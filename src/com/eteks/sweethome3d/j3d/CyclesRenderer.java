package com.eteks.sweethome3d.j3d;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.tools.OperatingSystem;
import com.eteks.sweethome3d.viewcontroller.Object3DFactory;

/*
 * Photo renderer using Blender's Cycles path tracer, through the native sh3d-cycles helper.
 *
 * This class is defined by the plugin in Sweet Home 3D's own class loader and package (see com.shmuelzon.CyclesRenderer.Plugin), so
 * it's listed in the Create photo / photos / video dialogs and AbstractPhotoRenderer.createInstance() finds it by
 * name for any plugin. The scene is exported with CyclesSceneExporter.
 *
 * Its settings are saved in the project (see CyclesRendererSettings, edited in the plugin's settings panel), with
 * these system properties (set by the plugin or on the command line):
 *   com.eteks.sweethome3d.j3d.CyclesRenderer.executable   path of sh3d-cycles (required)
 *   com.eteks.sweethome3d.j3d.CyclesRenderer.device       device used with the GPU setting: auto (default), hip, metal...
 *   com.eteks.sweethome3d.j3d.CyclesRenderer.options      extra sh3d-cycles options, overriding the settings
 *   com.eteks.sweethome3d.j3d.CyclesRenderer.debug        print sh3d-cycles' output and keep the exported scenes
 */
public class CyclesRenderer extends AbstractPhotoRenderer {
    private static final String PREFIX = CyclesRenderer.class.getName() + ".";

    private static volatile String lastResult;

    private final Object3DFactory object3dFactory;
    private File workDirectory;
    private volatile Process process;

    public CyclesRenderer(Home home, Object3DFactory object3dFactory, Quality quality) {
        super(home, quality);
        this.object3dFactory = object3dFactory;
    }

    @Override
    public String getName() {
        return "Cycles";
    }

    /* Cheap on purpose: SH3D creates renderers to paint each entry of its renderer combo box */
    @Override
    public boolean isAvailable() {
        String executable = System.getProperty(PREFIX + "executable");
        return executable != null && new File(executable).canExecute();
    }

    @Override
    public void render(BufferedImage image, Camera camera, List<? extends Selectable> updatedItems,
                       final ImageObserver observer) throws IOException {
        if (this.workDirectory == null) {
            this.workDirectory = OperatingSystem.createTemporaryFile("cycles", "");
            this.workDirectory.delete();
            this.workDirectory.mkdirs();
        }
        File exportDirectory = new File(this.workDirectory, "export");
        exportDirectory.mkdirs();

        // Export the scene for this camera, with its updated items
        new CyclesSceneExporter(this, getHome(), this.object3dFactory)
            .export(exportDirectory, camera, image.getWidth(), image.getHeight());

        File output = new File(this.workDirectory, "render.png");
        output.delete();
        List<String> command = new ArrayList<String>();
        command.add(System.getProperty(PREFIX + "executable"));
        command.add(exportDirectory.getAbsolutePath());
        command.add(output.getAbsolutePath());
        command.add("--progress");
        command.addAll(CyclesRendererSettings.read(getHome()).toOptions(getQuality()));

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        StringBuilder log = new StringBuilder();
        Process process = builder.start();
        this.process = process;
        KernelStatus kernelStatus = new KernelStatus(image, observer);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            boolean debug = Boolean.getBoolean(PREFIX + "debug");
            for (String line; (line = reader.readLine()) != null; ) {
                if (debug && !line.startsWith("PROGRESS ")) {
                    System.err.println("sh3d-cycles: " + line);
                }
                if (line.startsWith("RESULT ")) {
                    lastResult = line.substring("RESULT ".length());
                }
                if (line.startsWith("Cycles device: ")) {
                    kernelStatus.setDevice(line.substring("Cycles device: ".length()));
                } else if (line.startsWith("STATUS ")) {
                    kernelStatus.update(line.substring("STATUS ".length()));
                } else if (line.startsWith("PREVIEW ")) {
                    kernelStatus.stop();
                    showImage(image, new File(line.substring("PREVIEW ".length())), observer, ImageObserver.SOMEBITS);
                } else if (!line.startsWith("PROGRESS ")) {
                    log.append(line).append('\n');
                    if (log.length() > 20000) {
                        log.delete(0, log.length() - 20000);
                    }
                }
            }
            kernelStatus.stop();
            int status = process.waitFor();
            if (Thread.currentThread().isInterrupted() || this.process == null) {
                throw new InterruptedIOException("Rendering stopped");
            }
            if (status != 0 || !output.exists()) {
                throw new IOException("Cycles rendering failed (exit code " + status + "):\n" + log);
            }
            showImage(image, output, observer, ImageObserver.FRAMEBITS);
        } catch (InterruptedException ex) {
            throw new InterruptedIOException("Rendering stopped");
        } finally {
            kernelStatus.stop();
            process.destroy();
            this.process = null;
        }
    }

    /*
     * While Cycles loads GPU kernels before rendering, which takes minutes when it compiles them on first use, shows
     * an image saying so instead of the render, with the progress when Cycles counts the kernels (Metal). Shown only
     * with a GPU and when it lasts, until the first preview (other statuses, e.g. scene updates waiting for kernels,
     * don't end it).
     */
    private static class KernelStatus implements Runnable {
        private static final long DELAY = 1500;
        private static final Pattern COUNT = Pattern.compile("(\\d+) / (\\d+) render kernels");

        private final BufferedImage image;
        private final ImageObserver observer;
        private Thread thread;
        private String status;
        private long start;
        private volatile boolean stopped;
        private boolean gpu;

        KernelStatus(BufferedImage image, ImageObserver observer) {
            this.image = image;
            this.observer = observer;
        }

        /* The device of the render ("CPU [...]", "METAL [...]"...), which changes to the CPU if the GPU fails */
        synchronized void setDevice(String device) {
            this.gpu = !device.startsWith("CPU");
            if (!this.gpu) {
                this.status = null;
            }
        }

        synchronized void update(String status) {
            if (this.stopped || !this.gpu || !status.contains("render kernels")) {
                return;
            }
            if (this.status == null) {
                this.start = System.currentTimeMillis();
            }
            this.status = status;
            if (this.thread == null) {
                this.thread = new Thread(this, "Cycles kernel status");
                this.thread.setDaemon(true);
                this.thread.start();
            }
        }

        /* Once it returns, the status isn't drawn anymore */
        void stop() {
            synchronized (this) {
                this.stopped = true;
                notifyAll();
            }
            synchronized (this.image) {
                // Waits for a status being drawn
            }
        }

        public void run() {
            try {
                while (true) {
                    String status;
                    long elapsed;
                    synchronized (this) {
                        wait(1000);
                        if (this.stopped) {
                            return;
                        }
                        status = this.status;
                        elapsed = System.currentTimeMillis() - this.start;
                    }
                    if (status != null && elapsed >= DELAY) {
                        draw(status);
                    }
                }
            } catch (InterruptedException ex) {
                // Stopped
            }
        }

        private void draw(String status) {
            String detail = "Loading render kernels";
            double progress = -1;
            Matcher matcher = COUNT.matcher(status);
            if (matcher.find()) {
                int count = Integer.parseInt(matcher.group(1));
                int total = Math.max(1, Integer.parseInt(matcher.group(2)));
                detail = String.format("Compiling render kernels: %d / %d", count, total);
                progress = (double)count / total;
            }
            synchronized (this.image) {
                if (this.stopped) {
                    return;
                }
                int width = this.image.getWidth();
                int height = this.image.getHeight();
                Graphics2D g = this.image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, width, height);
                int size = Math.max(10, Math.min(width / 28, height / 12));
                Font font = new Font(Font.SANS_SERIF, Font.PLAIN, size);
                List<String> lines = new ArrayList<String>();
                lines.add("Preparing the GPU for rendering");
                lines.add(detail);
                int lineHeight = size * 3 / 2;
                int barHeight = progress >= 0 ? size : 0;
                int y = (height - lines.size() * lineHeight - barHeight - lineHeight) / 2 + size;
                g.setColor(Color.WHITE);
                for (int i = 0; i < lines.size(); i++) {
                    g.setFont(i == 0 ? font.deriveFont(Font.BOLD, size * 1.2f) : font);
                    int textWidth = g.getFontMetrics().stringWidth(lines.get(i));
                    g.drawString(lines.get(i), (width - textWidth) / 2, y);
                    y += lineHeight;
                }
                if (progress >= 0) {
                    int barWidth = width / 2;
                    int x = (width - barWidth) / 2;
                    g.drawRect(x, y - size, barWidth, barHeight);
                    g.fillRect(x, y - size, (int)(barWidth * progress), barHeight);
                    y += lineHeight;
                }
                g.setColor(Color.GRAY);
                g.setFont(font.deriveFont(size * 0.8f));
                String note = "Only on first use: the compiled kernels are kept for the next renders";
                g.drawString(note, (width - g.getFontMetrics().stringWidth(note)) / 2, y);
                g.dispose();
            }
            if (this.observer != null) {
                EventQueue.invokeLater(new Runnable() {
                    public void run() {
                        KernelStatus.this.observer.imageUpdate(KernelStatus.this.image, ImageObserver.SOMEBITS, 0, 0,
                            KernelStatus.this.image.getWidth(), KernelStatus.this.image.getHeight());
                    }
                });
            }
        }
    }

    /* The RESULT line of the last render (JSON with the device used, render times...), for the plugin's settings panel */
    public static String getLastResult() {
        return lastResult;
    }

    /* Draws the rendered (or preview) image into the photo image and notifies the observer, like PhotoRenderer */
    private static void showImage(final BufferedImage image, File file, final ImageObserver observer, final int flags) {
        try {
            BufferedImage rendered = ImageIO.read(file);
            if (rendered == null) {
                return;
            }
            synchronized (image) {
                Graphics2D g = image.createGraphics();
                g.drawImage(rendered, 0, 0, image.getWidth(), image.getHeight(), null);
                g.dispose();
            }
            if (observer != null) {
                EventQueue.invokeLater(new Runnable() {
                    public void run() {
                        observer.imageUpdate(image, flags, 0, 0, image.getWidth(), image.getHeight());
                    }
                });
            }
        } catch (IOException ex) {
            // A preview may be replaced while it's read, ignore it
        }
    }

    @Override
    public void stop() {
        Process process = this.process;
        this.process = null;
        if (process != null) {
            process.destroy();
        }
    }

    @Override
    public void dispose() {
        stop();
        if (this.workDirectory != null) {
            if (Boolean.getBoolean(PREFIX + "debug")) {
                System.err.println("sh3d-cycles: keeping " + this.workDirectory); // To inspect the exported scene
            } else {
                delete(this.workDirectory);
            }
            this.workDirectory = null;
        }
    }

    private static void delete(File file) {
        File [] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
