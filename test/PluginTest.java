import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.swing.undo.UndoableEditSupport;

import com.eteks.sweethome3d.io.DefaultUserPreferences;
import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.plugin.PluginManager;

/*
 * Loads the plugins of a folder with SH3D's PluginManager, as Sweet Home 3D does, then uses the Cycles renderer only
 * through SH3D's public renderer API, as SH3D's dialogs and other plugins do. Renders a test room (or a project) by
 * day and by night, and checks that stopping a render works.
 *
 * Usage: PluginTest <plugins folder> <output directory> [<project.sh3d> [[<level>/]<stored point of view>]]
 */
public class PluginTest {
    private static final String CYCLES = "com.eteks.sweethome3d.j3d.CyclesRenderer";
    private static int failures;

    public static void main(String[] args) throws Exception {
        File plugins = new File(args[0]);
        File output = new File(args[1]);
        output.mkdirs();
        DefaultUserPreferences preferences = new DefaultUserPreferences();
        Home home = args.length > 2 ? TestHome.load(args[2]) : TestHome.create(preferences);
        String pointOfView = args.length > 3 ? args[3] : null;

        List<String> before = AbstractPhotoRenderer.getAvailableRenderers();
        System.out.println("Renderers before loading the plugins: " + before);
        check("Cycles isn't available before loading the plugin", !before.contains(CYCLES));

        new PluginManager(plugins).getPlugins(TestHome.application(preferences, home), home, preferences, new UndoableEditSupport());
        List<String> after = AbstractPhotoRenderer.getAvailableRenderers();
        System.out.println("Renderers after loading the plugins: " + after);
        check("Cycles is listed by AbstractPhotoRenderer.getAvailableRenderers()", after.contains(CYCLES));

        AbstractPhotoRenderer renderer = AbstractPhotoRenderer.createInstance(CYCLES, home, null, AbstractPhotoRenderer.Quality.LOW);
        check("createInstance() returns the Cycles renderer, not the Sunflow fallback", renderer.getClass().getName().equals(CYCLES));
        check("It's defined in SH3D's class loader", renderer.getClass().getClassLoader() == AbstractPhotoRenderer.class.getClassLoader());
        renderer.dispose();
        if (failures > 0) {
            finish();
        }

        TestHome.viewFromTop(home, preferences, pointOfView);
        render(home, AbstractPhotoRenderer.Quality.LOW, TestHome.time(12, 0), 640, 360, new File(output, "aerial_day.png"));
        if (args.length > 2) {
            render(home, AbstractPhotoRenderer.Quality.HIGH, TestHome.time(20, 0), 1224, 744, new File(output, "aerial_night.png"));
        } else {
            TestHome.viewFromObserver(home, preferences);
            render(home, AbstractPhotoRenderer.Quality.LOW, TestHome.time(21, 0), 640, 360, new File(output, "observer_night.png"));
        }

        // Stop, as the photo dialog's Stop button does from the event dispatch thread
        final AbstractPhotoRenderer stopped = AbstractPhotoRenderer.createInstance(CYCLES, home, null, AbstractPhotoRenderer.Quality.HIGH);
        Camera camera = home.getCamera().clone();
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ex) {
            }
            stopped.stop();
        }).start();
        long start = System.currentTimeMillis();
        boolean interrupted = false;
        try {
            stopped.render(new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB), camera, null);
        } catch (InterruptedIOException ex) {
            interrupted = true;
        }
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        System.out.printf("Stopped render returned after %.1fs%n", seconds);
        check("stop() interrupts the render", interrupted && seconds < 15);
        stopped.dispose();
        finish();
    }

    private static void render(Home home, AbstractPhotoRenderer.Quality quality, long time, int width, int height, File file) throws Exception {
        Camera camera = home.getCamera().clone();
        camera.setTime(time);
        AbstractPhotoRenderer renderer = AbstractPhotoRenderer.createInstance(CYCLES, home, null, quality);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        AtomicInteger previews = new AtomicInteger(), finals = new AtomicInteger();
        ImageObserver observer = (img, flags, x, y, w, h) -> {
            if ((flags & ImageObserver.FRAMEBITS) != 0) {
                finals.incrementAndGet();
            } else if ((flags & ImageObserver.SOMEBITS) != 0) {
                previews.incrementAndGet();
            }
            return true;
        };
        long start = System.currentTimeMillis();
        try {
            renderer.render(image, camera, observer);
        } catch (Exception ex) {
            ex.printStackTrace();
            check("Rendering " + file.getName(), false);
            return;
        } finally {
            renderer.dispose();
        }
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        EventQueue.invokeAndWait(() -> { }); // Flush the observer notifications
        ImageIO.write(image, "png", file);

        double sum = 0, sumOfSquares = 0;
        int pixels = width * height;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                double luminance = 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
                sum += luminance;
                sumOfSquares += luminance * luminance;
            }
        }
        double mean = sum / pixels, deviation = Math.sqrt(sumOfSquares / pixels - mean * mean);
        System.out.printf("%s: %s %dx%d in %.1fs, %d previews, mean %.0f, deviation %.0f%n",
            file.getName(), quality, width, height, seconds, previews.get(), mean, deviation);
        check(file.getName() + ": the observer got the final image", finals.get() == 1);
        check(file.getName() + ": the image isn't blank", mean > 5 && deviation > 3);
    }

    private static void check(String description, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + description);
        if (!ok) {
            failures++;
        }
    }

    private static void finish() {
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
