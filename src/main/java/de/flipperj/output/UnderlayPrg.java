package de.flipperj.output;

import de.c64gfx.prg.Asm6502;
import de.flipperj.convert.UnderlayFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runnable .prg for NUFLI and MUFLI pictures with FlipperJ's own displayer (LOAD"PIC",8 : RUN). MUFLI: sprite 0 is
 * black over the FLI bug area, the strips can switch to multicolour per line pair ($D01C).
 * <pre>
 *   $0801        BASIC line "SYS 2061", then the displayer (setup and one block of code per line pair)
 *   $2000-$79FF  picture: screens, bitmaps and sprite blocks as in a NUFLI file, but the sprites are not crunched
 * </pre>
 * How it works (PAL, 63 cycles per line, all 8 sprites on, so the CPU always stops from cycle 55 to cycle 10):
 * <ul>
 *   <li>Every even picture line from line 2 on is forced into a late bad line by writing $D011 in cycle 14
 *       (new screen data every second line, FLI bug in the first three columns). Line 0 is a regular bad line.
 *       Lines 198/199 cannot be bad lines and keep the screen data of lines 196/197.</li>
 *   <li>In each odd line the CPU has cycles 11-54: it sets the colours of the six sprite strips that change for this
 *       line pair (strip i before cycle 20 + 6·(i−1), when its sprite starts), selects the screen of the next line pair
 *       in $D018 (screen data and sprite pointers), and loads the $D011 value for the next bad line into X.</li>
 *   <li>Sprites are y-expanded and started at raster 45; when a sprite has shown its 21 rows the VIC starts it again
 *       because its Y register has been set to that line (rasters 87, 129, 171, 213).</li>
 *   <li>Top half (lines 0-127) VIC bank 1, bottom half bank 0, switched in line 127.</li>
 * </ul>
 */
public final class UnderlayPrg {
    private UnderlayPrg() {}

    /** Restart lines of the sprites after the start at raster 45. */
    private static final int[] RESTART = {87, 129, 171, 213};
    /** Line pairs whose odd line sets the Y registers for the next restart (one sprite per pair). */
    private static final int[] Y_PAIRS = {20, 41, 66};
    /** Line pair whose odd line (127) switches to VIC bank 0. */
    private static final int BANK_PAIR = 64;

    /** How many strip colours may change in line pair p (p = 1..100) so the displayer has time for all writes. */
    public static int changeCapacity(int p) {
        int cap = 6;
        if (p == BANK_PAIR) cap--;
        for (int start : Y_PAIRS) if (p >= start && p < start + 8) cap--;
        if (regularBadLine(2 * p)) cap--;
        return cap;
    }

    /**
     * Lines 0, 8, 16, … are regular bad lines (RC starts at 0, as in a normal bitmap); $D011 must be written before
     * cycle 14 of that line, so the odd line before ends with an STX that the sprite fetches stop half-way.
     */
    private static boolean regularBadLine(int y) { return y % 8 == 0 && y <= 196; }

    /**
     * Last cycle in which the colour of strip st (1..6) may be written for this line: a write in cycle w changes the
     * colour from picture x = 8w − 136 on (measured in VICE), strip st starts at x = 24 + 48·(st−1).
     */
    static int stripDeadline(int st) { return 20 + 6 * (st - 1); }

    /** Raster line of picture line y. */
    private static int raster(int y) { return 51 + y; }

    /** $D018 for the cell pair of line y: screen and bitmap $2000 in the current bank. */
    private static int d018(int y) {
        int k = y < 128 ? ((~y) >> 1) & 7 : 8 + ((((y >> 1) + (y >= 168 ? 1 : 0)) & 3));
        return (k << 4) | 0x08;
    }

    private static int d011(int y) { return 0x38 | (raster(y) & 7); }

    /** Multicolour bits of the strips in $D01C for line pair p (MUFLI); NUFLI: only sprite 7. */
    private static int mcMask(UnderlayFrame f, int p) {
        if (f.nufli) return 0x80;
        int m = 0;
        for (int st = 1; st <= 6; st++) if (f.multicolour[st][p]) m |= 1 << st;
        return m;
    }

    /** Cycle in which the first instruction of the odd line of pair p counts as started. */
    private static int start(int p) {
        return p >= 1 && regularBadLine(2 * p - 2) ? 10 : 11;   // after a regular bad line: opcode fetched in cycle 11
    }

    /** How the odd line of pair p (< 100) ends. */
    private enum End { REGULAR, BANK, FORCED, NONE }

    private static End end(int p) {
        if (p == BANK_PAIR) return End.BANK;
        if (2 * p > 196) return End.NONE;
        return regularBadLine(2 * p) ? End.REGULAR : End.FORCED;
    }

    /** All writes of the odd line of pair p (1..100): {register, value, deadline}, in the order they are made. */
    private static List<int[]> writes(UnderlayFrame f, int p) {
        List<int[]> w = new ArrayList<>();
        int flagDeadline = Integer.MAX_VALUE;
        for (int st = 1; st <= 6; st++) {
            if (f.stripColour[st][p] != f.stripColour[st][p - 1])
                w.add(new int[]{0xD027 + st, f.stripColour[st][p], stripDeadline(st)});
            if (f.multicolour[st][p] != f.multicolour[st][p - 1]) flagDeadline = Math.min(flagDeadline, stripDeadline(st));
        }
        if (flagDeadline != Integer.MAX_VALUE) w.add(new int[]{0xD01C, mcMask(f, p), flagDeadline});
        w.sort(Comparator.comparingInt(x -> x[2]));
        if (p == 100) return w;
        End e = end(p);
        int last = e == End.FORCED ? 52 : e == End.NONE ? 54 : 49;
        if (e == End.BANK) w.add(new int[]{0xD011, d011(2 * p), last});   // regular bad line in 128: written early
        for (int k = 0; k < Y_PAIRS.length; k++)
            if (p >= Y_PAIRS[k] && p < Y_PAIRS[k] + 8) w.add(new int[]{0xD001 + 2 * (p - Y_PAIRS[k]), RESTART[k + 1], last});
        w.add(new int[]{0xD018, d018(2 * p), last});
        return w;
    }

    private static int padTarget(int p) {
        return switch (end(p)) {
            case REGULAR, BANK -> 50;
            case FORCED -> 53;
            case NONE -> 55;
        };
    }

    /** True if the displayer can make all writes of line pair p (1..100) in time. */
    public static boolean fits(UnderlayFrame f, int p) {
        Line l = new Line(null, p, start(p));
        try {
            for (int[] w : writes(f, p)) l.write(w[0], w[1], w[2]);
            if (p < 100) l.padTo(padTarget(p));
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static byte[] build(UnderlayFrame f) {
        Asm6502 a = new Asm6502(0x0801);
        a.bytes(0x0B, 0x08, 0x0A, 0x00, 0x9E, '2', '0', '6', '1', 0x00, 0x00, 0x00);
        a.jmp("main");
        a.label("main");
        a.sei();
        a.ldaImm(0x7F).staAbs(0xDC0D).staAbs(0xDD0D).ldaAbs(0xDC0D).ldaAbs(0xDD0D);
        a.ldaImmLow("nmi").staAbs(0x0318).ldaImmHigh("nmi").staAbs(0x0319);
        a.ldaImm(0).staAbs(0xD01A);
        a.ldaImm(0xFF).staAbs(0xD019);
        a.bytes(0xA0, 0x00);                          // LDY #0: STA abs,Y is a 5-cycle STA
        a.ldaAbs(0xDD02).oraImm(0x03).staAbs(0xDD02);
        a.ldaImm(0).staAbs(0xD020).staAbs(0xD021);
        a.ldaImm(0x08).staAbs(0xD016);
        a.ldaImm(0xFF).staAbs(0xD015).staAbs(0xD017).staAbs(0xD01B);
        a.ldaImm(0x7E).staAbs(0xD01D);
        int[] x = {24, 48, 96, 144, 192, 240, 288, 24};   // sprite X 24 = first pixel of the picture
        int msb = 0;
        for (int i = 0; i < 8; i++) {
            a.ldaImm(x[i] & 0xFF).staAbs(0xD000 + 2 * i);
            if (x[i] > 255) msb |= 1 << i;
        }
        a.ldaImm(msb).staAbs(0xD010);
        if (f.nufli) {
            a.ldaImm(f.bugMc1).staAbs(0xD025);
            a.ldaImm(f.bugMc3).staAbs(0xD026);
            a.ldaImm(f.bugHires).staAbs(0xD027);
            a.ldaImm(f.bugMc2).staAbs(0xD02E);
        } else {                                      // MUFLI: sprite 0 black over the FLI bug area
            a.ldaImm(f.mc1).staAbs(0xD025);
            a.ldaImm(f.mc3).staAbs(0xD026);
            a.ldaImm(0).staAbs(0xD027).staAbs(0xD02E);
        }

        a.label("frame");
        a.label("w1").bitAbs(0xD011).bpl("w1");     // raster >= 256
        a.label("w2").bitAbs(0xD011).bmi("w2");     // line 0
        a.ldaImm(0x96).staAbs(0xDD00);               // VIC bank 1
        a.ldaImm(0x38).staAbs(0xD011);               // YSCROLL 0: raster 48 is a regular bad line
        a.ldaImm(45);
        for (int i = 0; i < 8; i++) a.staAbs(0xD001 + 2 * i);
        for (int st = 1; st <= 6; st++) a.ldaImm(f.stripColour[st][0]).staAbs(0xD027 + st);
        a.ldaImm(mcMask(f, 0)).staAbs(0xD01C);
        a.ldaImm(46);
        a.label("w3").cmpAbs(0xD012).bne("w3");
        a.ldaImm(RESTART[0]);                         // sprites started in 45: next start when they end
        for (int i = 0; i < 8; i++) a.staAbs(0xD001 + 2 * i);
        a.ldaImm(49);
        a.label("w4").cmpAbs(0xD012).bne("w4");
        a.ldaImm(0x39).staAbs(0xD011);               // late bad line in 49: CPU stops, goes on in line 50 at cycle 11

        // line 50 (picture line -1): screen of lines 0/1, regular bad line in line 0
        Line l = new Line(a, 0, 11);
        l.write(0xD018, d018(0), 49);
        l.padTo(50);
        l.ldx(d011(0));
        a.bytes(0x8E, 0x11, 0xD0);                    // STX $D011 in cycles 52-55: regular bad line in line 0
        for (int p = 1; p <= 100; p++) {
            l = new Line(a, p, start(p));
            for (int[] w : writes(f, p)) l.write(w[0], w[1], w[2]);
            if (p == 100) { l.padTo(l.t); break; }    // line 199: done
            l.padTo(padTarget(p));
            switch (end(p)) {
                case BANK -> {                         // VIC bank 0 between the last bitmap fetch of line 127 (54)
                    l.ldx(0x97);                       // and the first sprite pointer fetch for line 128 (58)
                    a.bytes(0x8E, 0x00, 0xDD);        // STX $DD00 in cycles 52-55
                }
                case REGULAR -> {
                    l.ldx(d011(2 * p));
                    a.bytes(0x8E, 0x11, 0xD0);        // STX $D011 in cycles 52-55 (writes go on while BA is low)
                }
                case FORCED -> {
                    l.ldx(d011(2 * p));
                    a.bytes(0x8E, 0x11, 0xD0);        // even line: STX $D011 in cycles 11-14, then the bad line
                }
                case NONE -> new Line(a, p, 11).padTo(55);   // line 198 has no bad line: wait for line 199
            }
        }
        a.jmp("frame");
        a.label("nmi").rti();
        if (a.pc() > 0x2000) throw new IllegalStateException("Displayer too large: $" + Integer.toHexString(a.pc()));
        a.org(0x2000);
        byte[] pic = UnderlayFile.forDisplayer(f).bytes();
        a.bytes(java.util.Arrays.copyOfRange(pic, 2, pic.length));
        byte[] body = a.assemble();
        byte[] prg = new byte[body.length + 2];
        prg[0] = 0x01;
        prg[1] = 0x08;
        System.arraycopy(body, 0, prg, 2, body.length);
        return prg;
    }

    /**
     * Code for one raster line that starts in cycle 11 (after the sprite fetches), with the cycle of every write
     * checked against its deadline. Y is 0 all the time, so STA abs,Y is an STA that takes one cycle more.
     */
    private static final class Line {
        private final Asm6502 a;
        private final int pair;
        private int t;
        private int acc = -1;
        /** {kind 0 = LDA #, 1 = STA abs; value or register; deadline; slow (STA abs,Y)} */
        private final List<int[]> ops = new ArrayList<>();

        /** @param start cycle in which the first instruction counts as started (10: its opcode came before the stop) */
        Line(Asm6502 a, int pair, int start) {
            this.a = a;
            this.pair = pair;
            this.t = this.start = start;
        }

        void write(int reg, int value, int deadline) {
            if (acc != value) {
                ops.add(new int[]{0, value, 0, 0});
                acc = value;
            }
            ops.add(new int[]{1, reg, deadline, 0});
            t = check();
        }

        /** Cycle after the last op; throws if a write misses its deadline. */
        private int check() {
            int c = t0();
            for (int[] op : ops) {
                c += op[0] == 0 ? 2 : 4 + op[3];
                if (op[0] == 1 && c - 1 > op[2])
                    throw new IllegalStateException("Line pair " + pair + ": write to $" + Integer.toHexString(op[1])
                            + " in cycle " + (c - 1) + ", needed by " + op[2]);
            }
            return c;
        }

        private int start;

        private int t0() { return start; }

        /** Pads up to (not including) the given cycle and emits the code so far. */
        void padTo(int cycle) {
            int n = cycle - t;
            if (n < 0) throw new IllegalStateException("Line pair " + pair + ": cycle " + t + " > " + cycle);
            if (n == 1) {                              // the last STA that has room becomes STA abs,Y
                boolean done = false;
                for (int i = ops.size() - 1; i >= 0 && !done; i--) {
                    if (ops.get(i)[0] != 1) continue;
                    ops.get(i)[3] = 1;
                    try { check(); done = true; } catch (IllegalStateException e) { ops.get(i)[3] = 0; }
                }
                if (!done) throw new IllegalStateException("Line pair " + pair + ": cannot pad one cycle");
                n = 0;
            }
            for (int[] op : ops) {
                if (a == null) continue;               // only checking
                if (op[0] == 0) a.ldaImm(op[1]);
                else if (op[3] == 1) a.bytes(0x99, op[1] & 0xFF, op[1] >> 8);
                else a.staAbs(op[1]);
            }
            ops.clear();
            if (a != null) {
                if ((n & 1) == 1) { a.bytes(0x24, 0x00); n -= 3; }     // BIT $00: 3 cycles
                for (; n > 0; n -= 2) a.nop();
            }
            t = start = cycle;
        }

        void ldx(int value) {
            if (a != null) a.ldxImm(value);
            t += 2;
            start = t;
        }
    }
}
