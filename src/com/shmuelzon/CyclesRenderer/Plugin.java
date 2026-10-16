package com.shmuelzon.CyclesRenderer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.plugin.PluginAction;

/*
 * Adds the Cycles renderer to Sweet Home 3D.
 *
 * SH3D lists the renderers named in the com.eteks.sweethome3d.j3d.rendererClassNames system property and creates
 * them with Class.forName() from its own class loader, which can't see classes of plugins (they have their own
 * class loader). So, once per JVM, this plugin defines CyclesRenderer and CyclesSceneExporter (stored as
 * core/*.class.bin resources) directly in the class loader and package of AbstractPhotoRenderer, then
 * adds CyclesRenderer to that property. The renderer then behaves like the built-in ones: it's listed in the
 * Create photo / photos / video dialogs and any plugin can create it with AbstractPhotoRenderer.createInstance().
 */
public class Plugin extends com.eteks.sweethome3d.plugin.Plugin {
    static final String RENDERER_CLASS = "com.eteks.sweethome3d.j3d.CyclesRenderer";
    private static final String RENDERERS_PROPERTY = "com.eteks.sweethome3d.j3d.rendererClassNames";
    private static boolean installed;
    private static Throwable installError;

    @Override
    public void init() {
        install(getPluginClassLoader());
    }

    @Override
    public PluginAction[] getActions() {
        return new PluginAction [] {new SettingsAction()};
    }

    /* Tools > Cycles Renderer Settings..., the settings of the renderer for the project */
    public class SettingsAction extends PluginAction {
        public SettingsAction() {
            super("com.shmuelzon.CyclesRenderer.ApplicationPlugin", "CyclesRenderer.SettingsAction", getPluginClassLoader(),
                installError == null);
        }

        @Override
        public void execute() {
            new SettingsPanel(getHome(), getPluginClassLoader()).displayView((java.awt.Component)getHomeController().getView());
        }
    }

    /* Public so that tests and other launchers can install the renderer without SH3D's plugin manager */
    public static synchronized void install(ClassLoader pluginClassLoader) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            defineCoreClasses(pluginClassLoader);
            String executable = extractNatives(pluginClassLoader);
            if (executable != null && System.getProperty(RENDERER_CLASS + ".executable") == null) {
                System.setProperty(RENDERER_CLASS + ".executable", executable);
            }
            String renderers = System.getProperty(RENDERERS_PROPERTY,
                "com.eteks.sweethome3d.j3d.PhotoRenderer,com.eteks.sweethome3d.j3d.YafarayRenderer");
            if (!("," + renderers + ",").contains("," + RENDERER_CLASS + ",")) {
                System.setProperty(RENDERERS_PROPERTY, renderers + "," + RENDERER_CLASS);
            }
        } catch (Throwable ex) {
            installError = ex;
            ex.printStackTrace();
        }
    }

    public static Throwable getInstallError() {
        return installError;
    }

    /* Defines the core classes in the class loader of AbstractPhotoRenderer */
    private static void defineCoreClasses(ClassLoader pluginClassLoader) throws Exception {
        ClassLoader coreLoader = AbstractPhotoRenderer.class.getClassLoader();
        List<String> names = new ArrayList<String>();
        try (InputStream in = pluginClassLoader.getResourceAsStream("core/classes.txt")) {
            for (String line : new String(readAll(in), "UTF-8").split("\n")) {
                if (!line.trim().isEmpty()) {
                    names.add(line.trim());
                }
            }
        }
        Object lookup = null;
        Method defineClass = null;
        try {
            // Java 9+: a private lookup in AbstractPhotoRenderer's package (unnamed modules are open to everyone)
            Method privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
            lookup = privateLookupIn.invoke(null, AbstractPhotoRenderer.class, MethodHandles.lookup());
            defineClass = MethodHandles.Lookup.class.getMethod("defineClass", byte[].class);
        } catch (NoSuchMethodException ex) {
            // Java 8: ClassLoader.defineClass
            defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
        }
        for (String name : names) {
            try {
                Class.forName(name, false, coreLoader);
                continue; // Already defined (e.g. SH3D reopened in the same JVM)
            } catch (ClassNotFoundException ex) {
                // Define it
            }
            byte [] bytes;
            try (InputStream in = pluginClassLoader.getResourceAsStream("core/" + name.replace('.', '/') + ".class.bin")) {
                bytes = readAll(in);
            }
            if (lookup != null) {
                defineClass.invoke(lookup, (Object)bytes);
            } else {
                defineClass.invoke(coreLoader, name, bytes, 0, bytes.length);
            }
        }
    }

    /*
     * Extracts native/<platform>/ (listed in native/<platform>/files.txt) to a versioned cache directory and returns
     * the path of the sh3d-cycles executable, or null if the plugin has no binaries for this platform.
     */
    private static String extractNatives(ClassLoader pluginClassLoader) throws IOException {
        String platform = getPlatform();
        String base = "native/" + platform + "/";
        byte [] list;
        try (InputStream in = pluginClassLoader.getResourceAsStream(base + "files.txt")) {
            if (in == null) {
                return null;
            }
            list = readAll(in);
        }
        String [] files = new String(list, "UTF-8").split("\n");
        String version = files[0].trim(); // First line: content hash
        File directory = new File(getCacheDirectory(), "sh3d-cycles-" + version + "-" + platform);
        File executable = new File(directory, platform.startsWith("windows") ? "sh3d-cycles.exe" : "sh3d-cycles");
        File complete = new File(directory, ".complete");
        if (!complete.exists()) {
            for (int i = 1; i < files.length; i++) {
                String file = files[i].trim();
                if (file.isEmpty()) {
                    continue;
                }
                File target = new File(directory, file);
                target.getParentFile().mkdirs();
                try (InputStream in = pluginClassLoader.getResourceAsStream(base + file);
                     OutputStream out = new FileOutputStream(target)) {
                    byte [] buffer = new byte [1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, n);
                    }
                }
            }
            executable.setExecutable(true);
            complete.createNewFile();
        }
        return executable.getAbsolutePath();
    }

    private static String getPlatform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
        arch = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") ? "arm64" : arch;
        return (os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : "linux") + "-" + arch;
    }

    private static File getCacheDirectory() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        String home = System.getProperty("user.home");
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return new File(local != null ? local : home, "eTeks" + File.separator + "Sweet Home 3D" + File.separator + "cache");
        } else if (os.startsWith("mac")) {
            return new File(home, "Library/Caches/eTeks/Sweet Home 3D");
        } else {
            String xdg = System.getenv("XDG_CACHE_HOME");
            return new File(xdg != null ? xdg : home + "/.cache", "sweethome3d");
        }
    }

    private static byte [] readAll(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("Missing resource");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte [] buffer = new byte [1 << 16];
        for (int n; (n = in.read(buffer)) > 0; ) {
            bytes.write(buffer, 0, n);
        }
        return bytes.toByteArray();
    }
}
