package com.eteks.sweethome3d.j3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.eteks.sweethome3d.j3d.AbstractPhotoRenderer.Quality;
import com.eteks.sweethome3d.model.Home;

/*
 * Settings of the Cycles renderer, saved in the properties of a project (so they're the same for every render of it,
 * from SH3D's dialogs or other plugins), and turned into options of the native renderer.
 *
 * A setting missing from the project falls back to the system properties CyclesRenderer has always read (device,
 * <low|high>Quality.samples), then to its default. The "options" system property is added last, so it overrides all.
 */
public class CyclesRendererSettings implements Cloneable {
    public static final String PREFIX = "com.eteks.sweethome3d.j3d.CyclesRenderer.";

    public static final String STANDARD_VIEW = "Standard";
    public static final String AGX_VIEW = "AgX";
    public static final List<String> VIEWS = Collections.unmodifiableList(Arrays.asList(STANDARD_VIEW, AGX_VIEW));
    public static final String NO_LOOK = "None";

    public static final boolean DEFAULT_GPU = false;
    public static final int DEFAULT_LOW_QUALITY_SAMPLES = 32;
    public static final int DEFAULT_HIGH_QUALITY_SAMPLES = 256;
    public static final boolean DEFAULT_AUTO_EXPOSURE = false;
    public static final float DEFAULT_EXPOSURE = 0;
    public static final String DEFAULT_VIEW = STANDARD_VIEW;
    public static final String DEFAULT_LOOK = NO_LOOK;
    public static final float DEFAULT_HIGHLIGHT_COMPRESSION = 0;
    public static final float DEFAULT_SKY_STRENGTH = 1;

    private static final String GPU = "gpu";
    private static final String LOW_QUALITY_SAMPLES = "lowQuality.samples";
    private static final String HIGH_QUALITY_SAMPLES = "highQuality.samples";
    private static final String AUTO_EXPOSURE = "autoExposure";
    private static final String EXPOSURE = "exposure";
    private static final String VIEW = "view";
    private static final String LOOK = "look";
    private static final String HIGHLIGHT_COMPRESSION = "highlightCompression";
    private static final String SKY_STRENGTH = "skyStrength";
    private static final List<String> NAMES = Arrays.asList(GPU, LOW_QUALITY_SAMPLES, HIGH_QUALITY_SAMPLES, AUTO_EXPOSURE,
        EXPOSURE, VIEW, LOOK, HIGHLIGHT_COMPRESSION, SKY_STRENGTH);

    private boolean gpu = DEFAULT_GPU;
    private int lowQualitySamples = DEFAULT_LOW_QUALITY_SAMPLES;
    private int highQualitySamples = DEFAULT_HIGH_QUALITY_SAMPLES;
    private boolean autoExposure = DEFAULT_AUTO_EXPOSURE;
    private float exposure = DEFAULT_EXPOSURE;
    private String view = DEFAULT_VIEW;
    private String look = DEFAULT_LOOK;
    private float highlightCompression = DEFAULT_HIGHLIGHT_COMPRESSION;
    private float skyStrength = DEFAULT_SKY_STRENGTH;

    /* Looks of each view in Blender's color management configuration, as Blender lists them */
    public static List<String> getLooks(String view) {
        if (AGX_VIEW.equals(view)) {
            return Arrays.asList(NO_LOOK, "Punchy", "Greyscale", "Very High Contrast", "High Contrast",
                "Medium High Contrast", "Base Contrast", "Medium Low Contrast", "Low Contrast", "Very Low Contrast");
        }
        return Arrays.asList(NO_LOOK, "Very High Contrast", "High Contrast", "Medium High Contrast", "Medium Contrast",
            "Medium Low Contrast", "Low Contrast", "Very Low Contrast");
    }

    /* The settings of a project */
    public static CyclesRendererSettings read(Home home) {
        CyclesRendererSettings settings = new CyclesRendererSettings();
        settings.gpu = Boolean.parseBoolean(get(home, GPU,
            String.valueOf(!System.getProperty(PREFIX + "device", "cpu").equalsIgnoreCase("cpu"))));
        settings.lowQualitySamples = getInt(home, LOW_QUALITY_SAMPLES, DEFAULT_LOW_QUALITY_SAMPLES);
        settings.highQualitySamples = getInt(home, HIGH_QUALITY_SAMPLES, DEFAULT_HIGH_QUALITY_SAMPLES);
        settings.autoExposure = Boolean.parseBoolean(get(home, AUTO_EXPOSURE, String.valueOf(DEFAULT_AUTO_EXPOSURE)));
        settings.exposure = getFloat(home, EXPOSURE, DEFAULT_EXPOSURE);
        settings.setView(get(home, VIEW, DEFAULT_VIEW));
        settings.setLook(get(home, LOOK, DEFAULT_LOOK));
        settings.highlightCompression = getFloat(home, HIGHLIGHT_COMPRESSION, DEFAULT_HIGHLIGHT_COMPRESSION);
        settings.skyStrength = getFloat(home, SKY_STRENGTH, DEFAULT_SKY_STRENGTH);
        return settings;
    }

    /* Saves the settings in the properties of a project, and returns whether they changed */
    public boolean write(Home home) {
        String [] values = {String.valueOf(this.gpu), String.valueOf(this.lowQualitySamples),
            String.valueOf(this.highQualitySamples), String.valueOf(this.autoExposure), String.valueOf(this.exposure),
            this.view, this.look, String.valueOf(this.highlightCompression), String.valueOf(this.skyStrength)};
        boolean changed = false;
        for (int i = 0; i < NAMES.size(); i++) {
            String name = PREFIX + NAMES.get(i);
            if (!values[i].equals(home.getProperty(name))) {
                home.setProperty(name, values[i]);
                changed = true;
            }
        }
        return changed;
    }

    /* Options of the native renderer for a render at the given quality */
    public List<String> toOptions(Quality quality) {
        List<String> options = new ArrayList<String>();
        options.add("--samples");
        options.add(String.valueOf(quality == Quality.HIGH ? this.highQualitySamples : this.lowQualitySamples));
        options.add("--device");
        options.add(this.gpu ? System.getProperty(PREFIX + "device", "auto").replaceFirst("^(?i)cpu$", "auto") : "cpu");
        options.add("--auto-exposure");
        options.add(this.autoExposure ? "on" : "off");
        options.add("--exposure");
        options.add(String.format(Locale.ROOT, "%.2f", this.exposure));
        options.add("--view");
        options.add(this.view);
        options.add("--look");
        options.add(this.look.replace(' ', '_'));
        options.add("--highlight-compression");
        options.add(String.format(Locale.ROOT, "%.2f", this.highlightCompression));
        options.add("--sky-strength");
        options.add(String.format(Locale.ROOT, "%.3f", this.skyStrength));
        String extra = System.getProperty(PREFIX + "options");
        if (extra != null && !extra.trim().isEmpty()) {
            options.addAll(Arrays.asList(extra.trim().split("\\s+")));
        }
        return options;
    }

    private static String get(Home home, String name, String defaultValue) {
        String value = home != null ? home.getProperty(PREFIX + name) : null;
        if (value == null) {
            value = System.getProperty(PREFIX + name);
        }
        return value != null ? value : defaultValue;
    }

    private static int getInt(Home home, String name, int defaultValue) {
        try {
            return Integer.parseInt(get(home, name, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static float getFloat(Home home, String name, float defaultValue) {
        try {
            return Float.parseFloat(get(home, name, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public boolean isGpu() {
        return this.gpu;
    }

    public void setGpu(boolean gpu) {
        this.gpu = gpu;
    }

    public int getLowQualitySamples() {
        return this.lowQualitySamples;
    }

    public void setLowQualitySamples(int samples) {
        this.lowQualitySamples = Math.max(1, samples);
    }

    public int getHighQualitySamples() {
        return this.highQualitySamples;
    }

    public void setHighQualitySamples(int samples) {
        this.highQualitySamples = Math.max(1, samples);
    }

    public boolean isAutoExposure() {
        return this.autoExposure;
    }

    public void setAutoExposure(boolean autoExposure) {
        this.autoExposure = autoExposure;
    }

    public float getExposure() {
        return this.exposure;
    }

    public void setExposure(float exposure) {
        this.exposure = exposure;
    }

    public String getView() {
        return this.view;
    }

    /* An unknown view falls back to the default one, and a look the view doesn't have to none */
    public void setView(String view) {
        this.view = VIEWS.contains(view) ? view : DEFAULT_VIEW;
        setLook(this.look);
    }

    public String getLook() {
        return this.look;
    }

    public void setLook(String look) {
        this.look = getLooks(this.view).contains(look) ? look : NO_LOOK;
    }

    public float getHighlightCompression() {
        return this.highlightCompression;
    }

    public void setHighlightCompression(float highlightCompression) {
        this.highlightCompression = Math.max(0, Math.min(1, highlightCompression));
    }

    public float getSkyStrength() {
        return this.skyStrength;
    }

    public void setSkyStrength(float skyStrength) {
        this.skyStrength = Math.max(0, skyStrength);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof CyclesRendererSettings)) {
            return false;
        }
        CyclesRendererSettings settings = (CyclesRendererSettings)object;
        return this.gpu == settings.gpu && this.lowQualitySamples == settings.lowQualitySamples
            && this.highQualitySamples == settings.highQualitySamples && this.autoExposure == settings.autoExposure
            && this.exposure == settings.exposure && this.view.equals(settings.view) && this.look.equals(settings.look)
            && this.highlightCompression == settings.highlightCompression && this.skyStrength == settings.skyStrength;
    }

    @Override
    public int hashCode() {
        return Arrays.asList(this.gpu, this.lowQualitySamples, this.highQualitySamples, this.autoExposure, this.exposure,
            this.view, this.look, this.highlightCompression, this.skyStrength).hashCode();
    }

    @Override
    public CyclesRendererSettings clone() {
        try {
            return (CyclesRendererSettings)super.clone();
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError(ex);
        }
    }
}
