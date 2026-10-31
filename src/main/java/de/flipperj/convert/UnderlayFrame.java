package de.flipperj.convert;

/**
 * One NUFLI or MUFLI frame as the converter builds it: a hires FLI bitmap (two colours per 8×2 cell) over a layer
 * of sprites.
 * <ul>
 *   <li>Cells: 40 columns × 100 line pairs (lines 2k and 2k+1), {@link #fg} for set bitmap bits, {@link #bg} for
 *       cleared ones. Set bits are in front of the sprites, so a sprite shows only where the bitmap bit is 0.</li>
 *   <li>Six x-expanded sprites ("strips" 1..6, 48 pixels wide each) cover cell columns 3..38. Their colour can
 *       change every line pair; here a pair is lines 2p−1 and 2p (pair 0 is line 0 alone), as in the editors.
 *       MUFLI strips can be multicolour per line pair (4-pixel units, colours {@link #mc1}, own, {@link #mc3}).</li>
 *   <li>NUFLI only: the FLI bug area (columns 0..2) has its own hires sprite ({@link #bugHires}) and a multicolour
 *       sprite ({@link #bugMc1}, {@link #bugMc2}, {@link #bugMc3}); in lines y with (y &amp; 6) != 0 the cells there
 *       show light grey. MUFLI shows black in the first three columns.</li>
 * </ul>
 * {@link #sprite} holds the sprite layer per pixel: hires strips 0/1, multicolour strips 0..3, in the NUFLI bug area
 * bit 0 = hires sprite and bits 1..2 = multicolour value.
 */
public final class UnderlayFrame {
    public static final int W = 320, H = 200, PAIRS = 101;

    public final boolean nufli;
    public final int[] fg = new int[100 * 40], bg = new int[100 * 40];
    public final boolean[] bits = new boolean[W * H];
    public final int[] sprite = new int[W * H];
    /** [strip 1..6][pair 0..100]; index 0 unused. */
    public final int[][] stripColour = new int[7][PAIRS];
    /** MUFLI: strip uses multicolour in this pair. */
    public final boolean[][] multicolour = new boolean[7][PAIRS];
    public int mc1, mc3;
    public int bugHires, bugMc1, bugMc2, bugMc3;

    public UnderlayFrame(boolean nufli) { this.nufli = nufli; }

    /** Strip 1..6 of a cell column, 0 for none. */
    public static int strip(int cx) { return cx >= 3 && cx <= 38 ? (cx - 3) / 6 + 1 : 0; }

    /** Sprite colour pair of a line. */
    public static int pair(int y) { return (y + 1) >> 1; }

    public static int cell(int y, int cx) { return (y >> 1) * 40 + cx; }

    /** NUFLI: the bug area shows light grey for the cell colours in this line. */
    public static boolean greyBugLine(int y) { return (y & 6) != 0; }

    /** Palette index the VIC shows at (x, y), following the same rules as the file readers. */
    public int colour(int x, int y) {
        int cx = x >> 3;
        if (!nufli && cx < 3) return 0;
        int i = y * W + x;
        int k = cell(y, cx);
        boolean grey = nufli && cx < 3 && greyBugLine(y);
        int f = grey ? 15 : fg[k], b = grey ? 15 : bg[k];
        if (bits[i]) return f;
        int s = sprite[i];
        if (nufli && cx < 3) {
            if ((s & 1) != 0) return bugHires;
            return switch (s >> 1) {
                case 1 -> bugMc1;
                case 2 -> bugMc2;
                case 3 -> bugMc3;
                default -> b;
            };
        }
        int st = strip(cx);
        if (st == 0 || s == 0) return b;
        int p = pair(y);
        if (multicolour[st][p]) return switch (s) {
            case 1 -> mc1;
            case 2 -> stripColour[st][p];
            default -> mc3;
        };
        return stripColour[st][p];
    }
}
