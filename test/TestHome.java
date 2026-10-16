import java.awt.EventQueue;
import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import javax.swing.undo.UndoableEditSupport;

import com.eteks.sweethome3d.io.ContentRecording;
import com.eteks.sweethome3d.io.DefaultHomeInputStream;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.FurnitureCategory;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeApplication;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeRecorder;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.viewcontroller.HomeController3D;

/* Homes and cameras for the tests: a project file, or a small room furnished from SH3D's default catalogs */
public class TestHome {
    static final float WIDTH = 600, DEPTH = 400, HEIGHT = 250;

    static Home load(String project) throws Exception {
        DefaultHomeInputStream in = new DefaultHomeInputStream(new FileInputStream(new File(project)), ContentRecording.INCLUDE_ALL_CONTENT, null, null, false);
        try {
            return in.readHome();
        } finally {
            in.close();
        }
    }

    /* A walled room with a textured floor, a lamp at its center and a piece of furniture */
    static Home create(UserPreferences preferences) {
        Home home = new Home();
        float [][] corners = {{0, 0}, {WIDTH, 0}, {WIDTH, DEPTH}, {0, DEPTH}};
        Wall previous = null, first = null;
        for (int i = 0; i < corners.length; i++) {
            float [] start = corners[i], end = corners[(i + 1) % corners.length];
            Wall wall = new Wall(start[0], start[1], end[0], end[1], 10, HEIGHT);
            home.addWall(wall);
            if (previous != null) {
                previous.setWallAtEnd(wall);
                wall.setWallAtStart(previous);
            } else {
                first = wall;
            }
            previous = wall;
        }
        previous.setWallAtEnd(first);
        first.setWallAtStart(previous);

        Room room = new Room(corners);
        CatalogTexture texture = preferences.getTexturesCatalog().getCategories().get(0).getTexture(0);
        room.setFloorTexture(new HomeTexture(texture));
        home.addRoom(room);

        // A ceiling lamp and a sofa if the catalog has some, otherwise any light and any medium sized piece
        CatalogLight light = null;
        CatalogPieceOfFurniture piece = null;
        for (FurnitureCategory category : preferences.getFurnitureCatalog().getCategories()) {
            for (CatalogPieceOfFurniture catalogPiece : category.getFurniture()) {
                String name = catalogPiece.getName().toLowerCase();
                if (catalogPiece instanceof CatalogLight && catalogPiece.getHeight() < 100
                    && (light == null || name.contains("ceiling") && !light.getName().toLowerCase().contains("ceiling"))) {
                    light = (CatalogLight)catalogPiece;
                } else if (!(catalogPiece instanceof CatalogLight) && !catalogPiece.isDoorOrWindow()
                    && catalogPiece.getWidth() > 80 && catalogPiece.getWidth() < 250
                    && (piece == null || name.contains("sofa") && !piece.getName().toLowerCase().contains("sofa"))) {
                    piece = catalogPiece;
                }
            }
        }
        HomeLight homeLight = new HomeLight(light);
        homeLight.setX(WIDTH / 2);
        homeLight.setY(DEPTH / 2);
        homeLight.setElevation(HEIGHT - homeLight.getHeight() - 10);
        homeLight.setPower(0.5f);
        home.addPieceOfFurniture(homeLight);
        HomePieceOfFurniture homePiece = new HomePieceOfFurniture(piece);
        homePiece.setX(WIDTH / 3);
        homePiece.setY(DEPTH / 2);
        home.addPieceOfFurniture(homePiece);
        System.out.println("Test home: " + light.getName() + ", " + piece.getName() + ", floor " + texture.getName());
        return home;
    }

    static HomeApplication application(final UserPreferences preferences, Home home) {
        HomeApplication application = new HomeApplication() {
            public HomeRecorder getHomeRecorder() {
                return null;
            }

            public UserPreferences getUserPreferences() {
                return preferences;
            }
        };
        application.addHome(home);
        return application;
    }

    /*
     * A stored point of view of a project, as "<name>" or "<level>/<name>" to select its level first (as SH3D does
     * when a level is selected), or an aerial view of the whole test room from its south side
     */
    static void viewFromTop(final Home home, final UserPreferences preferences, final String pointOfView) throws Exception {
        EventQueue.invokeAndWait(() -> {
            HomeController3D controller3D = new HomeController3D(home, preferences, null, null, new UndoableEditSupport());
            controller3D.viewFromTop();
            if (pointOfView != null) {
                String name = pointOfView;
                int separator = pointOfView.indexOf('/');
                if (separator >= 0) {
                    String levelName = pointOfView.substring(0, separator);
                    name = pointOfView.substring(separator + 1);
                    home.setSelectedLevel(home.getLevels().stream().filter(l -> l.getName().equals(levelName)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No level " + levelName)));
                }
                final String cameraName = name;
                controller3D.goToCamera(home.getStoredCameras().stream().filter(c -> cameraName.equals(c.getName())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No point of view " + cameraName)));
            } else {
                Camera camera = home.getTopCamera();
                camera.setX(WIDTH / 2);
                camera.setY(DEPTH + 500);
                camera.setZ(900);
                camera.setYaw((float)Math.PI);
                camera.setPitch(0.9f);
            }
        });
    }

    /* Virtual visitor in a corner of the test room, looking at its center */
    static void viewFromObserver(final Home home, final UserPreferences preferences) throws Exception {
        EventQueue.invokeAndWait(() -> {
            new HomeController3D(home, preferences, null, null, new UndoableEditSupport()).viewFromObserver();
            Camera camera = home.getObserverCamera();
            camera.setX(WIDTH * 0.9f);
            camera.setY(DEPTH * 0.9f);
            camera.setZ(160);
            camera.setYaw((float)Math.atan2(WIDTH * 0.4, -DEPTH * 0.4));
            camera.setPitch(0.25f);
        });
    }

    /* SH3D stores times with the local time fields expressed in UTC */
    static long time(int hours, int minutes) {
        return LocalDateTime.of(2025, 5, 1, hours, minutes).toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
