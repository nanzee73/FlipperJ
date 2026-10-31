package de.flipperj.convert;

/** Supported FLI variants. */
public enum FliMode {
    FLI(true, false, false, "fli"),
    AFLI(false, false, false, "afl"),
    IFLI(true, true, false, "ifl"),
    /** Hires FLI over x-expanded sprites, NUFLI Editor format. */
    NUFLI(false, false, true, "nuf"),
    /** Hires FLI over hires or multicolour sprites, FLI bug area black, MUFLI Editor format. */
    MUFLI(false, false, true, "muf"),
    /** Two MUFLI frames shown alternately, MUIFLI Editor format. */
    MUIFLI(false, true, true, "mui");

    public final boolean multicolor;
    public final boolean interlaced;
    /** Sprite layer under the bitmap (NUFLI, MUFLI, MUIFLI): results are written as editor files. */
    public final boolean underlay;
    /** Extension of the raw / editor file. */
    public final String rawExtension;

    FliMode(boolean mc, boolean il, boolean underlay, String ext) {
        this.multicolor = mc;
        this.interlaced = il;
        this.underlay = underlay;
        this.rawExtension = ext;
    }

    /** Pixel width of one frame (multicolor 160, hires 320). */
    public int frameWidth() { return multicolor ? 160 : 320; }

    /** IFLI shows its second frame one pixel to the right. */
    public boolean shiftedSecondFrame() { return this == IFLI; }

    @Override public String toString() { return de.c64gfx.swing.I18n.tr("mode." + name()); }
}
