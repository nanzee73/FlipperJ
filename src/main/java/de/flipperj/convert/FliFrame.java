package de.flipperj.convert;

/**
 * Data of one FLI frame as it sits in C64 memory.
 * <ul>
 *   <li>bitmap: 8000 bytes (standard bitmap layout, 25 rows × 40 cells × 8 bytes)</li>
 *   <li>screens: 8 screen RAMs of 1024 bytes; picture line y uses screen (y &amp; 7)</li>
 *   <li>colorRam: 1000 nibbles (multicolor only)</li>
 *   <li>background: $D021</li>
 * </ul>
 */
public final class FliFrame {
    public final byte[] bitmap = new byte[8000];
    public final byte[][] screens = new byte[8][1024];
    public final byte[] colorRam = new byte[1000];
    public int background;

    /**
     * Last picture line that can be a bad line (raster line $F7 when the picture starts at $33).
     * Lines 197..199 ($F8..$FA) reuse the colors of line 196.
     */
    public static final int LAST_FLI_LINE = 196;

    /** Index of the screen whose data is actually displayed in picture line y. */
    public static int screenForLine(int y) { return Math.min(y, LAST_FLI_LINE) & 7; }

    /** Line group: lines 196..199 share the colors of line 196. */
    public static int lineGroup(int y) { return Math.min(y, LAST_FLI_LINE); }

    public int screenByte(int y, int cellX) {
        return screens[screenForLine(y)][(y >> 3) * 40 + cellX] & 0xFF;
    }

    public int bitmapByte(int y, int cellX) {
        return bitmap[(y >> 3) * 320 + cellX * 8 + (y & 7)] & 0xFF;
    }
}
