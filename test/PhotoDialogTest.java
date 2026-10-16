import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.undo.UndoableEditSupport;

import com.eteks.sweethome3d.io.DefaultUserPreferences;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.plugin.PluginManager;
import com.eteks.sweethome3d.swing.SwingViewFactory;
import com.eteks.sweethome3d.viewcontroller.PhotoController;

/*
 * Opens Sweet Home 3D's own Create photo dialog with the plugins of a folder loaded, selects Cycles in its renderer
 * combo box at the best quality, presses its Create button and saves screenshots of the dialog while rendering.
 *
 * Needs a display. Usage: PhotoDialogTest <plugins folder> <output directory> [<project.sh3d> [[<level>/]<stored point of view>]]
 */
public class PhotoDialogTest {
    private static final String CYCLES = "com.eteks.sweethome3d.j3d.CyclesRenderer";

    public static void main(String[] args) throws Exception {
        File output = new File(args[1]);
        output.mkdirs();
        final DefaultUserPreferences preferences = new DefaultUserPreferences();
        final Home home = args.length > 2 ? TestHome.load(args[2]) : TestHome.create(preferences);
        new PluginManager(new File(args[0])).getPlugins(TestHome.application(preferences, home), home, preferences, new UndoableEditSupport());
        TestHome.viewFromTop(home, preferences, args.length > 3 ? args[3] : null);

        final JButton [] create = new JButton [1];
        final String [] createText = new String [1];
        EventQueue.invokeAndWait(() -> {
            home.getCamera().setTime(TestHome.time(10, 0));

            // Stands in for the 3D view, only used for its aspect ratio and the OpenGL quality levels
            Fake3DView view3D = new Fake3DView();
            view3D.setSize(1024, 576);
            PhotoController controller = new PhotoController(home, preferences, view3D, new SwingViewFactory(), null);
            controller.setQuality(controller.getQualityLevelCount() - 1); // Best quality
            controller.setRenderer(CYCLES);
            controller.getView().displayView(null);

            JDialog dialog = findDialog();
            JComboBox<?> renderers = find(dialog, JComboBox.class).stream()
                .filter(c -> c.getItemCount() > 0 && c.getItemAt(0) instanceof String && ((String)c.getItemAt(0)).contains("Renderer"))
                .findFirst().get();
            List<String> items = new ArrayList<>();
            for (int i = 0; i < renderers.getItemCount(); i++) {
                items.add((String)renderers.getItemAt(i));
            }
            System.out.println("Renderer combo items: " + items + ", selected: " + renderers.getSelectedItem());
            create[0] = find(dialog, JButton.class).stream()
                .filter(b -> b.getText() != null && b.getText().toLowerCase().contains("create")).findFirst().get();
            createText[0] = create[0].getText();
            System.out.println("Pressing \"" + createText[0] + "\"");
            create[0].doClick();
        });

        long start = System.currentTimeMillis();
        boolean screenshot = false;
        while (true) {
            Thread.sleep(1000);
            final String [] text = new String [1];
            EventQueue.invokeAndWait(() -> text[0] = create[0].getText());
            double seconds = (System.currentTimeMillis() - start) / 1000.0;
            if (!screenshot && seconds > 25) {
                capture(new File(output, "dialog_rendering.png"));
                screenshot = true;
            }
            if (text[0].equals(createText[0])) {
                System.out.printf("Photo created in %.0fs%n", seconds);
                break;
            }
        }
        Thread.sleep(500);
        capture(new File(output, "dialog_done.png"));
        System.exit(0);
    }

    private static class Fake3DView extends javax.swing.JPanel implements com.eteks.sweethome3d.viewcontroller.View {
    }

    private static JDialog findDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog && window.isShowing()) {
                return (JDialog)window;
            }
        }
        throw new IllegalStateException("No dialog");
    }

    private static void capture(File file) throws Exception {
        EventQueue.invokeAndWait(() -> {
            try {
                JDialog dialog = findDialog();
                BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
                dialog.getRootPane().printAll(image.getGraphics());
                ImageIO.write(image, "png", file);
                System.out.println("Saved " + file);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> List<T> find(Container container, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (type.isInstance(component)) {
                result.add((T)component);
            }
            if (component instanceof Container) {
                result.addAll(find((Container)component, type));
            }
        }
        return result;
    }
}
