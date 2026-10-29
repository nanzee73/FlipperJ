package de.flipperj.convert;

/**
 * Renders FLI data the way the VIC-II displays it with the bundled displayer.
 * <p>
 * Model:
 * <ul>
 *   <li>Picture line 0 (raster line $33) is a regular bad line, so it has no FLI bug.</li>
 *   <li>Picture lines 1..196 are forced late bad lines: the first 3 cells read $FF as screen data
 *       (the FLI bug). For multicolor the color RAM there is assumed to be light grey (15) as well.</li>
 *   <li>Picture lines 197..199 (raster lines $F8..$FA) cannot trigger bad lines any more and show the
 *       colors of picture line 196.</li>
 * </ul>
 */
public final class VicRenderer {
    private VicRenderer() {}

    public static boolean isBugCell(int y, int cellX, boolean renderBug) {
        return renderBug && cellX < 3 && y >= 1;
    }

    /** Returns palette indices, width 160 (multicolor) or 320 (hires). */
    public static int[] render(FliFrame f, boolean multicolor, boolean renderBug) {
        int w = multicolor ? 160 : 320;
        int[] out = new int[w * 200];
        for (int y = 0; y < 200; y++) {
            for (int cx = 0; cx < 40; cx++) {
                int scr = f.screenByte(y, cx);
                int cram = f.colorRam[(y >> 3) * 40 + cx] & 15;
                if (isBugCell(y, cx, renderBug)) { scr = 0xFF; cram = 15; }
                int bits = f.bitmapByte(y, cx);
                if (multicolor) {
                    for (int p = 0; p < 4; p++) {
                        int v = (bits >> (6 - 2 * p)) & 3;
                        out[y * w + cx * 4 + p] = switch (v) {
                            case 0 -> f.background & 15;
                            case 1 -> scr >> 4;
                            case 2 -> scr & 15;
                            default -> cram;
                        };
                    }
                } else {
                    for (int p = 0; p < 8; p++) {
                        int v = (bits >> (7 - p)) & 1;
                        out[y * w + cx * 8 + p] = v == 1 ? scr >> 4 : scr & 15;
                    }
                }
            }
        }
        return out;
    }
}
