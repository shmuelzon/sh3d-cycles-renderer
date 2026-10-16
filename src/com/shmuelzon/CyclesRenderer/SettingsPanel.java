package com.shmuelzon.CyclesRenderer;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer;
import com.eteks.sweethome3d.j3d.CyclesRenderer;
import com.eteks.sweethome3d.j3d.CyclesRendererSettings;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;

/*
 * Settings of the Cycles renderer for a project, saved in the project. A preview renders the current point of view of
 * the project with the settings of the panel, before they're saved.
 */
public class SettingsPanel extends JPanel {
    private static final int PREVIEW_WIDTH = 480;
    private static final int PREVIEW_HEIGHT = 270;

    private final Home home;
    private final ResourceBundle resource;
    private final JCheckBox gpuCheckBox;
    private final JLabel devicesLabel;
    private final JSpinner highQualitySamplesSpinner;
    private final JSpinner lowQualitySamplesSpinner;
    private final JCheckBox autoExposureCheckBox;
    private final JSpinner exposureSpinner;
    private final JComboBox<String> viewComboBox;
    private final JComboBox<String> lookComboBox;
    private final JSpinner highlightCompressionSpinner;
    private final JSpinner skyStrengthSpinner;
    private final JButton previewButton;
    private final JLabel previewLabel;
    private final JLabel previewStatusLabel;
    private volatile AbstractPhotoRenderer previewRenderer;
    private Thread previewThread;

    public SettingsPanel(Home home, ClassLoader pluginClassLoader) {
        super(new GridBagLayout());
        this.home = home;
        this.resource = ResourceBundle.getBundle("com.shmuelzon.CyclesRenderer.ApplicationPlugin", Locale.getDefault(),
            pluginClassLoader);

        this.gpuCheckBox = new JCheckBox(text("gpuCheckBox"));
        this.gpuCheckBox.setToolTipText(text("gpuCheckBox.tooltip"));
        this.devicesLabel = new JLabel(text("devicesLabel.detecting"));
        this.devicesLabel.setFont(this.devicesLabel.getFont().deriveFont(Font.ITALIC));
        this.highQualitySamplesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 65536, 16));
        this.lowQualitySamplesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 65536, 8));
        this.autoExposureCheckBox = new JCheckBox(text("autoExposureCheckBox"));
        this.autoExposureCheckBox.setToolTipText(text("autoExposureCheckBox.tooltip"));
        this.exposureSpinner = new JSpinner(new SpinnerNumberModel(0.0, -10.0, 10.0, 0.25));
        this.viewComboBox = new JComboBox<String>(CyclesRendererSettings.VIEWS.toArray(new String [0]));
        this.viewComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                return super.getListCellRendererComponent(list, text("viewComboBox." + value), index, selected, focus);
            }
        });
        this.lookComboBox = new JComboBox<String>();
        this.highlightCompressionSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 1.0, 0.05));
        this.skyStrengthSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 10.0, 0.1));
        this.viewComboBox.addActionListener(ev -> updateLooks((String)this.lookComboBox.getSelectedItem()));
        this.autoExposureCheckBox.addActionListener(ev -> updateExposureLabel());

        this.previewButton = new JButton(text("previewButton"));
        this.previewButton.setToolTipText(text("previewButton.tooltip"));
        this.previewButton.addActionListener(ev -> togglePreview());
        JButton resetButton = new JButton(text("resetButton"));
        resetButton.addActionListener(ev -> load(new CyclesRendererSettings()));
        this.previewLabel = new JLabel();
        this.previewLabel.setPreferredSize(new Dimension(PREVIEW_WIDTH, PREVIEW_HEIGHT));
        this.previewLabel.setHorizontalAlignment(SwingConstants.CENTER);
        this.previewLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        this.previewStatusLabel = new JLabel(" ");

        int row = 0;
        addSection("deviceSection", row++);
        add(this.gpuCheckBox, row, 0, 2);
        add(this.devicesLabel, row++, 2, 2);
        addSection("qualitySection", row++);
        addLabeled("highQualitySamples", this.highQualitySamplesSpinner, row, 0);
        addLabeled("lowQualitySamples", this.lowQualitySamplesSpinner, row++, 2);
        addSection("lightSection", row++);
        add(this.autoExposureCheckBox, row, 0, 2);
        addLabeled("exposure", this.exposureSpinner, row++, 2);
        addLabeled("skyStrength", this.skyStrengthSpinner, row++, 0);
        addSection("colorSection", row++);
        addLabeled("view", this.viewComboBox, row, 0);
        addLabeled("look", this.lookComboBox, row++, 2);
        addLabeled("highlightCompression", this.highlightCompressionSpinner, row++, 0);
        addSection("previewSection", row++);
        add(this.previewLabel, row++, 0, 4);
        add(this.previewStatusLabel, row++, 0, 4);
        JPanel buttons = new JPanel();
        buttons.add(this.previewButton);
        buttons.add(resetButton);
        add(buttons, row, 0, 4);

        load(CyclesRendererSettings.read(home));
        detectDevices();
    }

    private String text(String key) {
        return this.resource.getString("CyclesRenderer.SettingsPanel." + key);
    }

    private void addSection(String key, int row) {
        JLabel label = new JLabel(text(key));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        GridBagConstraints constraints = constraints(row, 0, 4);
        constraints.insets = new Insets(row == 0 ? 0 : 10, 0, 2, 0);
        add(label, constraints);
    }

    private void addLabeled(String key, JComponent component, int row, int column) {
        JLabel label = new JLabel(text(key + "Label"));
        String tooltip = text(key + "Label.tooltip");
        label.setToolTipText(tooltip);
        component.setToolTipText(tooltip);
        label.setLabelFor(component);
        add(label, row, column, 1);
        GridBagConstraints constraints = constraints(row, column + 1, 1);
        if (component instanceof JSpinner) {
            ((JSpinner.DefaultEditor)((JSpinner)component).getEditor()).getTextField().setColumns(5);
        } else {
            constraints.fill = GridBagConstraints.HORIZONTAL;
        }
        add(component, constraints);
    }

    private void add(JComponent component, int row, int column, int width) {
        add(component, constraints(row, column, width));
    }

    private static GridBagConstraints constraints(int row, int column, int width) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(2, column == 0 ? 10 : 5, 2, 5);
        return constraints;
    }

    private void load(CyclesRendererSettings settings) {
        this.gpuCheckBox.setSelected(settings.isGpu());
        this.highQualitySamplesSpinner.setValue(settings.getHighQualitySamples());
        this.lowQualitySamplesSpinner.setValue(settings.getLowQualitySamples());
        this.autoExposureCheckBox.setSelected(settings.isAutoExposure());
        this.exposureSpinner.setValue((double)settings.getExposure());
        this.viewComboBox.setSelectedItem(settings.getView());
        updateLooks(settings.getLook());
        this.highlightCompressionSpinner.setValue((double)settings.getHighlightCompression());
        this.skyStrengthSpinner.setValue((double)settings.getSkyStrength());
        updateExposureLabel();
    }

    private CyclesRendererSettings getSettings() {
        CyclesRendererSettings settings = new CyclesRendererSettings();
        settings.setGpu(this.gpuCheckBox.isSelected());
        settings.setHighQualitySamples(((Number)this.highQualitySamplesSpinner.getValue()).intValue());
        settings.setLowQualitySamples(((Number)this.lowQualitySamplesSpinner.getValue()).intValue());
        settings.setAutoExposure(this.autoExposureCheckBox.isSelected());
        settings.setExposure(((Number)this.exposureSpinner.getValue()).floatValue());
        settings.setView((String)this.viewComboBox.getSelectedItem());
        settings.setLook((String)this.lookComboBox.getSelectedItem());
        settings.setHighlightCompression(((Number)this.highlightCompressionSpinner.getValue()).floatValue());
        settings.setSkyStrength(((Number)this.skyStrengthSpinner.getValue()).floatValue());
        return settings;
    }

    /* The looks of the selected view, keeping the selected look if the view has it */
    private void updateLooks(String look) {
        List<String> looks = CyclesRendererSettings.getLooks((String)this.viewComboBox.getSelectedItem());
        this.lookComboBox.setModel(new DefaultComboBoxModel<String>(looks.toArray(new String [0])));
        this.lookComboBox.setSelectedItem(looks.contains(look) ? look : CyclesRendererSettings.NO_LOOK);
    }

    /* With the automatic exposure, the exposure is a correction of the automatic one */
    private void updateExposureLabel() {
        this.exposureSpinner.setToolTipText(text(this.autoExposureCheckBox.isSelected()
            ? "exposureLabel.autoTooltip" : "exposureLabel.tooltip"));
    }

    /* Lists the GPUs Cycles finds, with the native renderer, on a background thread */
    private void detectDevices() {
        Thread thread = new Thread(() -> {
            List<String> gpus = new ArrayList<String>();
            String error = null;
            try {
                Process process = new ProcessBuilder(System.getProperty(Plugin.RENDERER_CLASS + ".executable"),
                    "--list-devices").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        if (line.startsWith("DEVICE ") && !line.startsWith("DEVICE CPU ")) {
                            gpus.add(line.substring("DEVICE ".length()));
                        }
                    }
                }
                process.waitFor();
            } catch (Exception ex) {
                error = ex.toString();
            }
            final String text = error != null ? String.format(text("devicesLabel.error"), error)
                : gpus.isEmpty() ? text("devicesLabel.none") : String.format(text("devicesLabel.found"), String.join(", ", gpus));
            EventQueue.invokeLater(() -> this.devicesLabel.setText(text));
        }, "Cycles device detection");
        thread.setDaemon(true);
        thread.start();
    }

    /* Renders the current point of view of a copy of the project with the settings of the panel, or stops it */
    private void togglePreview() {
        if (this.previewThread != null) {
            stopPreview();
            return;
        }
        final Home preview = this.home.clone();
        getSettings().write(preview);
        final Camera camera = this.home.getCamera().clone();
        final BufferedImage image = new BufferedImage(PREVIEW_WIDTH, PREVIEW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        final ImageObserver observer = (img, flags, x, y, width, height) -> {
            this.previewLabel.setIcon(new ImageIcon(image));
            this.previewLabel.repaint();
            return true;
        };
        this.previewButton.setText(text("stopButton"));
        this.previewStatusLabel.setText(text("previewStatus.rendering"));
        this.previewThread = new Thread(() -> {
            long start = System.currentTimeMillis();
            String status;
            try {
                this.previewRenderer = AbstractPhotoRenderer.createInstance(Plugin.RENDERER_CLASS, preview, null,
                    AbstractPhotoRenderer.Quality.LOW);
                this.previewRenderer.render(image, camera, observer);
                status = String.format(text("previewStatus.done"), (System.currentTimeMillis() - start) / 1000.0,
                    resultDevice(CyclesRenderer.getLastResult()));
            } catch (InterruptedIOException ex) {
                status = text("previewStatus.stopped");
            } catch (Throwable ex) {
                status = String.format(text("previewStatus.error"), ex.getMessage());
                ex.printStackTrace();
            } finally {
                if (this.previewRenderer != null) {
                    this.previewRenderer.dispose();
                    this.previewRenderer = null;
                }
            }
            final String finalStatus = status;
            EventQueue.invokeLater(() -> {
                this.previewLabel.setIcon(new ImageIcon(image));
                this.previewStatusLabel.setText(finalStatus);
                this.previewButton.setText(text("previewButton"));
                this.previewThread = null;
            });
        }, "Cycles preview");
        this.previewThread.start();
    }

    private void stopPreview() {
        AbstractPhotoRenderer renderer = this.previewRenderer;
        if (renderer != null) {
            renderer.stop();
        }
        if (this.previewThread != null) {
            this.previewThread.interrupt();
        }
    }

    private static String resultDevice(String result) {
        Matcher matcher = Pattern.compile("\"device\": \"([^\"]*)\"").matcher(result != null ? result : "");
        return matcher.find() ? matcher.group(1) : "?";
    }

    /* Shows the panel in a dialog, and saves its settings in the project when it's confirmed */
    public void displayView(Component parent) {
        String [] options = {text("okButton"), text("cancelButton")};
        JOptionPane optionPane = new JOptionPane(this, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null,
            options, options[0]);
        JDialog dialog = optionPane.createDialog(parent, text("title"));
        dialog.setResizable(true);
        dialog.setVisible(true);
        stopPreview();
        CyclesRendererSettings settings = getSettings();
        if (options[0].equals(optionPane.getValue()) && !settings.equals(CyclesRendererSettings.read(this.home))
            && settings.write(this.home)) {
            this.home.setModified(true);
        }
        dialog.dispose();
    }
}
