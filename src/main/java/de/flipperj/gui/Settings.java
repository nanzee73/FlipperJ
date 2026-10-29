package de.flipperj.gui;

import java.util.prefs.Preferences;

/** User settings that are kept between sessions (stored in the Java user preferences). */
public final class Settings {
    public boolean livePreview = true;
    public boolean pixelGrid = true;
    public boolean linkPreviews = true;
    public boolean palAspect = true;
    public boolean writePrg = true;
    public boolean writeRaw = false;
    /** true: output next to the input picture; false: in the last folder chosen for output. */
    public boolean outputNextToInput = false;

    private static Preferences prefs() { return Preferences.userRoot().node("de/flipperj/settings"); }

    public static Settings load() {
        Settings s = new Settings();
        try {
            Preferences p = prefs();
            s.livePreview = p.getBoolean("livePreview", s.livePreview);
            s.pixelGrid = p.getBoolean("pixelGrid", s.pixelGrid);
            s.linkPreviews = p.getBoolean("linkPreviews", s.linkPreviews);
            s.palAspect = p.getBoolean("palAspect", s.palAspect);
            s.writePrg = p.getBoolean("writePrg", s.writePrg);
            s.writeRaw = p.getBoolean("writeRaw", s.writeRaw);
            s.outputNextToInput = p.getBoolean("outputNextToInput", s.outputNextToInput);
        } catch (Exception ignored) {
            // preferences not available: defaults
        }
        return s;
    }

    public void save() {
        try {
            Preferences p = prefs();
            p.putBoolean("livePreview", livePreview);
            p.putBoolean("pixelGrid", pixelGrid);
            p.putBoolean("linkPreviews", linkPreviews);
            p.putBoolean("palAspect", palAspect);
            p.putBoolean("writePrg", writePrg);
            p.putBoolean("writeRaw", writeRaw);
            p.putBoolean("outputNextToInput", outputNextToInput);
            p.flush();
        } catch (Exception ignored) {
            // preferences not available
        }
    }
}
