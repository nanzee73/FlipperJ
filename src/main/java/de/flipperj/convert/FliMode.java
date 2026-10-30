package de.flipperj.convert;

/** Supported FLI variants. */
public enum FliMode {
    FLI("FLI (multicolor)", true, false, "fli"),
    AFLI("AFLI (hires FLI)", false, false, "afl"),
    IFLI("IFLI (multicolor interlace)", true, true, "ifl");

    public final String label;
    public final boolean multicolor;
    public final boolean interlaced;
    public final String rawExtension;

    FliMode(String label, boolean mc, boolean il, String ext) {
        this.label = label;
        this.multicolor = mc;
        this.interlaced = il;
        this.rawExtension = ext;
    }

    /** Pixel width of one frame (multicolor 160, hires 320). */
    public int frameWidth() { return multicolor ? 160 : 320; }

    @Override public String toString() { return de.c64gfx.swing.I18n.tr("mode." + name()); }
}
