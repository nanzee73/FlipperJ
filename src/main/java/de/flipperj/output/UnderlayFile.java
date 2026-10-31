package de.flipperj.output;

import de.flipperj.convert.UnderlayFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes NUFLI Editor (.nuf), MUFLI Editor (.muf) and MUIFLI Editor (.mui) files.
 * <p>
 * The layout is the one C64GfxLib reads ({@code C64Formats}); everything is placed so that reading the file gives
 * exactly the converter's picture, apart from sprite bytes that several lines have to share (see {@link #conflicts}).
 * <pre>
 *   NUFLI, load address $2000 ($2000-$79FF)
 *     $2000-$2FFF  4 screens for lines 128..199 (+ sprite colour tables in their unused parts)
 *     $3000-$33FF  left free for a displayer
 *     $3400-$3F3F  bitmap lines 128..199 (VIC bank 0, bitmap $2000)
 *     $3FF2-$3FF9  colours of the FLI bug area sprites
 *     $4000-$5FFF  8 screens for lines 0..127
 *     $6000-$73FF  bitmap lines 0..127 (VIC bank 1, bitmap $6000)
 *   MUFLI, load address $2100: the same picture data 256 bytes further into the file's start,
 *     sprite colour tables at $2400.., multicolour flags $3300.., multicolour sprite colours $3FF2/$3FF3.
 *   MUIFLI: two MUFLI frames in one file; parts of the second one at file offsets $5602 and $8002.
 * </pre>
 * Sprites are stretched to cover all 200 lines (sprite crunching in the displayer); a sprite pointer lives in
 * screen RAM, so each screen has its own sprite blocks. The blocks are chosen here so they don't overlap other
 * data and each other as far as possible.
 */
public final class UnderlayFile {
    public static final int NUFLI_SIZE = 23042, MUFLI_SIZE = 21826, MUIFLI_SIZE = 44034;

    /** Number of sprite bytes whose value had to be shared by lines that wanted different ones. */
    public int conflicts;

    private final boolean nufli;
    private final byte[] d;
    /** Who wrote each byte: 0 free, 1 fixed data, 2 kept free if possible ($3000-$33FF in NUFLI), 3 sprite data. */
    private final byte[] use;
    /** For sprite data: which sprite byte (slot, sprite row, column) it holds – lines of one pair share it. */
    private final int[] owner;
    private final int shift;

    private UnderlayFile(boolean nufli) {
        this.nufli = nufli;
        d = new byte[nufli ? NUFLI_SIZE : MUFLI_SIZE];
        use = new byte[d.length];
        owner = new int[d.length];
        shift = nufli ? 0 : 0x100;
        d[0] = 0;
        d[1] = (byte) (nufli ? 0x20 : 0x21);
        use[0] = use[1] = 1;
    }

    public static byte[] nufli(UnderlayFrame f) { return build(f).d; }

    public static byte[] mufli(UnderlayFrame f) { return build(f).d; }

    /** NUFLI or MUFLI file plus the number of shared-byte conflicts. */
    public static UnderlayFile build(UnderlayFrame f) {
        UnderlayFile u = new UnderlayFile(f.nufli);
        u.write(f);
        return u;
    }

    public byte[] bytes() { return d; }

    /** MUIFLI: frame 1 at the start, frame 2 split into the free space as the MUIFLI Editor stores it. */
    public static byte[] muifli(byte[] a, byte[] b) {
        byte[] out = new byte[MUIFLI_SIZE];
        System.arraycopy(a, 0, out, 0, MUFLI_SIZE);
        System.arraycopy(b, 2, out, 0x8002, 0x2c00);
        System.arraycopy(b, 0x2c02, out, 0x5602, 0x2900);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ layout

    /** File offset of the screen RAM used by picture line y (as in C64Formats). */
    private int screen(int y) {
        int s = y < 128 ? 0x2002 + ((((~y) >> 1) & 7) << 10) : 2 + (((((y >> 1) + (y >= 168 ? 1 : 0)) & 3)) << 10);
        return s - shift;
    }

    private int bitmapBase(int y) {
        if (nufli) return y < 128 ? 0x4002 : 2;
        return 0x3f02 - ((y & 128) << 7);
    }

    private static int spriteTable(int sprite) { return ((sprite & 6) << 9) + ((sprite & 1) << 7); }

    /** Line of a sprite block shown in picture line y (sprite crunching makes it wrap within the block). */
    static int spriteLine(int y, int column) {
        return (((y + 47) >> 1) * 3 + column + (y < 123 ? 0 : y < 165 ? 1 : 2)) & 63;
    }

    /** Sprite pointer slot offset in screen RAM: NUFLI 0..6 = sprite, 7 = multicolour sprite; MUFLI 1..6 = strip. */
    private int pointerOffset(int slot) {
        if (nufli) return slot == 7 ? 0x3ff : 0x3f8 + slot;
        return 0x3f9 + slot - 1;
    }

    private int blockBase(int y) {
        return (nufli ? 128 : 124) - ((y & 128) << 1);
    }

    private int maxBlock() { return nufli ? (d.length - 2) / 64 - 1 : 339; }

    private void fixed(int off, int value) {
        d[off] = (byte) value;
        use[off] = 1;
    }

    // ------------------------------------------------------------------------------------------------ writing

    private void write(UnderlayFrame f) {
        int x0 = nufli ? 0 : 3;
        // cells and bitmap
        for (int y = 0; y < 200; y++) {
            int s = screen(y), bm = bitmapBase(y);
            for (int cx = x0; cx < 40; cx++) {
                int k = UnderlayFrame.cell(y, cx);
                int off = (y >> 3) * 40 + cx;
                fixed(s + off, (f.fg[k] << 4) | (f.bg[k] & 15));
                int v = 0;
                for (int px = 0; px < 8; px++) if (f.bits[y * 320 + cx * 8 + px]) v |= 0x80 >> px;
                fixed(bm + (off << 3) + (y & 7), v);
            }
        }
        // colour tables
        int table = nufli ? 0x402 : 0x302;
        for (int st = 1; st <= 6; st++)
            for (int p = 0; p < UnderlayFrame.PAIRS; p++)
                fixed(table + spriteTable(st - 1) + p, f.stripColour[st][p] & 15);
        if (nufli) {
            fixed(0x1ff9, f.bugHires);
            fixed(0x1ff3, f.bugMc1);
            fixed(0x1ff8, f.bugMc2);
            fixed(0x1ff2, f.bugMc3);
            for (int i = 0x1002; i < 0x1402; i++) if (use[i] == 0) use[i] = 2;   // $3000-$33FF: displayer, if possible
        } else {
            for (int p = 0; p < UnderlayFrame.PAIRS; p++) {
                int v = 0;
                for (int st = 1; st <= 6; st++) if (f.multicolour[st][p]) v |= 1 << st;
                fixed(0x1202 + p, v);
            }
            fixed(0x1ef3, f.mc1);
            fixed(0x1ef2, f.mc3);
        }
        // reserve all pointer bytes before choosing blocks
        List<Integer> screens = new ArrayList<>();
        for (int y = 0; y < 200; y++) if (!screens.contains(screen(y))) screens.add(screen(y));
        int[] slots = nufli ? new int[]{0, 1, 2, 3, 4, 5, 6, 7} : new int[]{1, 2, 3, 4, 5, 6};
        for (int s : screens) for (int slot : slots) fixed(s + pointerOffset(slot), 0);
        // sprite blocks per screen and slot
        for (int s : screens)
            for (int slot : slots) {
                List<Integer> lines = new ArrayList<>();
                for (int y = 0; y < 200; y++) if (screen(y) == s) lines.add(y);
                int base = blockBase(lines.get(0));
                int block = chooseBlock(lines, base, slot);
                fixed(s + pointerOffset(slot), block - base);
                for (int y : lines)
                    for (int c = 0; c < 3; c++) {
                        int off = 2 + (block << 6) + spriteLine(y, c);
                        int v = spriteByte(f, y, slot, c);
                        if (use[off] == 3 && (d[off] & 0xFF) != v) conflicts++;
                        d[off] = (byte) v;
                        use[off] = 3;
                        owner[off] = id(slot, y, c);
                    }
            }
    }

    /** Sprite byte identity: the two lines of a colour pair (2p−1, 2p) show the same sprite row. */
    private static int id(int slot, int y, int c) { return 1 + (slot * 128 + UnderlayFrame.pair(y)) * 4 + c; }

    /**
     * The block (absolute, as in C64Formats) for these lines: no clash with other data, sharing bytes that hold the
     * same sprite row of the same sprite (so the sprites of all screens fit), otherwise as few new bytes as possible.
     */
    private int chooseBlock(List<Integer> lines, int base, int slot) {
        int best = -1;
        long bestCost = Long.MAX_VALUE;
        int from = Math.max(0, base), to = Math.min(maxBlock(), base + 255);
        for (int b = from; b <= to; b++) {
            if (2 + (b << 6) + 63 >= d.length) break;
            long cost = 0;
            for (int y : lines)
                for (int c = 0; c < 3 && cost < bestCost; c++) {
                    int off = 2 + (b << 6) + spriteLine(y, c);
                    int u = use[off];
                    if (u == 1) cost += 1_000_000;
                    else if (u == 2) cost += 5;             // usable, but better left to a displayer
                    else if (u == 3) cost += owner[off] == id(slot, y, c) ? 0 : 1000;
                    else cost += 1;                       // a new byte: prefer sharing
                }
            if (cost < bestCost) { bestCost = cost; best = b; }
        }
        return best;
    }

    /** Sprite byte of a slot shown in line y, byte column c (0..2), built from the per-pixel sprite layer. */
    private static int spriteByte(UnderlayFrame f, int y, int slot, int c) {
        int v = 0;
        int row = y * 320;
        if (f.nufli && slot == 0) {                       // hires sprite under the FLI bug area, not expanded
            for (int b = 0; b < 8; b++) if ((f.sprite[row + c * 8 + b] & 1) != 0) v |= 0x80 >> b;
            return v;
        }
        if (f.nufli && slot == 7) {                       // multicolour sprite under the FLI bug area
            for (int b = 0; b < 4; b++) v |= ((f.sprite[row + c * 8 + b * 2] >> 1) & 3) << (6 - 2 * b);
            return v;
        }
        int x0 = 24 + (slot - 1) * 48 + c * 16;           // x-expanded: one byte covers 16 pixels
        boolean mc = !f.nufli && f.multicolour[slot][UnderlayFrame.pair(y)];
        if (mc) {
            for (int b = 0; b < 4; b++) v |= (f.sprite[row + x0 + b * 4] & 3) << (6 - 2 * b);
        } else {
            for (int b = 0; b < 8; b++) if ((f.sprite[row + x0 + b * 2] & 1) != 0) v |= 0x80 >> b;
        }
        return v;
    }
}
