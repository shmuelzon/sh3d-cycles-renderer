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
import javax.swing.JLabel;
import javax.swing.undo.UndoableEditSupport;

import com.eteks.sweethome3d.io.DefaultUserPreferences;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.eteks.sweethome3d.plugin.PluginManager;

/*
 * Opens the plugin's settings panel (its Tools menu action), selects the AgX view with the Punchy look, renders a
 * preview, saves screenshots, presses OK and checks that the settings are saved in the project.
 *
 * Needs a display. Usage: SettingsPanelTest <plugins folder> <output directory> [<project.sh3d> [[<level>/]<stored point of view> [<HH:MM>]]]
 */
public class SettingsPanelTest {
    private static final String PROPERTY_PREFIX = "com.eteks.sweethome3d.j3d.CyclesRenderer.";

    public static void main(String[] args) throws Exception {
        File output = new File(args[1]);
        output.mkdirs();
        final DefaultUserPreferences preferences = new DefaultUserPreferences();
        final Home home = args.length > 2 ? TestHome.load(args[2]) : TestHome.create(preferences);
        List<Plugin> plugins = new PluginManager(new File(args[0])).getPlugins(TestHome.application(preferences, home),
            home, preferences, new UndoableEditSupport());
        TestHome.viewFromTop(home, preferences, args.length > 3 ? args[3] : null);
        String [] time = (args.length > 4 ? args[4] : "10:00").split(":");
        home.getCamera().setTime(TestHome.time(Integer.parseInt(time[0]), Integer.parseInt(time[1])));
        home.setModified(false);

        Plugin plugin = plugins.stream().filter(p -> p.getClass().getName().equals("com.shmuelzon.CyclesRenderer.Plugin"))
            .findFirst().orElseThrow(() -> new IllegalStateException("Plugin not loaded"));
        for (PluginAction action : plugin.getActions()) {
            System.out.println("Action: " + action.getPropertyValue(PluginAction.Property.MENU) + " > "
                + action.getPropertyValue(PluginAction.Property.NAME) + (action.isEnabled() ? "" : " (disabled)"));
        }
        final Object panel = plugin.getClass().getClassLoader().loadClass("com.shmuelzon.CyclesRenderer.SettingsPanel")
            .getConstructor(Home.class, ClassLoader.class).newInstance(home, plugin.getClass().getClassLoader());
        EventQueue.invokeLater(() -> {
            try {
                panel.getClass().getMethod("displayView", Component.class).invoke(panel, (Component)null);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        final JLabel [] status = new JLabel [1];
        Thread.sleep(2000);
        EventQueue.invokeAndWait(() -> {
            JDialog dialog = findDialog();
            List<JComboBox> combos = find(dialog, JComboBox.class);
            combos.get(0).setSelectedItem("AgX");  // View
            combos.get(1).setSelectedItem("Punchy");  // Look
            List<JLabel> labels = find(dialog, JLabel.class);
            status[0] = labels.get(labels.size() - 1);
            button(dialog, "Preview").doClick();
        });
        capture(new File(output, "settings_rendering.png"));
        long start = System.currentTimeMillis();
        while (true) {
            Thread.sleep(1000);
            final String [] text = new String [1];
            EventQueue.invokeAndWait(() -> text[0] = status[0].getText());
            if (!text[0].startsWith("Rendering")) {
                System.out.printf("Preview: %s (%.0fs)%n", text[0], (System.currentTimeMillis() - start) / 1000.0);
                break;
            }
        }
        Thread.sleep(500);
        capture(new File(output, "settings_done.png"));
        EventQueue.invokeAndWait(() -> button(findDialog(), "OK").doClick());
        Thread.sleep(500);
        String view = home.getProperty(PROPERTY_PREFIX + "view");
        String look = home.getProperty(PROPERTY_PREFIX + "look");
        System.out.println("Saved settings: view " + view + ", look " + look + ", modified " + home.isModified());
        System.out.println("AgX".equals(view) && "Punchy".equals(look) && home.isModified() ? "PASS" : "FAIL");
        System.exit(0);
    }

    private static JButton button(Container container, String text) {
        return find(container, JButton.class).stream().filter(b -> text.equals(b.getText())).findFirst()
            .orElseThrow(() -> new IllegalStateException("No " + text + " button"));
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
