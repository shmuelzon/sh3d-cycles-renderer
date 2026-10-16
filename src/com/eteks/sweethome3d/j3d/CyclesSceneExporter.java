package com.eteks.sweethome3d.j3d;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.media.j3d.Appearance;
import javax.media.j3d.BoundingSphere;
import javax.media.j3d.BranchGroup;
import javax.media.j3d.ColoringAttributes;
import javax.media.j3d.Geometry;
import javax.media.j3d.GeometryArray;
import javax.media.j3d.GeometryStripArray;
import javax.media.j3d.Group;
import javax.media.j3d.ImageComponent2D;
import javax.media.j3d.IndexedGeometryArray;
import javax.media.j3d.IndexedGeometryStripArray;
import javax.media.j3d.IndexedQuadArray;
import javax.media.j3d.IndexedTriangleArray;
import javax.media.j3d.IndexedTriangleFanArray;
import javax.media.j3d.IndexedTriangleStripArray;
import javax.media.j3d.Link;
import javax.media.j3d.Material;
import javax.media.j3d.Node;
import javax.media.j3d.PolygonAttributes;
import javax.media.j3d.QuadArray;
import javax.media.j3d.RenderingAttributes;
import javax.media.j3d.Shape3D;
import javax.media.j3d.TexCoordGeneration;
import javax.media.j3d.Texture;
import javax.media.j3d.TextureAttributes;
import javax.media.j3d.Transform3D;
import javax.media.j3d.TransformGroup;
import javax.media.j3d.TransparencyAttributes;
import javax.media.j3d.TriangleArray;
import javax.media.j3d.TriangleFanArray;
import javax.media.j3d.TriangleStripArray;
import javax.vecmath.Color3f;
import javax.vecmath.Point3d;
import javax.vecmath.Point3f;
import javax.vecmath.Vector3f;
import javax.vecmath.Vector4f;

import org.sunflow.SunflowAPI;
import org.sunflow.core.ParameterList;
import org.sunflow.core.ParameterList.InterpolationType;
import org.sunflow.core.light.SunSkyLight;

import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Compass;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.LightSource;
import com.eteks.sweethome3d.model.ObserverCamera;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.Transformation;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.viewcontroller.Object3DFactory;

/*
 * Exports a home for sh3d-cycles: the triangles of its 3D view (as built by SH3D's Object3DFactory), with the
 * original data of their Java 3D appearances, its light sources, sun, sky and camera.
 *
 * Writes to a directory:
 *   scene.json   materials, meshes (references to meshes.bin), lights and environment
 *   meshes.bin   little endian float32 arrays: per triangle corner positions, normals and texture coordinates
 *   textures/    the images of the textures
 *   camera.json  the camera
 *
 * Coordinates are in Java 3D's space: Y up, centimeters.
 */
class CyclesSceneExporter {
    static final int FORMAT_VERSION = 2;

    private final AbstractPhotoRenderer renderer; // For AbstractPhotoRenderer's helpers
    private final Home home;
    private final Object3DFactory object3dFactory;

    private final List<Map<String, Object>> materials = new ArrayList<Map<String, Object>>();
    private final Map<String, Integer> materialIndices = new LinkedHashMap<String, Integer>();
    private final List<Map<String, Object>> meshes = new ArrayList<Map<String, Object>>();
    private final List<Map<String, Object>> lights = new ArrayList<Map<String, Object>>();
    private final Map<Object, String> textures = new IdentityHashMap<Object, String>();
    private File directory;
    private FileChannel meshData;
    private long meshDataOffset;

    CyclesSceneExporter(AbstractPhotoRenderer renderer, Home home, Object3DFactory object3dFactory) {
        this.renderer = renderer;
        this.home = home;
        this.object3dFactory = object3dFactory != null
            ? object3dFactory : new AbstractPhotoRenderer.PhotoObject3DFactory();
    }

    void export(File directory, Camera camera, int width, int height) throws IOException {
        this.directory = directory;
        new File(directory, "textures").mkdirs();
        HomeEnvironment environment = this.home.getEnvironment();
        int lightColor = environment.getLightColor();

        try (FileChannel channel = new FileOutputStream(new File(directory, "meshes.bin")).getChannel()) {
            this.meshData = channel;
            this.meshDataOffset = 0;
            float subpartSize = environment.getSubpartSizeUnderLight();
            environment.setSubpartSizeUnderLight(0); // Subdivided walls and rooms are only useful to Java 3D lights
            try {
                for (Selectable item : this.home.getSelectableViewableItems()) {
                    if (item instanceof HomeFurnitureGroup) {
                        for (HomePieceOfFurniture piece : ((HomeFurnitureGroup)item).getAllFurniture()) {
                            if (!(piece instanceof HomeFurnitureGroup)) {
                                exportItem(piece);
                            }
                        }
                    } else {
                        exportItem(item);
                    }
                }
                // A ground large enough to reach the horizon, slightly under the floor of the first level
                Ground3D ground = new Ground3D(this.home, -1E6f / 2, -1E6f / 2, 1E6f, 1E6f, true);
                Transform3D translation = new Transform3D();
                translation.setTranslation(new Vector3f(0, -0.1f, 0));
                exportNode(ground, translation, "ground", true, null, 0);
            } finally {
                environment.setSubpartSizeUnderLight(subpartSize);
            }
        }
        exportCeilingLights(environment);

        Map<String, Object> scene = new LinkedHashMap<String, Object>();
        scene.put("version", FORMAT_VERSION);
        scene.put("lightColor", rgb(lightColor));
        scene.put("observer", this.home.getCamera() instanceof ObserverCamera);
        scene.put("environment", exportEnvironment(environment, camera));
        scene.put("materials", this.materials);
        scene.put("meshes", this.meshes);
        scene.put("lights", this.lights);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(directory, "scene.json")), StandardCharsets.UTF_8)) {
            writeJson(writer, scene);
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(directory, "camera.json")), StandardCharsets.UTF_8)) {
            writer.write(String.format(Locale.ROOT,
                "{\"x\":%f,\"y\":%f,\"z\":%f,\"yaw\":%f,\"pitch\":%f,\"fieldOfView\":%f,\"lens\":\"%s\",\"width\":%d,\"height\":%d}",
                camera.getX(), camera.getY(), camera.getZ(), camera.getYaw(), camera.getPitch(), camera.getFieldOfView(),
                camera.getLens().name(), width, height));
        }
    }

    /* Items */

    private void exportItem(Selectable item) throws IOException {
        Node node = (Node)this.object3dFactory.createObject3D(this.home, item, true);
        if (node == null) {
            return;
        }
        String id = item instanceof HomePieceOfFurniture ? ((HomePieceOfFurniture)item).getId()
            : item instanceof Wall ? ((Wall)item).getId() : item instanceof Room ? ((Room)item).getId() : null;
        // Walls and rooms are drawn transparent in the 3D view when the user chooses so, not in photos
        boolean ignoreTransparency = item instanceof Wall || item instanceof Room;
        if (item instanceof HomeLight) {
            HomeLight light = (HomeLight)item;
            Level level = light.getLevel();
            boolean on = light.getPower() > 0 && (level == null || level.isViewableAndVisible());
            String [] materialNames = light.getLightSourceMaterialNames();
            exportNode(node, new Transform3D(), id, false, on && materialNames.length > 0 ? light : null, 0);
            if (on && materialNames.length == 0) {
                exportLightSources(light, id);
            }
        } else {
            exportNode(node, new Transform3D(), id, ignoreTransparency, null, 0);
        }
    }

    private void exportNode(Node node, Transform3D parentTransform, String item, boolean ignoreTransparency,
                            HomeLight emittingLight, int depth) throws IOException {
        if (node instanceof Group) {
            Transform3D transform = parentTransform;
            if (node instanceof TransformGroup) {
                Transform3D local = new Transform3D();
                ((TransformGroup)node).getTransform(local);
                transform = new Transform3D(parentTransform);
                transform.mul(local);
            }
            Enumeration<?> children = ((Group)node).getAllChildren();
            while (children.hasMoreElements()) {
                exportNode((Node)children.nextElement(), transform, item, ignoreTransparency, emittingLight, depth + 1);
            }
        } else if (node instanceof Link) {
            exportNode(((Link)node).getSharedGroup(), parentTransform, item, ignoreTransparency, emittingLight, depth + 1);
        } else if (node instanceof Shape3D) {
            exportShape((Shape3D)node, parentTransform, item, ignoreTransparency, emittingLight);
        }
    }

    private void exportShape(Shape3D shape, Transform3D transform, String item, boolean ignoreTransparency,
                             HomeLight emittingLight) throws IOException {
        Appearance appearance = shape.getAppearance();
        String appearanceName = appearance != null ? appearance.getName() : null;
        boolean emitting = false;
        if (emittingLight != null && appearanceName != null) {
            for (String name : emittingLight.getLightSourceMaterialNames()) {
                emitting |= name.equals(appearanceName);
            }
        }
        RenderingAttributes rendering = appearance != null ? appearance.getRenderingAttributes() : null;
        TransparencyAttributes transparencyAttributes = appearance != null ? appearance.getTransparencyAttributes() : null;
        float transparency = transparencyAttributes != null && !ignoreTransparency ? transparencyAttributes.getTransparency() : 0;
        // Invisible shapes include the light bulbs of lights, shown only when they're selected
        if (rendering != null && !rendering.getVisible() || transparency >= 1 && !emitting) {
            return;
        }

        String shapeName = shape.getUserData() instanceof String ? (String)shape.getUserData() : "";
        int material = exportMaterial(appearance, shapeName, transparency, emitting ? emittingLight : null);
        TexCoordGeneration texCoordGeneration = appearance != null ? appearance.getTexCoordGeneration() : null;
        Transform3D textureTransform = new Transform3D();
        TextureAttributes textureAttributes = appearance != null ? appearance.getTextureAttributes() : null;
        if (textureAttributes != null) {
            textureAttributes.getTextureTransform(textureTransform);
        }
        PolygonAttributes polygon = appearance != null ? appearance.getPolygonAttributes() : null;
        // As Sweet Home 3D's renderers: culled front faces reverse the faces, and normals are flipped on demand
        boolean reverse = polygon != null && polygon.getCullFace() == PolygonAttributes.CULL_FRONT;
        boolean normalFlip = polygon != null && polygon.getBackFaceNormalFlip();

        for (int i = 0; i < shape.numGeometries(); i++) {
            Geometry geometry = shape.getGeometry(i);
            if (geometry instanceof GeometryArray) {
                exportGeometry((GeometryArray)geometry, transform, texCoordGeneration, textureTransform, reverse,
                    normalFlip, material, item, emitting && transparency >= 1);
            }
        }
    }

    /* Geometry: any kind of triangle, quad, strip or fan array, by copy, by reference or interleaved */

    private void exportGeometry(GeometryArray geometry, Transform3D transform, TexCoordGeneration texCoordGeneration,
                                Transform3D textureTransform, boolean reverse, boolean normalFlip,
                                int material, String item,
                                boolean invisible) throws IOException {
        int[] triangles = triangulate(geometry);
        if (triangles == null || triangles.length == 0) {
            return;
        }
        int format = geometry.getVertexFormat();
        int vertexCount = geometry.getVertexCount();
        float [] coordinates = new float [vertexCount * 3];
        float [] normals = (format & GeometryArray.NORMALS) != 0 ? new float [vertexCount * 3] : null;
        int textureDimension = (format & GeometryArray.TEXTURE_COORDINATE_4) != 0 ? 4
            : (format & GeometryArray.TEXTURE_COORDINATE_3) != 0 ? 3
            : (format & GeometryArray.TEXTURE_COORDINATE_2) != 0 ? 2 : 0;
        float [] uvs = textureDimension > 0 ? new float [vertexCount * 2] : null;
        readVertices(geometry, format, vertexCount, textureDimension, coordinates, normals, uvs);

        // Indices: triangles holds triples of corners, mapped to coordinate / normal / texture coordinate indices
        int [] coordinateIndices = null, normalIndices = null, uvIndices = null;
        if (geometry instanceof IndexedGeometryArray) {
            IndexedGeometryArray indexed = (IndexedGeometryArray)geometry;
            int indexCount = indexed.getIndexCount();
            if ((format & GeometryArray.BY_REFERENCE_INDICES) != 0) {
                coordinateIndices = indexed.getCoordIndicesRef();
                normalIndices = coordinateIndices;
                uvIndices = coordinateIndices;
            } else {
                coordinateIndices = new int [indexCount];
                indexed.getCoordinateIndices(0, coordinateIndices);
                if ((format & GeometryArray.USE_COORD_INDEX_ONLY) != 0) {
                    normalIndices = coordinateIndices;
                    uvIndices = coordinateIndices;
                } else {
                    if (normals != null) {
                        normalIndices = new int [indexCount];
                        indexed.getNormalIndices(0, normalIndices);
                    }
                    if (uvs != null) {
                        uvIndices = new int [indexCount];
                        indexed.getTextureCoordinateIndices(0, 0, uvIndices);
                    }
                }
            }
        }

        Transform3D normalTransform = new Transform3D(transform);
        normalTransform.invert();
        normalTransform.transpose(); // Normals follow the inverse transpose, for non uniform scales
        Vector4f planeS = new Vector4f(), planeT = new Vector4f();
        if (texCoordGeneration != null) {
            texCoordGeneration.getPlaneS(planeS);
            texCoordGeneration.getPlaneT(planeT);
        }

        int triangleCount = triangles.length / 3;
        float [] points = new float [triangleCount * 9];
        float [] cornerNormals = new float [triangleCount * 9];
        float [] cornerUvs = texCoordGeneration != null || uvs != null ? new float [triangleCount * 6] : null;
        Set<AbstractPhotoRenderer.Triangle> exported = new HashSet<AbstractPhotoRenderer.Triangle>();
        float [] trianglePoints = new float [9];
        int written = 0;
        Point3f point = new Point3f();
        Vector3f normal = new Vector3f();
        for (int t = 0; t < triangleCount; t++) {
            int [] corners = reverse ? new int [] {triangles[t * 3 + 2], triangles[t * 3 + 1], triangles[t * 3]}
                : new int [] {triangles[t * 3], triangles[t * 3 + 1], triangles[t * 3 + 2]};
            for (int c = 0; c < 3; c++) {
                int vertex = coordinateIndices != null ? coordinateIndices[corners[c]] : corners[c];
                point.set(coordinates[vertex * 3], coordinates[vertex * 3 + 1], coordinates[vertex * 3 + 2]);
                transform.transform(point);
                trianglePoints[c * 3] = point.x;
                trianglePoints[c * 3 + 1] = point.y;
                trianglePoints[c * 3 + 2] = point.z;
            }
            // Skip degenerate triangles and coplanar duplicates (e.g. both faces of a thin part)
            Vector3f edge1 = new Vector3f(trianglePoints[3] - trianglePoints[0], trianglePoints[4] - trianglePoints[1], trianglePoints[5] - trianglePoints[2]);
            Vector3f edge2 = new Vector3f(trianglePoints[6] - trianglePoints[0], trianglePoints[7] - trianglePoints[1], trianglePoints[8] - trianglePoints[2]);
            Vector3f faceNormal = new Vector3f();
            faceNormal.cross(edge1, edge2);
            if (faceNormal.lengthSquared() < 1E-12f || !exported.add(new AbstractPhotoRenderer.Triangle(trianglePoints, 0, 1, 2))) {
                continue;
            }
            faceNormal.normalize();
            for (int c = 0; c < 3; c++) {
                int vertex = coordinateIndices != null ? coordinateIndices[corners[c]] : corners[c];
                System.arraycopy(trianglePoints, c * 3, points, written * 9 + c * 3, 3);
                if (normals != null) {
                    int n = normalIndices != null ? normalIndices[corners[c]] : corners[c];
                    normal.set(normals[n * 3], normals[n * 3 + 1], normals[n * 3 + 2]);
                    normalTransform.transform(normal);
                    if (normalFlip) {
                        normal.negate();
                    }
                    if (normal.lengthSquared() > 0 && !Float.isNaN(normal.x)) {
                        normal.normalize();
                    } else {
                        normal.set(faceNormal);
                    }
                } else {
                    normal.set(faceNormal);
                }
                cornerNormals[written * 9 + c * 3] = normal.x;
                cornerNormals[written * 9 + c * 3 + 1] = normal.y;
                cornerNormals[written * 9 + c * 3 + 2] = normal.z;
                if (cornerUvs != null) {
                    float u, v;
                    if (texCoordGeneration != null) {
                        // Generated in object coordinates
                        float x = coordinates[vertex * 3], y = coordinates[vertex * 3 + 1], z = coordinates[vertex * 3 + 2];
                        u = planeS.x * x + planeS.y * y + planeS.z * z + planeS.w;
                        v = planeT.x * x + planeT.y * y + planeT.z * z + planeT.w;
                    } else {
                        int uv = uvIndices != null ? uvIndices[corners[c]] : corners[c];
                        u = uvs[uv * 2];
                        v = uvs[uv * 2 + 1];
                    }
                    if (textureTransform.getBestType() != Transform3D.IDENTITY) {
                        Point3f transformed = new Point3f(u, v, 0);
                        textureTransform.transform(transformed);
                        u = transformed.x;
                        v = transformed.y;
                    }
                    cornerUvs[written * 6 + c * 2] = Float.isFinite(u) ? u : 0;
                    cornerUvs[written * 6 + c * 2 + 1] = Float.isFinite(v) ? v : 0;
                }
            }
            if (normals != null) {
                // Cycles shades the side its faces' winding points to, so it must agree with the normals
                // (e.g. in mirrored models)
                int n = written * 9;
                float dot = 0;
                for (int c = 0; c < 3; c++) {
                    dot += faceNormal.x * cornerNormals[n + c * 3] + faceNormal.y * cornerNormals[n + c * 3 + 1]
                        + faceNormal.z * cornerNormals[n + c * 3 + 2];
                }
                if (dot < 0) {
                    swapCorners(points, n, 3);
                    swapCorners(cornerNormals, n, 3);
                    if (cornerUvs != null) {
                        swapCorners(cornerUvs, written * 6, 2);
                    }
                }
            }
            written++;
        }
        if (written == 0) {
            return;
        }
        Map<String, Object> mesh = new LinkedHashMap<String, Object>();
        mesh.put("material", material);
        if (item != null) {
            mesh.put("item", item);
        }
        if (invisible) {
            mesh.put("invisible", true);
        }
        mesh.put("triangles", written);
        mesh.put("points", writeArray(points, written * 9));
        mesh.put("normals", writeArray(cornerNormals, written * 9));
        if (cornerUvs != null) {
            mesh.put("uvs", writeArray(cornerUvs, written * 6));
        }
        this.meshes.add(mesh);
    }

    /* Swaps the second and third corners of the triangle at offset, with size values per corner */
    private static void swapCorners(float [] values, int offset, int size) {
        for (int k = 0; k < size; k++) {
            float value = values[offset + size + k];
            values[offset + size + k] = values[offset + 2 * size + k];
            values[offset + 2 * size + k] = value;
        }
    }

    /* Returns the vertex indices of the triangles of the geometry (indices into its index array if it's indexed) */
    private static int [] triangulate(GeometryArray geometry) {
        List<Integer> triangles = new ArrayList<Integer>();
        if (geometry instanceof IndexedGeometryArray) {
            int count = ((IndexedGeometryArray)geometry).getIndexCount();
            if (geometry instanceof IndexedTriangleArray) {
                addTriangles(triangles, 0, count);
            } else if (geometry instanceof IndexedQuadArray) {
                addQuads(triangles, 0, count);
            } else if (geometry instanceof IndexedGeometryStripArray) {
                int [] counts = new int [((IndexedGeometryStripArray)geometry).getNumStrips()];
                ((IndexedGeometryStripArray)geometry).getStripIndexCounts(counts);
                addStrips(triangles, counts, geometry instanceof IndexedTriangleFanArray,
                    geometry instanceof IndexedTriangleStripArray || geometry instanceof IndexedTriangleFanArray);
            } else {
                return null; // Lines and points
            }
        } else {
            int count = geometry.getValidVertexCount();
            int first = geometry.getInitialVertexIndex();
            if (geometry instanceof TriangleArray) {
                addTriangles(triangles, first, count);
            } else if (geometry instanceof QuadArray) {
                addQuads(triangles, first, count);
            } else if (geometry instanceof GeometryStripArray
                && (geometry instanceof TriangleStripArray || geometry instanceof TriangleFanArray)) {
                int [] counts = new int [((GeometryStripArray)geometry).getNumStrips()];
                ((GeometryStripArray)geometry).getStripVertexCounts(counts);
                int [] offsetCounts = counts;
                List<Integer> stripTriangles = new ArrayList<Integer>();
                addStrips(stripTriangles, offsetCounts, geometry instanceof TriangleFanArray, true);
                for (int index : stripTriangles) {
                    triangles.add(first + index);
                }
            } else {
                return null;
            }
        }
        int [] result = new int [triangles.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = triangles.get(i);
        }
        return result;
    }

    private static void addTriangles(List<Integer> triangles, int first, int count) {
        for (int i = first; i + 2 < first + count; i += 3) {
            triangles.add(i);
            triangles.add(i + 1);
            triangles.add(i + 2);
        }
    }

    private static void addQuads(List<Integer> triangles, int first, int count) {
        for (int i = first; i + 3 < first + count; i += 4) {
            triangles.add(i);
            triangles.add(i + 1);
            triangles.add(i + 2);
            triangles.add(i);
            triangles.add(i + 2);
            triangles.add(i + 3);
        }
    }

    private static void addStrips(List<Integer> triangles, int [] counts, boolean fan, boolean triangle) {
        if (!triangle) {
            return;
        }
        int start = 0;
        for (int count : counts) {
            for (int i = 0; i + 2 < count; i++) {
                if (fan) {
                    triangles.add(start);
                    triangles.add(start + i + 1);
                    triangles.add(start + i + 2);
                } else if (i % 2 == 0) {
                    triangles.add(start + i);
                    triangles.add(start + i + 1);
                    triangles.add(start + i + 2);
                } else {
                    triangles.add(start + i + 1);
                    triangles.add(start + i);
                    triangles.add(start + i + 2);
                }
            }
            start += count;
        }
    }

    private static void readVertices(GeometryArray geometry, int format, int vertexCount, int textureDimension,
                                     float [] coordinates, float [] normals, float [] uvs) {
        if ((format & GeometryArray.BY_REFERENCE) == 0) {
            geometry.getCoordinates(0, coordinates);
            if (normals != null) {
                geometry.getNormals(0, normals);
            }
            if (uvs != null) {
                float [] all = new float [vertexCount * textureDimension];
                geometry.getTextureCoordinates(0, 0, all);
                for (int i = 0; i < vertexCount; i++) {
                    uvs[i * 2] = all[i * textureDimension];
                    uvs[i * 2 + 1] = all[i * textureDimension + 1];
                }
            }
        } else if ((format & GeometryArray.INTERLEAVED) != 0) {
            // Per vertex: texture coordinates of each set, color, normal, coordinates
            float [] data = geometry.getInterleavedVertices();
            int textureSets = geometry.getTexCoordSetCount();
            int colorSize = (format & GeometryArray.COLOR_4) == GeometryArray.COLOR_4 ? 4
                : (format & GeometryArray.COLOR_3) != 0 ? 3 : 0;
            int normalOffset = textureSets * textureDimension + colorSize;
            int coordinateOffset = normalOffset + (normals != null ? 3 : 0);
            int stride = coordinateOffset + 3;
            for (int i = 0; i < vertexCount; i++) {
                System.arraycopy(data, i * stride + coordinateOffset, coordinates, i * 3, 3);
                if (normals != null) {
                    System.arraycopy(data, i * stride + normalOffset, normals, i * 3, 3);
                }
                if (uvs != null) {
                    System.arraycopy(data, i * stride, uvs, i * 2, 2);
                }
            }
        } else {
            float [] coordinateData = geometry.getCoordRefFloat();
            if (coordinateData != null) {
                System.arraycopy(coordinateData, 0, coordinates, 0, Math.min(coordinates.length, coordinateData.length));
            } else if (geometry.getCoordRef3f() != null) {
                Point3f [] points = geometry.getCoordRef3f();
                for (int i = 0; i < Math.min(vertexCount, points.length); i++) {
                    coordinates[i * 3] = points[i].x;
                    coordinates[i * 3 + 1] = points[i].y;
                    coordinates[i * 3 + 2] = points[i].z;
                }
            }
            if (normals != null && geometry.getNormalRefFloat() != null) {
                float [] normalData = geometry.getNormalRefFloat();
                System.arraycopy(normalData, 0, normals, 0, Math.min(normals.length, normalData.length));
            }
            if (uvs != null && geometry.getTexCoordRefFloat(0) != null) {
                float [] uvData = geometry.getTexCoordRefFloat(0);
                for (int i = 0; i < vertexCount && i * textureDimension + 1 < uvData.length; i++) {
                    uvs[i * 2] = uvData[i * textureDimension];
                    uvs[i * 2 + 1] = uvData[i * textureDimension + 1];
                }
            }
        }
    }

    /* Materials: the Java 3D appearance data, interpreted by sh3d-cycles */

    private int exportMaterial(Appearance appearance, String shapeName, float transparency,
                               HomeLight emittingLight) throws IOException {
        Material material = appearance != null ? appearance.getMaterial() : null;
        Texture texture = appearance != null ? appearance.getTexture() : null;
        boolean mirror = shapeName.startsWith(ModelManager.MIRROR_SHAPE_PREFIX);
        boolean windowPane = shapeName.startsWith(ModelManager.WINDOW_PANE_SHAPE_PREFIX);

        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        Color3f color = new Color3f(0.8f, 0.8f, 0.8f);
        if (material != null) {
            material.getDiffuseColor(color);
        } else if (appearance != null && appearance.getColoringAttributes() != null) {
            appearance.getColoringAttributes().getColor(color);
        }
        String kind = emittingLight != null ? "emission"
            : mirror ? "mirror"
            : material != null && !material.getLightingEnable() || material == null && texture == null ? "unlit"
            : "surface";
        entry.put("kind", kind);
        if (appearance != null && appearance.getName() != null) {
            entry.put("name", appearance.getName());
        }
        entry.put("color", new float [] {color.x, color.y, color.z});
        if (texture != null && !mirror) {
            entry.put("texture", exportTexture(texture));
        }
        if (material != null) {
            Color3f specular = new Color3f();
            material.getSpecularColor(specular);
            entry.put("specular", new float [] {specular.x, specular.y, specular.z});
            entry.put("shininess", material.getShininess());
            if (material instanceof OBJMaterial && ((OBJMaterial)material).isOpticalDensitySet()) {
                entry.put("ior", ((OBJMaterial)material).getOpticalDensity());
            }
        }
        if (transparency > 0) {
            entry.put("transparency", transparency);
        }
        if (windowPane) {
            entry.put("windowPane", true);
        }
        if (emittingLight != null) {
            entry.put("power", emittingLight.getPower());
        }
        StringBuilder key = new StringBuilder();
        for (Map.Entry<String, Object> field : entry.entrySet()) {
            Object value = field.getValue();
            key.append(field.getKey()).append('=').append(value instanceof float [] ? Arrays.toString((float [])value) : value).append(';');
        }
        Integer index = this.materialIndices.get(key.toString());
        if (index == null) {
            index = this.materials.size();
            this.materials.add(entry);
            this.materialIndices.put(key.toString(), index);
        }
        return index;
    }

    private String exportTexture(Texture texture) throws IOException {
        String path = this.textures.get(texture);
        if (path != null) {
            return path;
        }
        byte [] data = null;
        if (texture.getUserData() instanceof URL) {
            try (InputStream in = ((URL)texture.getUserData()).openStream()) {
                data = readAll(in);
            } catch (IOException ex) {
                data = null;
            }
        }
        String extension;
        if (data != null && isImage(data)) {
            extension = data[0] == (byte)0xFF ? ".jpg" : data[0] == (byte)0x89 ? ".png" : data[0] == 'B' ? ".bmp" : ".gif";
        } else {
            RenderedImage image = ((ImageComponent2D)texture.getImage(0)).getRenderedImage();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            data = bytes.toByteArray();
            extension = ".png";
        }
        path = "textures/texture" + this.textures.size() + extension;
        try (OutputStream out = new FileOutputStream(new File(this.directory, path))) {
            out.write(data);
        }
        this.textures.put(texture, path);
        return path;
    }

    private static boolean isImage(byte [] data) {
        return data.length > 4 && ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 // JPEG
            || (data[0] & 0xFF) == 0x89 && data[1] == 'P' // PNG
            || data[0] == 'B' && data[1] == 'M' || data[0] == 'G' && data[1] == 'I');
    }

    /* Lights */

    private void exportLightSources(final HomeLight light, final String item) {
        final ModelManager modelManager = ModelManager.getInstance();
        if (!light.isHorizontallyRotated() && light.getModelTransformations() == null) {
            Transform3D lightTransform = modelManager.getPieceOfFurnitureNormalizedModelTransformation(light, null);
            for (LightSource source : light.getLightSources()) {
                addLightSource(light, source, lightTransform, item);
            }
            return;
        }
        // The light's model is needed to place its sources when it's rotated around a horizontal axis or deformed
        modelManager.loadModel(light.getModel(), true, new ModelManager.ModelObserver() {
            public void modelUpdated(BranchGroup modelRoot) {
                float [][] rotation = light.getModelRotation();
                Transformation [] transformations = light.getModelTransformations();
                Node undeformed = null;
                Transform3D undeformedNormalization = null;
                if (transformations != null) {
                    undeformed = modelManager.cloneNode(modelRoot);
                    undeformedNormalization = modelManager.getNormalizedTransform(modelRoot, rotation, 1, light.isModelCenteredAtOrigin());
                    undeformedNormalization.invert();
                    renderer.updateModelTransformations(modelRoot, transformations);
                }
                Transform3D normalization = modelManager.getNormalizedTransform(modelRoot, rotation, 1, light.isModelCenteredAtOrigin());
                TransformGroup normalized = new TransformGroup(normalization);
                normalized.addChild(modelRoot);
                Transform3D lightTransform = modelManager.getPieceOfFurnitureNormalizedModelTransformation(light, normalized);
                for (LightSource source : light.getLightSources()) {
                    Transform3D sourceTransform = lightTransform;
                    if (transformations != null) {
                        // A source in a deformed part of the model follows the deformation of that part
                        Point3f location = normalizedLocation(source);
                        undeformedNormalization.transform(location);
                        BoundingSphere bounds = new BoundingSphere(new Point3d(location), radius(light, source));
                        for (Transformation transformation : transformations) {
                            String name = transformation.getName() + ModelManager.DEFORMABLE_TRANSFORM_GROUP_SUFFIX;
                            if (renderer.intersectsDeformedNode(undeformed, bounds, name)) {
                                sourceTransform = new Transform3D(lightTransform);
                                sourceTransform.mul(normalization);
                                sourceTransform.mul(renderer.getDeformation(modelRoot, new Transform3D(), name));
                                sourceTransform.mul(undeformedNormalization);
                                break;
                            }
                        }
                    }
                    addLightSource(light, source, sourceTransform, item);
                }
            }

            public void modelError(Exception ex) {
                // Ignore the light, like the other renderers
            }
        });
    }

    private void addLightSource(HomeLight light, LightSource source, Transform3D lightTransform, String item) {
        Point3f location = normalizedLocation(source);
        lightTransform.transform(location);
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("type", "point");
        entry.put("position", new float [] {location.x, location.y, location.z});
        entry.put("radius", radius(light, source));
        entry.put("color", rgb(source.getColor()));
        entry.put("power", light.getPower());
        if (item != null) {
            entry.put("item", item);
        }
        this.lights.add(entry);
    }

    /* Light source location in the normalized model box, centered at its origin */
    private static Point3f normalizedLocation(LightSource source) {
        return new Point3f(source.getX() - 0.5f, source.getZ() - 0.5f, 0.5f - source.getY());
    }

    private static float radius(HomeLight light, LightSource source) {
        return source.getDiameter() != null ? source.getDiameter() * light.getWidth() / 2 : 3.25f;
    }

    /* The optional light SH3D adds under the ceiling of each room */
    private void exportCeilingLights(HomeEnvironment environment) {
        int color = environment.getCeillingLightColor();
        if (color <= 0) {
            return;
        }
        for (Room room : this.home.getRooms()) {
            Level level = room.getLevel();
            if (room.isCeilingVisible() && (level == null || level.isViewableAndVisible())) {
                float elevation = level != null ? level.getElevation() : 0;
                float height = level != null ? level.getHeight() : this.home.getWallHeight();
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("type", "ceiling");
                entry.put("position", new float [] {room.getXCenter(), elevation + height - 25, room.getYCenter()});
                entry.put("radius", 20f);
                entry.put("color", rgb(color));
                entry.put("power", (float)Math.sqrt(room.getArea()) / 3);
                entry.put("item", room.getId());
                this.lights.add(entry);
            }
        }
    }

    /* Sun and sky */

    /* Irradiance of the sun of SH3D's renderers, in Cycles' units: like YafaRay, a sphere of radius 10000 cm at 1000000
     * cm, of a radiance of power 20 times the home light color times the square root of SunFlow's sun color */
    private float [] sunIrradiance(Compass compass, float [] sun) {
        SunSkyLight sunSkyLight = new SunSkyLight();
        ParameterList parameters = new ParameterList();
        parameters.addVectors("up", InterpolationType.NONE, new float [] {0, 1, 0});
        parameters.addVectors("east", InterpolationType.NONE,
            new float [] {(float)Math.sin(compass.getNorthDirection()), 0, (float)Math.cos(compass.getNorthDirection())});
        parameters.addVectors("sundir", InterpolationType.NONE, new float [] {sun[0], sun[1], sun[2]});
        sunSkyLight.update(parameters, new SunflowAPI());
        float [] sunColor = sunSkyLight.getSunColor().getRGB();
        int lightColor = this.home.getEnvironment().getLightColor();
        float solidAngle = (float)(Math.PI * 1E-4); // pi * (10000 / 1000000)^2
        return new float [] {
            20 * (lightColor >> 16 & 0xFF) * (float)Math.sqrt(Math.max(0, sunColor[0])) * solidAngle,
            20 * (lightColor >> 8 & 0xFF) * (float)Math.sqrt(Math.max(0, sunColor[1])) * solidAngle,
            20 * (lightColor & 0xFF) * (float)Math.sqrt(Math.max(0, sunColor[2])) * solidAngle};
    }

    private Map<String, Object> exportEnvironment(HomeEnvironment environment, Camera camera) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        Compass compass = this.home.getCompass();
        float [] sun = this.renderer.getSunDirection(compass, Camera.convertTimeToTimeZone(camera.getTime(), compass.getTimeZone()));
        // SH3D's renderers consider it's night when the sun is more than about 4 degrees under the horizon
        if (sun[1] > -0.075f) {
            entry.put("sun", sun);
            entry.put("sunIrradiance", sunIrradiance(compass, sun));
        } else {
            entry.put("sun", null);
        }
        HomeTexture skyTexture = environment.getSkyTexture();
        if (skyTexture != null && this.home.getCamera() instanceof ObserverCamera) {
            entry.put("skyTexture", exportSkyTexture(skyTexture));
        }
        entry.put("skyColor", rgb(environment.getSkyColor()));
        return entry;
    }

    /*
     * The sky texture of a virtual visit as an equirectangular image: the panorama on the upper half and its mirror
     * on the lower half, offset like in SH3D's 3D view
     */
    private String exportSkyTexture(HomeTexture skyTexture) throws IOException {
        BufferedImage sky;
        try (InputStream in = skyTexture.getImage().openStream()) {
            sky = ImageIO.read(in);
        }
        BufferedImage image = new BufferedImage(sky.getWidth(), sky.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        int offset = (int)(sky.getWidth() * skyTexture.getXOffset());
        for (int shift : new int [] {offset, offset - sky.getWidth()}) {
            g.drawImage(sky, shift, 0, null);
            AffineTransform mirror = AffineTransform.getTranslateInstance(shift, sky.getHeight() * 2);
            mirror.scale(1, -1);
            g.drawImage(sky, mirror, null);
        }
        g.dispose();
        String path = "textures/sky.png";
        ImageIO.write(image, "png", new File(this.directory, path));
        return path;
    }

    private static float [] rgb(int color) {
        return new float [] {((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f};
    }

    /* Output helpers */

    private Map<String, Object> writeArray(float [] values, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values, 0, count);
        while (buffer.hasRemaining()) {
            this.meshData.write(buffer);
        }
        Map<String, Object> reference = new LinkedHashMap<String, Object>();
        reference.put("offset", this.meshDataOffset);
        reference.put("count", count);
        this.meshDataOffset += count * 4L;
        return reference;
    }

    private static byte [] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte [] buffer = new byte [1 << 16];
        for (int n; (n = in.read(buffer)) > 0; ) {
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }

    private static void writeJson(Writer w, Object value) throws IOException {
        if (value == null) {
            w.write("null");
        } else if (value instanceof Map) {
            w.write('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>)value).entrySet()) {
                if (!first) {
                    w.write(',');
                }
                first = false;
                writeString(w, String.valueOf(entry.getKey()));
                w.write(':');
                writeJson(w, entry.getValue());
            }
            w.write('}');
        } else if (value instanceof List) {
            w.write('[');
            boolean first = true;
            for (Object item : (List<?>)value) {
                if (!first) {
                    w.write(',');
                }
                first = false;
                writeJson(w, item);
            }
            w.write(']');
        } else if (value instanceof float []) {
            float [] values = (float [])value;
            w.write('[');
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    w.write(',');
                }
                w.write(Float.isFinite(values[i]) ? Float.toString(values[i]) : "0");
            }
            w.write(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            w.write(value.toString());
        } else {
            writeString(w, value.toString());
        }
    }

    private static void writeString(Writer w, String s) throws IOException {
        w.write('"');
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                w.write('\\');
                w.write(c);
            } else if (c < 0x20) {
                w.write(String.format("\\u%04x", (int)c));
            } else {
                w.write(c);
            }
        }
        w.write('"');
    }
}
