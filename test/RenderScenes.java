import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.undo.UndoableEditSupport;

import com.eteks.sweethome3d.io.DefaultUserPreferences;
import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.plugin.PluginManager;

/*
 * Renders scenes of a project with a renderer at its best quality, e.g. to compare renderers.
 *
 * Usage: RenderScenes <plugins folder> <output directory> <project.sh3d> <width> <height> <renderer class> <scene>...
 *   scene: <name>:<level>/<point of view>:<HH>:<MM>:<on|off|light name>, with "_" for spaces, e.g.
 *          bedroom_night:2nd_floor/HA_Bedroom:20:00:on (lights at their power, all turned off, or only the lights of
 *          that name at their power, as the floor plan plugin renders each light)
 */
public class RenderScenes {
    public static void main(String[] args) throws Exception {
        File output = new File(args[1]);
        output.mkdirs();
        DefaultUserPreferences preferences = new DefaultUserPreferences();
        Home home = TestHome.load(args[2]);
        int width = Integer.parseInt(args[3]);
        int height = Integer.parseInt(args[4]);
        String rendererClass = args[5];
        new PluginManager(new File(args[0])).getPlugins(TestHome.application(preferences, home), home, preferences, new UndoableEditSupport());

        List<HomeLight> lights = new ArrayList<>();
        List<Float> powers = new ArrayList<>();
        for (HomePieceOfFurniture piece : home.getFurniture()) {
            for (HomePieceOfFurniture child : piece instanceof HomeFurnitureGroup
                    ? ((HomeFurnitureGroup)piece).getAllFurniture() : java.util.Collections.singletonList(piece)) {
                if (child instanceof HomeLight) {
                    lights.add((HomeLight)child);
                    powers.add(((HomeLight)child).getPower());
                }
            }
        }

        for (int i = 6; i < args.length; i++) {
            String [] parts = args[i].split(":");
            String lightName = parts[4];
            parts = args[i].replace('_', ' ').split(":");
            String name = parts[0].replace(' ', '_');
            TestHome.viewFromTop(home, preferences, parts[1]);
            Camera camera = home.getCamera().clone();
            camera.setTime(TestHome.time(Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            for (int l = 0; l < lights.size(); l++) {
                boolean on = lightName.equals("on") || lightName.equals(lights.get(l).getName());
                lights.get(l).setPower(on ? powers.get(l) : 0);
            }
            AbstractPhotoRenderer renderer = AbstractPhotoRenderer.createInstance(rendererClass, home, null, AbstractPhotoRenderer.Quality.HIGH);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            long start = System.currentTimeMillis();
            renderer.render(image, camera, null);
            renderer.dispose();
            System.out.printf("%s: %s in %.1fs%n", name, renderer.getName(), (System.currentTimeMillis() - start) / 1000.0);
            ImageIO.write(image, "png", new File(output, name + ".png"));
        }
        System.exit(0);
    }
}
