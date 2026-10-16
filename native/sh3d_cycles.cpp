/*
 * sh3d-cycles: renders a Sweet Home 3D scene exported by CyclesSceneExporter (scene.json version 2, meshes.bin,
 * textures/, camera.json) with Cycles, and writes the image as an 8-bit PNG tone-mapped with OpenColorIO.
 *
 * Usage: sh3d-cycles <export dir> <output.png> [options]
 *        sh3d-cycles --list-devices   lists the devices Cycles can render with
 *   --width W --height H       output size (default: from camera.json)
 *   --samples N                max samples per pixel (adaptive sampling, default 256)
 *   --device auto|cpu|hip|...  Cycles device (default auto: first GPU found, else CPU), "+cpu" to add the CPU
 *   --threads N                CPU threads (default 0: all)
 *   --auto-exposure on|off     meter a quick low resolution pass to set the exposure (default off: a fixed exposure,
 *                              as SH3D's other renderers, so that the renders of a scene are comparable)
 *   --exposure EV              film exposure, added to the automatic one (default 0)
 *   --noise-threshold T        adaptive sampling noise threshold, as Blender's (default 0.01)
 *   --sky-strength S|auto      strength of the sky, relative to YafaRay's (default auto: 1)
 *   --sky-saturation S         saturation of the sky light (default 1)
 *   --night-sky S              world strength at night (default 0.02)
 *   --view NAME                OCIO view, e.g. Standard or AgX (default Standard: sRGB clipped at 1, as SH3D's other
 *                              renderers)
 *   --highlight-compression C  part of the highlights' excess over 1 stop above middle grey that's compressed
 *                              locally, e.g. to see the outdoors through windows, 0 for none (default 0)
 *   --look NAME                OCIO look, "_" for spaces, e.g. Medium_High_Contrast with AgX (default None)
 *   --ocio PATH                OCIO config (default: <executable dir>/colormanagement/config.ocio)
 *   --progress                 write progressive previews to <output>.preview.png, announced on stdout
 *   --log-level LEVEL          Cycles log level, e.g. warning, info, debug
 *
 * Prints "STATUS <Cycles' status>" lines when it changes (e.g. "Loading render kernels..." or "12 / 80 render kernels
 * loaded..." while GPU kernels are compiled), "PROGRESS <0..1> <samples>" and "PREVIEW <path>" lines while rendering and
 * a final "RESULT {json}" line.
 */

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <tuple>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "device/device.h"
#include "scene/background.h"
#include "scene/camera.h"
#include "scene/film.h"
#include "scene/integrator.h"
#include "scene/light.h"
#include "scene/mesh.h"
#include "scene/object.h"
#include "scene/pass.h"
#include "scene/scene.h"
#include "scene/shader.h"
#include "scene/shader_graph.h"
#include "scene/shader_nodes.h"
#include "session/buffers.h"
#include "session/output_driver.h"
#include "session/session.h"
#include "util/log.h"
#include "util/path.h"
#include "util/transform.h"
#include "util/version.h"

#include <OpenColorIO/OpenColorIO.h>
#include <OpenImageIO/imageio.h>

#include "json.h"

namespace OCIO = OCIO_NAMESPACE;
using namespace ccl;

static const float CM = 0.01f; /* SH3D centimeters to meters */

static double now()
{
  return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

/* ------------------------------------------------------------------------------------------------ options */

struct Options {
  std::string export_dir, output;
  int width = 0, height = 0, samples = 256, threads = 0;
  std::string device = "auto";
  bool auto_exposure = false, progress = false;
  float exposure = 0, sky_saturation = 1, night_sky = 0.02f, noise_threshold = 0.01f, highlight_compression = 0;
  std::string sky_strength = "auto";
  std::string view = "Standard", look = "None", ocio;
};

static Options parse_options(int argc, const char **argv)
{
  if (argc < 3) {
    fprintf(stderr, "Usage: sh3d-cycles <export dir> <output.png> [options]\n");
    exit(2);
  }
  Options o;
  o.export_dir = argv[1];
  o.output = argv[2];
  for (int i = 3; i < argc; i++) {
    const std::string key = argv[i];
    if (key == "--progress") {
      o.progress = true;
      continue;
    }
    if (i + 1 >= argc) {
      fprintf(stderr, "Missing value for %s\n", key.c_str());
      exit(2);
    }
    const std::string value = argv[++i];
    if (key == "--width") o.width = std::stoi(value);
    else if (key == "--height") o.height = std::stoi(value);
    else if (key == "--samples") o.samples = std::stoi(value);
    else if (key == "--threads") o.threads = std::stoi(value);
    else if (key == "--device") o.device = value;
    else if (key == "--auto-exposure") o.auto_exposure = value == "on";
    else if (key == "--exposure") o.exposure = std::stof(value);
    else if (key == "--noise-threshold") o.noise_threshold = std::stof(value);
    else if (key == "--highlight-compression") o.highlight_compression = std::stof(value);
    else if (key == "--sky-strength") o.sky_strength = value;
    else if (key == "--sky-saturation") o.sky_saturation = std::stof(value);
    else if (key == "--night-sky") o.night_sky = std::stof(value);
    else if (key == "--view") o.view = value;
    else if (key == "--look") o.look = value;
    else if (key == "--ocio") o.ocio = value;
    else if (key == "--log-level") log_level_set(value);
    else {
      fprintf(stderr, "Unknown option %s\n", key.c_str());
      exit(2);
    }
  }
  std::replace(o.look.begin(), o.look.end(), '_', ' ');
  if (o.ocio.empty()) {
    o.ocio = path_join(path_get(""), "colormanagement/config.ocio");
  }
  return o;
}

static std::string read_file(const std::string &path)
{
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    throw std::runtime_error("Can't read " + path);
  }
  std::stringstream buffer;
  buffer << in.rdbuf();
  return buffer.str();
}

/* ------------------------------------------------------------------------------------------------ device */

static std::string upper(std::string s)
{
  std::transform(s.begin(), s.end(), s.begin(), ::toupper);
  return s;
}

static DeviceInfo select_device(const std::string &requested, int threads, std::string &name)
{
  bool with_cpu = false;
  std::string type = requested;
  if (type.size() > 4 && type.compare(type.size() - 4, 4, "+cpu") == 0) {
    with_cpu = true;
    type = type.substr(0, type.size() - 4);
  }
  const vector<DeviceInfo> cpus = Device::available_devices(DEVICE_MASK_CPU);
  if (type != "cpu") {
    const std::vector<DeviceType> order = type == "auto" ?
                                              std::vector<DeviceType>{DEVICE_OPTIX, DEVICE_CUDA, DEVICE_HIP,
                                                                      DEVICE_ONEAPI, DEVICE_METAL} :
                                              std::vector<DeviceType>{Device::type_from_string(
                                                  upper(type).c_str())};
    for (const DeviceType device_type : order) {
      vector<DeviceInfo> devices = Device::available_devices(DEVICE_MASK(device_type));
      if (devices.empty()) {
        continue;
      }
      name = Device::string_from_type(device_type);
      for (const DeviceInfo &d : devices) {
        name += " [" + d.description + "]";
      }
      if (with_cpu && !cpus.empty()) {
        devices.push_back(cpus.front());
        name += "+CPU";
      }
      return devices.size() == 1 ? devices.front() : Device::get_multi_device(devices, threads, true);
    }
    fprintf(stderr, "No %s device found, using CPU\n", type.c_str());
  }
  name = "CPU [" + cpus.front().description + "]";
  return cpus.front();
}

/* ------------------------------------------------------------------------------------------------ scene */

class SceneBuilder {
 public:
  SceneBuilder(Scene *scene, const Json &data, const std::string &blob, const Options &options)
      : scene_(scene), data_(data), blob_(blob), options_(options)
  {
    if (int(data["version"].num()) != 2) {
      throw std::runtime_error("Unsupported scene format version");
    }
    light_color_ = color3(data["lightColor"], one_float3());
  }

  int triangles = 0, lights = 0;

  void build()
  {
    for (const Json &material : data_["materials"].array) {
      materials_.push_back(build_material(material));
    }
    build_meshes();
    build_lights();
    build_world();
  }

 private:
  Scene *scene_;
  const Json &data_;
  const std::string &blob_;
  const Options &options_;
  std::vector<Shader *> materials_;
  float3 light_color_;
  Shader *light_shader_ = nullptr;
  std::unordered_map<std::string, bool> alpha_cache_;

  const float *floats(const Json &ref, size_t &count) const
  {
    count = size_t(ref["count"].num());
    return reinterpret_cast<const float *>(blob_.data() + size_t(ref["offset"].num()));
  }

  /* Java 3D (x, y up, z) -> Blender (x, -z, y) */
  static float3 to_blender(const float x, const float y, const float z)
  {
    return make_float3(x, -z, y);
  }

  static float3 color3(const Json &value, const float3 fallback)
  {
    if (value.type != Json::ARRAY || value.size() < 3) {
      return fallback;
    }
    return make_float3(value[0].num(), value[1].num(), value[2].num());
  }

  static float luminance(const float3 c)
  {
    return 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z;
  }

  std::string texture_path(const std::string &relative) const
  {
    return path_join(options_.export_dir, relative);
  }

  Shader *add_shader(const std::string &name, unique_ptr<ShaderGraph> graph)
  {
    Shader *shader = scene_->create_node<Shader>();
    shader->name = ustring(name);
    shader->set_graph(std::move(graph));
    shader->tag_update(scene_);
    return shader;
  }

  bool has_alpha(const std::string &path)
  {
    const auto it = alpha_cache_.find(path);
    if (it != alpha_cache_.end()) {
      return it->second;
    }
    bool alpha = false;
    unique_ptr<OIIO::ImageInput> in = OIIO::ImageInput::open(texture_path(path));
    if (in) {
      alpha = in->spec().alpha_channel >= 0 || in->spec().nchannels == 4;
      in->close();
    }
    alpha_cache_[path] = alpha;
    return alpha;
  }

  ImageTextureNode *image_node(ShaderGraph *graph, const std::string &path)
  {
    ImageTextureNode *tex = graph->create_node<ImageTextureNode>();
    tex->set_filename(ustring(texture_path(path)));
    /* Auto detection (sRGB for 8-bit images) like Blender; "__builtin_srgb" shows 8-bit textures too bright */
    tex->set_colorspace(u_colorspace_auto);
    tex->set_interpolation(INTERPOLATION_LINEAR);
    return tex;
  }

  /* Phong exponent (Java 3D shininess, 1 to 128) -> GGX roughness */
  static float roughness_from_phong(const float ns)
  {
    const float alpha = sqrtf(2.0f / (std::max(ns, 1.0f) + 2.0f));
    return std::min(1.0f, std::max(0.05f, sqrtf(alpha)));
  }

  /* Thin glass: transparent with a Schlick fresnel reflection on both faces, which lets the sun through windows */
  void thin_glass(ShaderGraph *g, ShaderInput *surface, const float3 tint, const float ior)
  {
    const float f0 = powf((ior - 1) / (ior + 1), 2);
    TransparentBsdfNode *transparent = g->create_node<TransparentBsdfNode>();
    transparent->set_color(tint);
    GlossyBsdfNode *glossy = g->create_node<GlossyBsdfNode>();
    glossy->set_roughness(0.0f);
    GeometryNode *geometry = g->create_node<GeometryNode>();
    VectorMathNode *dot = g->create_node<VectorMathNode>();
    dot->set_math_type(NODE_VECTOR_MATH_DOT_PRODUCT);
    g->connect(geometry->output("Normal"), dot->input("Vector1"));
    g->connect(geometry->output("Incoming"), dot->input("Vector2"));
    MathNode *cosine = g->create_node<MathNode>();
    cosine->set_math_type(NODE_MATH_ABSOLUTE);
    g->connect(dot->output("Value"), cosine->input("Value1"));
    MathNode *one_minus = g->create_node<MathNode>();
    one_minus->set_math_type(NODE_MATH_SUBTRACT);
    one_minus->set_value1(1.0f);
    g->connect(cosine->output("Value"), one_minus->input("Value2"));
    MathNode *power = g->create_node<MathNode>();
    power->set_math_type(NODE_MATH_POWER);
    power->set_value2(5.0f);
    g->connect(one_minus->output("Value"), power->input("Value1"));
    MathNode *fresnel = g->create_node<MathNode>();
    fresnel->set_math_type(NODE_MATH_MULTIPLY_ADD);
    fresnel->set_value2(1 - f0);
    fresnel->set_value3(f0);
    g->connect(power->output("Value"), fresnel->input("Value1"));
    MixClosureNode *mix = g->create_node<MixClosureNode>();
    g->connect(fresnel->output("Value"), mix->input("Fac"));
    g->connect(transparent->output("BSDF"), mix->input("Closure1"));
    g->connect(glossy->output("BSDF"), mix->input("Closure2"));
    g->connect(mix->output("Closure"), surface);
  }

  Shader *emission_shader(const float3 radiance)
  {
    unique_ptr<ShaderGraph> graph = make_unique<ShaderGraph>();
    EmissionNode *emission = graph->create_node<EmissionNode>();
    const float m = std::max(reduce_max(radiance), 1e-6f);
    emission->set_color(radiance / m);
    emission->set_strength(m);
    graph->connect(emission->output("Emission"), graph->output()->input("Surface"));
    return add_shader("emission", std::move(graph));
  }

  Shader *build_material(const Json &material)
  {
    const std::string kind = material["kind"].str("surface");
    const float3 color = color3(material["color"], make_float3(0.8f, 0.8f, 0.8f));
    const std::string texture = material["texture"].str();
    const float transparency = float(material["transparency"].num(0));
    unique_ptr<ShaderGraph> graph = make_unique<ShaderGraph>();
    ShaderGraph *g = graph.get();
    ShaderInput *surface = g->output()->input("Surface");

    if (kind == "emission") {
      /* Materials of a light emitting light, as bright as with SH3D's SunFlow renderer */
      const float power = float(material["power"].num(0.5));
      return emission_shader(color * light_color_ * (8160.0f * power * power));
    }
    if (kind == "unlit") {
      EmissionNode *emission = g->create_node<EmissionNode>();
      emission->set_strength(1.0f);
      if (!texture.empty()) {
        g->connect(image_node(g, texture)->output("Color"), emission->input("Color"));
      }
      else {
        emission->set_color(color);
      }
      g->connect(emission->output("Emission"), surface);
      return add_shader("unlit", std::move(graph));
    }
    if (kind == "mirror") {
      PrincipledBsdfNode *bsdf = g->create_node<PrincipledBsdfNode>();
      bsdf->set_base_color(color);
      bsdf->set_metallic(1.0f);
      bsdf->set_roughness(0.0f);
      g->connect(bsdf->output("BSDF"), surface);
      return add_shader("mirror", std::move(graph));
    }
    if (texture.empty() && transparency > 0) {
      /* Untextured transparent materials are glass, tinted by their color as much as they're opaque */
      const float3 tint = make_float3(transparency, transparency, transparency) + color * (1 - transparency);
      float ior = float(material["ior"].num(1.5));
      if (ior <= 1.0f) {
        ior = 1.5f;
      }
      thin_glass(g, surface, tint, ior);
      return add_shader("glass", std::move(graph));
    }

    PrincipledBsdfNode *bsdf = g->create_node<PrincipledBsdfNode>();
    if (!texture.empty()) {
      ImageTextureNode *tex = image_node(g, texture);
      g->connect(tex->output("Color"), bsdf->input("Base Color"));
      const bool alpha = has_alpha(texture);
      if (alpha && transparency <= 0) {
        g->connect(tex->output("Alpha"), bsdf->input("Alpha"));
      }
      else if (transparency > 0) {
        MathNode *opacity = g->create_node<MathNode>();
        opacity->set_math_type(NODE_MATH_MULTIPLY);
        opacity->set_value1(1.0f);
        opacity->set_value2(1 - transparency);
        if (alpha) {
          g->connect(tex->output("Alpha"), opacity->input("Value1"));
        }
        g->connect(opacity->output("Value"), bsdf->input("Alpha"));
      }
    }
    else {
      bsdf->set_base_color(color);
    }

    /* Shininess and specular color: SH3D sets the specular color to the shininess of its materials, and the
     * shininess exponent to 128 times it; OBJ models give them as Ks and Ns */
    const float shininess = float(material["shininess"].num(1));
    const float3 specular = color3(material["specular"], zero_float3());
    if (shininess <= 1.0f || reduce_max(specular) <= 0.0f) {
      bsdf->set_roughness(0.9f);
      bsdf->set_specular_ior_level(0.25f);
    }
    else {
      bsdf->set_roughness(roughness_from_phong(shininess));
      bsdf->set_specular_ior_level(std::min(1.0f, 0.25f + luminance(specular)));
    }
    if (material.has("ior") && material["ior"].num() > 1) {
      bsdf->set_ior(float(material["ior"].num()));
    }
    g->connect(bsdf->output("BSDF"), surface);
    return add_shader(material["name"].str("surface"), std::move(graph));
  }

  /* Meshes: per triangle corner positions, normals and texture coordinates, merged by item and material */
  void build_meshes()
  {
    struct Group {
      std::vector<const Json *> meshes;
      size_t triangles = 0;
    };
    /* One object per item and material: objects spanning the whole home, as with one per material, overlap so much
     * that rendering is about 50% slower */
    std::map<std::tuple<int, bool, std::string>, Group> groups;
    for (const Json &mesh : data_["meshes"].array) {
      Group &group = groups[{int(mesh["material"].num()), mesh["invisible"].boolean, mesh["item"].str()}];
      group.meshes.push_back(&mesh);
      group.triangles += size_t(mesh["triangles"].num());
    }
    for (const auto &entry : groups) {
      const int material = std::get<0>(entry.first);
      const Group &group = entry.second;
      if (group.triangles == 0 || material < 0 || size_t(material) >= materials_.size()) {
        continue;
      }
      Mesh *mesh = scene_->create_node<Mesh>();
      array<Node *> used_shaders;
      used_shaders.push_back_slow(materials_[material]);
      mesh->set_used_shaders(used_shaders);
      const size_t vertices = group.triangles * 3;
      mesh->resize_mesh(vertices, group.triangles);
      packed_float3 *positions = mesh->get_position_for_write();
      int *indices = mesh->get_triangles().data();
      packed_normal *normals = mesh->attributes.add(ATTR_STD_VERTEX_NORMAL)->data_for_write<packed_normal>();
      float2 *uvs = mesh->attributes.add(ATTR_STD_UV, ustring("UVMap"))->data_for_write<float2>();
      size_t v = 0;
      for (const Json *part : group.meshes) {
        size_t np, nn, nuv = 0;
        const float *p = floats((*part)["points"], np);
        const float *n = floats((*part)["normals"], nn);
        const float *uv = part->has("uvs") ? floats((*part)["uvs"], nuv) : nullptr;
        const size_t corners = np / 3;
        for (size_t c = 0; c < corners; c++, v++) {
          positions[v] = packed_float3(to_blender(p[c * 3], p[c * 3 + 1], p[c * 3 + 2]) * CM);
          normals[v] = packed_normal(safe_normalize(to_blender(n[c * 3], n[c * 3 + 1], n[c * 3 + 2])));
          uvs[v] = uv ? make_float2(uv[c * 2], uv[c * 2 + 1]) : zero_float2();
          indices[v] = int(v);
        }
      }
      std::fill(mesh->get_smooth().begin(), mesh->get_smooth().end(), true);
      std::fill(mesh->get_shader().begin(), mesh->get_shader().end(), 0);
      mesh->tag_triangles_modified();
      mesh->tag_shader_modified();
      mesh->tag_smooth_modified();
      Object *object = scene_->create_node<Object>();
      object->set_geometry(mesh);
      object->set_tfm(transform_identity());
      if (std::get<1>(entry.first)) {
        object->set_visibility(PATH_RAY_VISIBILITY_ALL & ~PATH_RAY_VISIBILITY_CAMERA);
      }
      triangles += int(group.triangles);
    }
  }

  /* Transform whose local Z axis is `z`, at `position` */
  static Transform frame_with_z(const float3 z, const float3 position)
  {
    const float3 up = fabsf(z.z) < 0.999f ? make_float3(0, 0, 1) : make_float3(0, 1, 0);
    const float3 x = safe_normalize(cross(up, z));
    const float3 y = cross(z, x);
    return make_transform(x.x, y.x, z.x, position.x, x.y, y.y, z.y, position.y, x.z, y.z, z.z, position.z);
  }

  void add_light_object(Light *light, const Transform &tfm)
  {
    if (light_shader_ == nullptr) {
      /* Like Blender's light node tree: white emission of strength 1 (Cycles' default_light has strength 0) */
      light_shader_ = emission_shader(one_float3());
    }
    array<Node *> used_shaders;
    used_shaders.push_back_slow(light_shader_);
    light->set_used_shaders(used_shaders);
    Object *object = scene_->create_node<Object>();
    object->set_geometry(light);
    object->set_tfm(tfm);
    lights++;
  }

  /* Point lights, as bright as SH3D's SunFlow renderer makes them: light sources of power p and color c emit
   * 1283.5 W * p^2 * c, ceiling lights a sphere of 20 cm with a radiance of power * 255 / 208 * c */
  void build_lights()
  {
    for (const Json &light : data_["lights"].array) {
      const std::string type = light["type"].str();
      const float3 position = color3(light["position"], zero_float3());
      const float radius = float(light["radius"].num(3.25));
      const float3 color = color3(light["color"], one_float3()) * light_color_;
      const float power = float(light["power"].num(0.5));
      float3 strength;
      if (type == "point") {
        strength = color * (1283.5f * power * power);
      }
      else if (type == "ceiling") {
        const float r = radius * CM;
        strength = color * (4 * M_PI_F * M_PI_F * r * r * power * 255.0f / 208.0f);
      }
      else {
        continue;
      }
      if (reduce_max(strength) <= 0) {
        continue;
      }
      PointLight *point = scene_->create_node<PointLight>();
      point->set_strength(strength);
      point->set_radius(radius * CM);
      point->set_is_sphere(false); /* Blender's default soft falloff */
      add_light_object(point, transform_translate(to_blender(position.x, position.y, position.z) * CM));
    }
  }

  /* Strength of the Preetham sky that makes it as bright as YafaRay's sun sky of power 1.5 */
  static constexpr float SKY_STRENGTH = 1.0f;

  void build_world()
  {
    const Json &environment = data_["environment"];
    const bool day = environment["sun"].type == Json::ARRAY;
    const float3 sun = day ? safe_normalize(color3(environment["sun"], zero_float3())) : zero_float3();
    const float3 sun_direction = to_blender(sun.x, sun.y, sun.z);
    const std::string sky_texture = environment["skyTexture"].str();

    unique_ptr<ShaderGraph> graph = make_unique<ShaderGraph>();
    ShaderGraph *g = graph.get();
    BackgroundNode *background = g->create_node<BackgroundNode>();
    g->connect(background->output("Background"), g->output()->input("Surface"));

    if (!sky_texture.empty()) {
      /* Sky texture of a virtual visit, with the sun of SH3D's renderers */
      EnvironmentTextureNode *env = g->create_node<EnvironmentTextureNode>();
      env->set_filename(ustring(texture_path(sky_texture)));
      env->set_colorspace(u_colorspace_auto);
      g->connect(env->output("Color"), background->input("Color"));
      background->set_strength(1.0f);
      const float3 irradiance = color3(environment["sunIrradiance"], zero_float3());
      if (day && reduce_max(irradiance) > 0) {
        SunLight *light = scene_->create_node<SunLight>();
        light->set_strength(irradiance);
        light->set_angle(2 * atanf(0.01f));
        add_light_object(light, frame_with_z(sun_direction, zero_float3()));
      }
    }
    else if (day) {
      /* As SunFlow and YafaRay: a Preetham sky of turbidity 6, and a sun of their irradiance and size (a sphere of
       * 10000 cm at 1000000 cm) */
      SkyTextureNode *sky = g->create_node<SkyTextureNode>();
      sky->set_sky_type(NODE_SKY_PREETHAM);
      sky->set_turbidity(6.0f);
      sky->set_sun_direction(sun_direction);
      ShaderOutput *color = sky->output("Color");
      if (options_.sky_saturation != 1) {
        HSVNode *saturation = g->create_node<HSVNode>();
        saturation->set_saturation(options_.sky_saturation);
        g->connect(color, saturation->input("Color"));
        color = saturation->output("Color");
      }
      g->connect(color, background->input("Color"));
      background->set_strength(SKY_STRENGTH * (options_.sky_strength == "auto" ? 1.0f : std::stof(options_.sky_strength)));

      const float3 irradiance = color3(environment["sunIrradiance"], zero_float3());
      if (reduce_max(irradiance) > 0) {
        SunLight *light = scene_->create_node<SunLight>();
        light->set_strength(irradiance);
        light->set_angle(2 * atanf(0.01f));
        add_light_object(light, frame_with_z(sun_direction, zero_float3()));
      }
      printf("Sky: sun elevation %.1f, irradiance %.3f %.3f %.3f\n", asinf(sun_direction.z) * 180 / M_PI_F,
             irradiance.x, irradiance.y, irradiance.z);
    }
    else {
      background->set_color(make_float3(0.02f, 0.03f, 0.06f));
      background->set_strength(options_.night_sky);
    }

    Shader *shader = scene_->default_background;
    shader->set_graph(std::move(graph));
    shader->tag_update(scene_);

    /* Importance sampled world light, as Blender adds for its world */
    BackgroundLight *light = scene_->create_node<BackgroundLight>();
    array<Node *> used_shaders;
    used_shaders.push_back_slow(scene_->default_background);
    light->set_used_shaders(used_shaders);
    light->set_map_resolution(0);
    light->set_use_mis(true);
    Object *object = scene_->create_node<Object>();
    object->set_geometry(light);
    object->set_tfm(transform_identity());
  }

};

static void setup_camera(Scene *scene, const Json &camera, const int width, const int height)
{
  Camera *cam = scene->camera;
  const std::string lens = camera["lens"].str("PINHOLE");
  const float yaw = float(camera["yaw"].num());
  const float pitch = lens == "SPHERICAL" ? 0.0f : float(camera["pitch"].num());
  const float3 forward = make_float3(-sinf(yaw) * cosf(pitch), -cosf(yaw) * cosf(pitch), -sinf(pitch));
  const float3 position = make_float3(camera["x"].num(), -camera["y"].num(), camera["z"].num()) * CM;
  /* Cycles cameras look along +Z, with +Y up */
  const float3 right = safe_normalize(cross(forward, make_float3(0, 0, 1)));
  const float3 up = cross(right, forward);
  cam->set_matrix(make_transform(right.x, up.x, forward.x, position.x, right.y, up.y, forward.y, position.y,
                                 right.z, up.z, forward.z, position.z));
  cam->set_full_width(width);
  cam->set_full_height(height);
  cam->set_nearclip(0.05f);
  cam->set_farclip(1e5f);
  const float horizontal_fov = float(camera["fieldOfView"].num(1.1));
  const float aspect = float(width) / height;
  cam->set_camera_type(CAMERA_PERSPECTIVE);
  /* The auto viewplane spans [-aspect, aspect] x [-1, 1] in landscape, so the fov is the vertical one */
  cam->set_fov(aspect >= 1 ? 2 * atanf(tanf(horizontal_fov / 2) / aspect) : horizontal_fov);
  if (lens == "NORMAL") {
    cam->set_focaldistance(float(camera["focusDistance"].num(250)) * CM);
    cam->set_aperturesize(float(camera["lensRadius"].num(1)) * CM);
  }
  else if (lens == "FISHEYE" || lens == "SPHERICAL") {
    fprintf(stderr, "Lens %s isn't supported yet, using a perspective camera\n", lens.c_str());
  }
  cam->compute_auto_viewplane();
  cam->need_flags_update = true;
  cam->need_device_update = true;
}

/* ------------------------------------------------------------------------------------------------ output */

class BufferOutputDriver : public OutputDriver {
 public:
  std::mutex mutex;
  std::vector<float> pixels; /* RGBA, bottom-up */
  int width = 0, height = 0;
  std::function<void()> on_update;

  void write_render_tile(const Tile &tile) override
  {
    read(tile);
  }

  bool update_render_tile(const Tile &tile) override
  {
    if (!on_update) {
      return false;
    }
    read(tile);
    on_update();
    return true;
  }

 private:
  void read(const Tile &tile)
  {
    if (!(tile.size == tile.full_size)) {
      return;
    }
    const std::lock_guard<std::mutex> lock(mutex);
    width = tile.size.x;
    height = tile.size.y;
    pixels.resize(size_t(width) * height * 4);
    tile.get_pass_pixels("combined", 4, pixels.data());
  }
};

/* Tone maps linear RGBA (bottom-up) with OCIO and writes an 8-bit sRGB PNG (top-down) */
class ToneMapper {
 public:
  ToneMapper(const Options &options) : highlight_compression_(options.highlight_compression)
  {
    OCIO::ConstConfigRcPtr config = OCIO::Config::CreateFromFile(options.ocio.c_str());
    OCIO::DisplayViewTransformRcPtr transform = OCIO::DisplayViewTransform::Create();
    transform->setSrc(OCIO::ROLE_SCENE_LINEAR);
    transform->setDisplay("sRGB");
    transform->setView(options.view.c_str());
    OCIO::LegacyViewingPipelineRcPtr pipeline = OCIO::LegacyViewingPipeline::Create();
    pipeline->setDisplayViewTransform(transform);
    if (!options.look.empty() && options.look != "None") {
      /* As Blender, which shows the looks of a view without its prefix, e.g. "Punchy" for "AgX - Punchy" */
      const std::string view_look = options.view + " - " + options.look;
      pipeline->setLooksOverrideEnabled(true);
      pipeline->setLooksOverride(config->getLook(view_look.c_str()) ? view_look.c_str() : options.look.c_str());
    }
    processor_ = pipeline->getProcessor(config)->getDefaultCPUProcessor();
  }

  void write(std::vector<float> pixels, const int width, const int height, const float exposure,
             const std::string &path) const
  {
    const float scale = exp2f(exposure);
    for (size_t i = 0; i < pixels.size(); i++) {
      if (i % 4 != 3) {
        pixels[i] *= scale;
      }
    }
    compress_highlights(pixels, width, height);
    OCIO::PackedImageDesc image(pixels.data(), width, height, 4);
    processor_->apply(image);
    std::vector<unsigned char> rgb(size_t(width) * height * 3);
    for (int y = 0; y < height; y++) {
      const float *row = pixels.data() + size_t(height - 1 - y) * width * 4;
      for (int x = 0; x < width; x++) {
        for (int c = 0; c < 3; c++) {
          const float v = std::min(1.0f, std::max(0.0f, row[x * 4 + c]));
          rgb[(size_t(y) * width + x) * 3 + c] = (unsigned char)(v * 255.0f + 0.5f);
        }
      }
    }
    const std::string temporary = path + ".tmp.png";
    unique_ptr<OIIO::ImageOutput> out = OIIO::ImageOutput::create(temporary);
    if (!out || !out->open(temporary, OIIO::ImageSpec(width, height, 3, OIIO::TypeDesc::UINT8))) {
      throw std::runtime_error("Can't write " + path);
    }
    out->write_image(OIIO::TypeDesc::UINT8, rgb.data());
    out->close();
    std::remove(path.c_str()); /* rename() doesn't replace existing files on Windows */
    std::rename(temporary.c_str(), path.c_str());
  }

 private:
  /* Local highlight compression, as a photo editor's highlights slider: areas brighter than 1 stop above middle grey
   * (a sunlit view through a window, a room lit by its lamps next to darker ones...) lose part of their excess, up to
   * 5 stops, and keep their details. The areas are the log luminance smoothed by a guided filter, which keeps their
   * edges sharp, so that they don't get halos. */
  void compress_highlights(std::vector<float> &pixels, const int width, const int height) const
  {
    if (highlight_compression_ <= 0 || width < 2 || height < 2) {
      return;
    }
    static const float START = 1.0f + -2.47f /* log2(0.18) */, MAX_EV = 5.0f, EPS = 0.25f;
    const size_t n = size_t(width) * height;
    std::vector<float> l(n), ll(n);
    for (size_t i = 0; i < n; i++) {
      const float *p = &pixels[i * 4];
      l[i] = log2f(std::max(1e-6f, 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]));
      ll[i] = l[i] * l[i];
    }
    const int radius = std::max(1, std::max(width, height) / 24);
    std::vector<float> mean = box_filter(l, width, height, radius), mean_sq = box_filter(ll, width, height, radius);
    std::vector<float> &a = ll, b(n);
    for (size_t i = 0; i < n; i++) {
      const float variance = std::max(0.0f, mean_sq[i] - mean[i] * mean[i]);
      a[i] = variance / (variance + EPS);
      b[i] = mean[i] * (1 - a[i]);
    }
    const std::vector<float> mean_a = box_filter(a, width, height, radius), mean_b = box_filter(b, width, height, radius);
    for (size_t i = 0; i < n; i++) {
      const float base = mean_a[i] * l[i] + mean_b[i];
      const float reduction = std::min(MAX_EV, std::max(0.0f, base - START) * highlight_compression_);
      if (reduction > 0) {
        const float factor = exp2f(-reduction);
        for (int c = 0; c < 3; c++) {
          pixels[i * 4 + c] *= factor;
        }
      }
    }
  }

  /* Mean over a (2 radius + 1)^2 square, clamped to the image */
  static std::vector<float> box_filter(const std::vector<float> &in, const int width, const int height, const int radius)
  {
    std::vector<float> rows(in.size()), out(in.size());
    for (int y = 0; y < height; y++) {
      const float *src = &in[size_t(y) * width];
      double sum = 0;
      int count = 0;
      for (int x = 0; x < std::min(radius, width); x++, count++) {
        sum += src[x];
      }
      for (int x = 0; x < width; x++) {
        if (x + radius < width) {
          sum += src[x + radius];
          count++;
        }
        if (x - radius - 1 >= 0) {
          sum -= src[x - radius - 1];
          count--;
        }
        rows[size_t(y) * width + x] = float(sum / count);
      }
    }
    for (int x = 0; x < width; x++) {
      double sum = 0;
      int count = 0;
      for (int y = 0; y < std::min(radius, height); y++, count++) {
        sum += rows[size_t(y) * width + x];
      }
      for (int y = 0; y < height; y++) {
        if (y + radius < height) {
          sum += rows[size_t(y + radius) * width + x];
          count++;
        }
        if (y - radius - 1 >= 0) {
          sum -= rows[size_t(y - radius - 1) * width + x];
          count--;
        }
        out[size_t(y) * width + x] = float(sum / count);
      }
    }
    return out;
  }

  OCIO::ConstCPUProcessorRcPtr processor_;
  float highlight_compression_;
};

/* Writes progressive previews on its own thread, so that rendering doesn't wait for tone mapping and PNG encoding.
 * Only the latest frame is kept when previews come faster than they're written. */
class PreviewWriter {
 public:
  PreviewWriter(const ToneMapper &tone_mapper, const std::string &path) : tone_mapper_(tone_mapper), path_(path)
  {
    thread_ = std::thread([this] { run(); });
  }

  ~PreviewWriter()
  {
    stop();
  }

  void submit(const std::vector<float> &pixels, const int width, const int height, const float exposure)
  {
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      pending_ = pixels;
      width_ = width;
      height_ = height;
      exposure_ = exposure;
      has_pending_ = true;
    }
    condition_.notify_one();
  }

  /* Waits for the preview being written, drops any pending one */
  void stop()
  {
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      stopping_ = true;
      has_pending_ = false;
    }
    condition_.notify_one();
    if (thread_.joinable()) {
      thread_.join();
    }
  }

 private:
  void run()
  {
    while (true) {
      std::vector<float> pixels;
      int width, height;
      float exposure;
      {
        std::unique_lock<std::mutex> lock(mutex_);
        condition_.wait(lock, [this] { return has_pending_ || stopping_; });
        if (stopping_) {
          return;
        }
        pixels.swap(pending_);
        width = width_;
        height = height_;
        exposure = exposure_;
        has_pending_ = false;
      }
      tone_mapper_.write(std::move(pixels), width, height, exposure, path_);
      printf("PREVIEW %s\n", path_.c_str());
      fflush(stdout);
    }
  }

  const ToneMapper &tone_mapper_;
  const std::string path_;
  std::thread thread_;
  std::mutex mutex_;
  std::condition_variable condition_;
  std::vector<float> pending_;
  int width_ = 0, height_ = 0;
  float exposure_ = 0;
  bool has_pending_ = false, stopping_ = false;
};

/* Log average luminance -> exposure bringing it to KEY, ignoring pixels darker than 1% of the 99th percentile */
static float metering_exposure(const std::vector<float> &pixels, float &average)
{
  static const float KEY = 0.11f;
  std::vector<float> luminance;
  luminance.reserve(pixels.size() / 4);
  for (size_t i = 0; i + 3 < pixels.size(); i += 4) {
    luminance.push_back(0.2126f * pixels[i] + 0.7152f * pixels[i + 1] + 0.0722f * pixels[i + 2]);
  }
  std::vector<float> sorted = luminance;
  std::sort(sorted.begin(), sorted.end());
  const float threshold = 0.01f * sorted[size_t(0.99 * (sorted.size() - 1))];
  double sum = 0;
  size_t count = 0;
  for (const float l : luminance) {
    if (l > threshold) {
      sum += log(double(l) + 1e-6);
      count++;
    }
  }
  average = count ? float(exp(sum / count)) : 0.18f;
  return std::max(-10.0f, std::min(10.0f, log2f(KEY / std::max(average, 1e-6f))));
}

/* ------------------------------------------------------------------------------------------------ main */

/*
 * Reports the progress of a device's GPU kernels compilation (e.g. "12 / 78 render kernels loaded") until they're
 * ready. Cycles compiles Metal kernels in the background, and only reports their progress once the scene is updated,
 * while updating it waits for some of them without saying so (to evaluate the sky's shader for the importance map of
 * the world light, until about 70 of 78 kernels are loaded). Other devices are ready at once.
 */
class KernelMonitor {
 public:
  KernelMonitor(const Device *device, std::function<void(const std::string &)> report)
      : thread_([this, device, report]() { run(device, report); })
  {
  }

  ~KernelMonitor()
  {
    stop();
  }

  void stop()
  {
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      stopped_ = true;
    }
    condition_.notify_all();
    if (thread_.joinable()) {
      thread_.join();
    }
  }

 private:
  void run(const Device *device, const std::function<void(const std::string &)> &report)
  {
    std::unique_lock<std::mutex> lock(mutex_);
    while (!stopped_) {
      std::string status;
      if (device->is_ready(status)) {
        return;
      }
      report(status);
      condition_.wait_for(lock, std::chrono::milliseconds(500));
    }
  }

  std::mutex mutex_;
  std::condition_variable condition_;
  bool stopped_ = false;
  std::thread thread_; /* Last, so it starts once the members it uses are constructed */
};

/* Renders the scene, throwing on errors */
static void render_scene(const Options &options, const double t0)
{
  {
    const Json data = Json::parse(read_file(path_join(options.export_dir, "scene.json")));
    const Json camera = Json::parse(read_file(path_join(options.export_dir, "camera.json")));
    const std::string blob = read_file(path_join(options.export_dir, "meshes.bin"));
    const int width = options.width ? options.width : int(camera["width"].num(1024));
    const int height = options.height ? options.height : int(camera["height"].num(576));

    SessionParams session_params;
    std::string device_name;
    session_params.device = select_device(options.device, options.threads, device_name);
    session_params.denoise_device = Device::available_devices(DEVICE_MASK_CPU).front();
    session_params.background = true;
    /* Not headless when previews are wanted: Cycles skips progressive output driver updates in headless mode */
    session_params.headless = !options.progress;
    session_params.threads = options.threads;
    session_params.samples = options.samples;
    session_params.use_auto_tile = false;
    printf("Cycles device: %s\n", device_name.c_str());
    fflush(stdout);

    SceneParams scene_params;
    scene_params.shadingsystem = SHADINGSYSTEM_SVM;
    scene_params.use_texture_cache = false; /* Load full images, no tiled .tx cache */
    unique_ptr<Session> session = make_unique<Session>(session_params, scene_params);
    Scene *scene = session->scene.get();
    unique_ptr<BufferOutputDriver> driver_owner = make_unique<BufferOutputDriver>();
    BufferOutputDriver *driver = driver_owner.get();
    session->set_output_driver(std::move(driver_owner));

    SceneBuilder builder(scene, data, blob, options);
    builder.build();
    setup_camera(scene, camera, width, height);

    Integrator *integrator = scene->integrator;
    integrator->set_use_adaptive_sampling(true);
    integrator->set_adaptive_threshold(options.noise_threshold);
    integrator->set_max_bounce(12);
    integrator->set_max_diffuse_bounce(6);
    integrator->set_max_glossy_bounce(6);
    integrator->set_max_transmission_bounce(12);
    integrator->set_transparent_max_bounce(32);
    integrator->set_sample_clamp_indirect(10.0f);
    integrator->set_caustics_reflective(false);
    integrator->set_caustics_refractive(false);
    integrator->set_filter_glossy(1.0f);
    integrator->set_denoiser_type(DENOISER_OPENIMAGEDENOISE);
    integrator->set_denoiser_passes(DENOISER_PASS_ALBEDO | DENOISER_PASS_NORMAL);
    integrator->set_denoiser_prefilter(DENOISER_PREFILTER_ACCURATE);
    integrator->set_denoise_use_gpu(false);

    /* Noisy combined pass rendered by the kernel, and its denoised version read by the output driver (which falls
     * back to the noisy one when denoising is off) */
    Pass *noisy = scene->create_node<Pass>();
    noisy->set_name(ustring("combined_noisy"));
    noisy->set_type(PASS_COMBINED);
    noisy->set_mode(PassMode::NOISY);
    Pass *pass = scene->create_node<Pass>();
    pass->set_name(ustring("combined"));
    pass->set_type(PASS_COMBINED);
    pass->set_mode(PassMode::DENOISED);

    ToneMapper tone_mapper(options);
    const double t1 = now();
    printf("Scene built in %.1fs: %d triangles, %zu materials, %d lights\n", t1 - t0, builder.triangles,
           data["materials"].size(), builder.lights);
    fflush(stdout);

    auto render = [&](const int w, const int h, const int samples, const bool denoise) {
      scene->camera->set_full_width(w);
      scene->camera->set_full_height(h);
      scene->camera->compute_auto_viewplane();
      scene->camera->need_flags_update = true;
      scene->camera->need_device_update = true;
      integrator->set_use_denoise(denoise);
      session_params.samples = samples;
      BufferParams buffer_params;
      buffer_params.width = buffer_params.full_width = w;
      buffer_params.height = buffer_params.full_height = h;
      session->reset(session_params, buffer_params);
      session->start();
      session->wait();
      if (session->progress.get_error()) {
        throw std::runtime_error(session->progress.get_error_message());
      }
    };

    /* Cycles' status when it changes, e.g. while it loads or compiles GPU kernels before rendering, and the progress of
     * the final render */
    std::atomic<bool> final_render{false};
    std::string last_status;
    std::mutex status_mutex;
    auto print_status = [&](const std::string &status) {
      const std::lock_guard<std::mutex> lock(status_mutex);
      if (status != last_status) {
        last_status = status;
        printf("STATUS %s\n", status.c_str());
        fflush(stdout);
      }
    };
    session->progress.set_update_callback([&]() {
      std::string status, substatus;
      session->progress.get_status(status, substatus);
      print_status(status);
      if (final_render) {
        printf("PROGRESS %.3f %d\n", session->progress.get_progress(), session->progress.get_current_sample());
        fflush(stdout);
      }
    });
    KernelMonitor kernel_monitor(session->device.get(), print_status);

    /* Auto exposure from a quick, noisy, quarter resolution pass on the same session (scene stays loaded) */
    float exposure = options.exposure, average = 0;
    double metering = -1;
    if (options.auto_exposure) {
      /* Denoised too: turning the denoiser on only for the next render of the session doesn't take effect */
      render(std::max(1, width / 4), std::max(1, height / 4), 16, true);
      exposure += metering_exposure(driver->pixels, average);
      metering = now() - t1;
      printf("Auto exposure: average luminance %.4f, exposure %.2f EV (%.1fs)\n", average, exposure, metering);
      fflush(stdout);
    }
    /* Exposed by the film, so adaptive sampling measures the noise of the image as it's seen, whatever the scene's
     * brightness */
    scene->film->set_exposure(exp2f(exposure));

    unique_ptr<PreviewWriter> previews;
    if (options.progress) {
      previews = make_unique<PreviewWriter>(tone_mapper, options.output + ".preview.png");
      driver->on_update = [&]() {
        /* Called by the render thread, with the driver's pixels up to date */
        previews->submit(driver->pixels, driver->width, driver->height, 0);
      };
      final_render = true;
    }
    const double t2 = now();
    render(width, height, options.samples, true);
    const double t3 = now();
    driver->on_update = nullptr;
    if (previews) {
      previews->stop(); /* No preview after the final image */
    }
    tone_mapper.write(driver->pixels, driver->width, driver->height, 0, options.output);

    printf("RESULT {\"device\": \"%s\", \"build\": %.2f, \"metering\": %.2f, \"render\": %.2f, "
           "\"exposure\": %.2f, \"samples\": %d, \"triangles\": %d, \"engine\": \"cycles %s\", "
           "\"width\": %d, \"height\": %d}\n",
           device_name.c_str(), t1 - t0, metering, t3 - t2, exposure, options.samples, builder.triangles,
           CYCLES_VERSION_STRING, width, height);
    fflush(stdout);
    kernel_monitor.stop();
    session.reset();
  }
}

/* Devices Cycles can render with, one "DEVICE <type> <description>" line each */
static void list_devices()
{
  for (const DeviceInfo &device : Device::available_devices(DEVICE_MASK_ALL)) {
    printf("DEVICE %s %s\n", Device::string_from_type(device.type).c_str(), device.description.c_str());
  }
  fflush(stdout);
}

int main(int argc, const char **argv)
{
  const double t0 = now();
  log_init(nullptr);
  path_init();
  if (argc == 2 && std::string(argv[1]) == "--list-devices") {
    list_devices();
    return 0;
  }
  Options options = parse_options(argc, argv);
  /* Cycles' own color management (texture color spaces) only reads the config from $OCIO */
#ifdef _WIN32
  _putenv_s("OCIO", options.ocio.c_str());
#else
  setenv("OCIO", options.ocio.c_str(), 1);
#endif

  try {
    try {
      render_scene(options, t0);
    }
    catch (const std::exception &e) {
      if (options.device == "cpu") {
        throw;
      }
      /* E.g. no GPU kernels for this GPU, or no compiler to build them: rendering with the CPU still works */
      printf("GPU rendering failed (%s), rendering with the CPU\n", e.what());
      fflush(stdout);
      Options cpu = options;
      cpu.device = "cpu";
      render_scene(cpu, t0);
    }
  }
  catch (const std::exception &e) {
    fprintf(stderr, "Error: %s\n", e.what());
    return 1;
  }
  return 0;
}
