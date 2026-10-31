package de.flipperj.convert;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.dither.Dither;
import de.c64gfx.dither.Ditherer;
import de.c64gfx.palette.Palette;
import de.c64gfx.format.C64Formats;
import de.c64gfx.format.C64Picture;
import de.c64gfx.swing.ImageAdjust;
import de.flipperj.output.UnderlayFile;

import java.util.stream.IntStream;

/**
 * NUFLI, MUFLI and MUIFLI: hires FLI (two colours per 8×2 cell) over a layer of x-expanded sprites that adds a third
 * colour per 48-pixel strip and line pair (see {@link UnderlayFrame}).
 * <ol>
 *   <li>Colour selection, alternating: cell colours given the sprite colours, then sprite colours given the cells
 *       (MUFLI also decides per strip and line pair between hires and multicolour sprites).</li>
 *   <li>Dithering where every pixel may use its cell's two colours and the sprite colours of its line.</li>
 *   <li>Encoding: per sprite unit (2 or 4 pixels × 2 lines) the sprite value that serves the dithered pixels best,
 *       then per pixel the bitmap bit (set bits cover the sprite).</li>
 * </ol>
 */
public final class UnderlayConverter {
    private static final int W = 320, H = 200;

    private UnderlayConverter() {}

    /** Result of one frame: data and the palette indices it shows. */
    record Out(UnderlayFrame frame, int[] idx) {}

    /** Converts and writes the editor file; the preview is what reading that file gives. */
    static ConversionResult convert(float[] t320, ConvertSettings s, ColorMatcher m, FliConverter.Progress p) {
        byte[] file;
        switch (s.mode()) {
            case NUFLI -> file = UnderlayFile.build(convertFrame(t320, s, m, true, p, 0, 1).frame()).bytes();
            case MUFLI -> file = UnderlayFile.build(convertFrame(t320, s, m, false, p, 0, 1).frame()).bytes();
            default -> {
                Out[] ab = convertMuifli(t320, s, m, s.sourcePalette(), p);
                file = UnderlayFile.muifli(UnderlayFile.build(ab[0].frame()).bytes(), UnderlayFile.build(ab[1].frame()).bytes());
            }
        }
        try {
            C64Picture pic = C64Formats.read("picture." + s.mode().rawExtension, file);
            int[][] idx = pic.frame2() == null ? new int[][]{pic.frame1()} : new int[][]{pic.frame1(), pic.frame2()};
            p.update("Done", 1.0);
            return new ConversionResult(s.mode(), file, idx, t320);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Written " + s.mode() + " file cannot be read back: " + e.getMessage(), e);
        }
    }

    public static Out convertFrame(float[] t320, ConvertSettings s, ColorMatcher m, boolean nufli,
                                   FliConverter.Progress p, double p0, double p1) {
        float[] target = t320;
        Out best = null;
        double bestErr = Double.MAX_VALUE;
        int n = s.dither() == Dither.NONE ? 1 : Math.max(1, s.passes());
        for (int pass = 0; pass < n && !p.cancelled(); pass++) {
            p.update("Selecting colours (pass " + (pass + 1) + "/" + n + ")", p0 + (p1 - p0) * pass / n);
            float[] et = m.errorTable(target);
            UnderlayFrame f = new UnderlayFrame(nufli);
            selectColours(f, et);
            p.update("Dithering", p0 + (p1 - p0) * (pass + 0.6) / n);
            float[] seen = new float[W * H * 3];
            int[] wanted = Ditherer.run(t320, W, H, m, s.sourcePalette(),
                    new Ditherer.Settings(s.dither(), s.ditherStrength(), s.serpentine(), s.bleeding()),
                    new Ditherer.Cells(allowed(f), 8, 1, 8, 8), seen);
            encode(f, m.errorTable(seen), wanted);
            int[] idx = new int[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) idx[y * W + x] = f.colour(x, y);
            double err = FliConverter.blurredError(t320, idx, W, s.sourcePalette());
            if (err < bestErr) { bestErr = err; best = new Out(f, idx); }
            target = seen;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ colours

    private static float e(float[] et, int x, int y, int c) { return et[(y * W + x) * 16 + c]; }

    /** Colours a pixel can get from the sprite layer (without the cell colours). */
    private static int[] underColours(UnderlayFrame f, int x, int y) {
        int cx = x >> 3;
        if (f.nufli && cx < 3) return new int[]{f.bugHires, f.bugMc1, f.bugMc2, f.bugMc3};
        int st = UnderlayFrame.strip(cx);
        if (st == 0 || (!f.nufli && cx < 3)) return new int[0];
        int p = UnderlayFrame.pair(y);
        if (f.multicolour[st][p]) return new int[]{f.mc1, f.stripColour[st][p], f.mc3};
        return new int[]{f.stripColour[st][p]};
    }

    private static boolean fixedGrey(UnderlayFrame f, int y, int cx) {
        return f.nufli && cx < 3 && UnderlayFrame.greyBugLine(y);
    }

    private static void selectColours(UnderlayFrame f, float[] et) {
        // start: sprite colours from the nearest colours, no sprites in the bug area yet
        for (int st = 1; st <= 6; st++) java.util.Arrays.fill(f.stripColour[st], 0);
        chooseCells(f, et, false);
        for (int round = 0; round < 3; round++) {
            chooseStrips(f, et);
            if (!f.nufli) chooseMulticolour(f, et);
            else chooseBugSprites(f, et);
            chooseCells(f, et, true);
        }
    }

    /** Best two colours per 8×2 cell, given the sprite colours (if withSprites). */
    private static void chooseCells(UnderlayFrame f, float[] et, boolean withSprites) {
        IntStream.range(0, 100 * 40).parallel().forEach(k -> {
            int r = k / 40, cx = k % 40;
            int y0 = r * 2;
            if (!f.nufli && cx < 3) { f.fg[k] = 0; f.bg[k] = 0; return; }
            if (fixedGrey(f, y0, cx)) { f.fg[k] = 15; f.bg[k] = 15; return; }
            float[] under = new float[16];                   // best sprite colour error per pixel
            for (int ly = 0; ly < 2; ly++)
                for (int px = 0; px < 8; px++) {
                    float u = Float.MAX_VALUE;
                    if (withSprites)
                        for (int c : underColours(f, cx * 8 + px, y0 + ly)) u = Math.min(u, e(et, cx * 8 + px, y0 + ly, c));
                    under[ly * 8 + px] = u;
                }
            float bestC = Float.MAX_VALUE;
            int ba = 0, bb = 0;
            for (int a = 0; a < 16; a++)
                for (int b = a; b < 16; b++) {
                    float c = 0;
                    for (int ly = 0; ly < 2 && c < bestC; ly++)
                        for (int px = 0; px < 8; px++) {
                            int x = cx * 8 + px, y = y0 + ly;
                            c += Math.min(Math.min(e(et, x, y, a), e(et, x, y, b)), under[ly * 8 + px]);
                        }
                    if (c < bestC) { bestC = c; ba = a; bb = b; }
                }
            f.fg[k] = ba;
            f.bg[k] = bb;
        });
    }

    private static float cellError(UnderlayFrame f, float[] et, int x, int y) {
        int k = UnderlayFrame.cell(y, x >> 3);
        return Math.min(e(et, x, y, f.fg[k]), e(et, x, y, f.bg[k]));
    }

    private static int[] pairLines(int p) {
        if (p == 0) return new int[]{0};
        if (p == 100) return new int[]{199};
        return new int[]{2 * p - 1, 2 * p};
    }

    /** One colour per strip and line pair (hires sprites). */
    private static void chooseStrips(UnderlayFrame f, float[] et) {
        IntStream.range(0, 6 * UnderlayFrame.PAIRS).parallel().forEach(k -> {
            int st = k / UnderlayFrame.PAIRS + 1, p = k % UnderlayFrame.PAIRS;
            int x0 = 24 + (st - 1) * 48;
            float bestC = Float.MAX_VALUE;
            int best = 0;
            for (int c = 0; c < 16; c++) {
                float sum = 0;
                for (int y : pairLines(p))
                    for (int x = x0; x < x0 + 48; x++) sum += Math.min(cellError(f, et, x, y), e(et, x, y, c));
                if (sum < bestC) { bestC = sum; best = c; }
            }
            f.stripColour[st][p] = best;
            f.multicolour[st][p] = false;
        });
    }

    /** MUFLI: the two shared multicolour colours, then per strip and pair whether multicolour is better. */
    private static void chooseMulticolour(UnderlayFrame f, float[] et) {
        // current error per strip pixel with hires sprites
        float[] cur = new float[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 24; x < 312; x++) {
                int st = UnderlayFrame.strip(x >> 3);
                cur[y * W + x] = Math.min(cellError(f, et, x, y), e(et, x, y, f.stripColour[st][UnderlayFrame.pair(y)]));
            }
        double bestGain = 0;
        int b1 = 0, b3 = 0;
        for (int a = 0; a < 16; a++)
            for (int b = a + 1; b < 16; b++) {
                final int ca = a, cb = b;
                double gain = IntStream.range(0, H).parallel().mapToDouble(y -> {
                    double g = 0;
                    for (int x = 24; x < 312; x++)
                        g += Math.max(0, cur[y * W + x] - Math.min(e(et, x, y, ca), e(et, x, y, cb)));
                    return g;
                }).sum();
                if (gain > bestGain) { bestGain = gain; b1 = a; b3 = b; }
            }
        f.mc1 = b1;
        f.mc3 = b3;
        // multicolour per strip and pair: 3 sprite colours, but 4-pixel units (penalty for the coarser pixels)
        IntStream.range(0, 6 * UnderlayFrame.PAIRS).parallel().forEach(k -> {
            int st = k / UnderlayFrame.PAIRS + 1, p = k % UnderlayFrame.PAIRS;
            int x0 = 24 + (st - 1) * 48;
            float hires = 0;
            for (int y : pairLines(p)) for (int x = x0; x < x0 + 48; x++) hires += cur[y * W + x];
            float bestC = Float.MAX_VALUE;
            int best = 0;
            for (int c = 0; c < 16; c++) {
                float sum = 0;
                for (int y : pairLines(p))
                    for (int x = x0; x < x0 + 48; x += 4) {
                        // per 4-pixel unit one sprite value: best common choice
                        float u = Float.MAX_VALUE;
                        for (int v : new int[]{-1, f.mc1, c, f.mc3}) {
                            float s = 0;
                            for (int i = 0; i < 4; i++) {
                                float ce = cellError(f, et, x + i, y);
                                s += v < 0 ? ce : Math.min(e(et, x + i, y, f.fg[UnderlayFrame.cell(y, (x + i) >> 3)]), e(et, x + i, y, v));
                            }
                            u = Math.min(u, s);
                        }
                        sum += u;
                    }
                if (sum < bestC) { bestC = sum; best = c; }
            }
            if (bestC < hires * 0.97f) {
                f.multicolour[st][p] = true;
                f.stripColour[st][p] = best;
            }
        });
    }

    /** NUFLI: one hires and three multicolour sprite colours for the FLI bug area (whole picture). */
    private static void chooseBugSprites(UnderlayFrame f, float[] et) {
        int[] chosen = new int[4];
        java.util.Arrays.fill(chosen, -1);
        float[] cur = new float[24 * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < 24; x++)
                cur[y * 24 + x] = fixedGrey(f, y, x >> 3) ? e(et, x, y, 15) : cellError(f, et, x, y);
        for (int n = 0; n < 4; n++) {
            double bestGain = -1;
            int best = 0;
            for (int c = 0; c < 16; c++) {
                double g = 0;
                for (int y = 0; y < H; y++)
                    for (int x = 0; x < 24; x++) g += Math.max(0, cur[y * 24 + x] - e(et, x, y, c));
                if (g > bestGain) { bestGain = g; best = c; }
            }
            chosen[n] = best;
            for (int y = 0; y < H; y++)
                for (int x = 0; x < 24; x++) cur[y * 24 + x] = Math.min(cur[y * 24 + x], e(et, x, y, best));
        }
        f.bugHires = chosen[0];
        f.bugMc1 = chosen[1];
        f.bugMc2 = chosen[2];
        f.bugMc3 = chosen[3];
    }

    /** Colours each pixel may get from the ditherer: its cell's two and its line's sprite colours. */
    private static int[][] allowed(UnderlayFrame f) {
        int[][] a = new int[H * 40][];
        for (int y = 0; y < H; y++)
            for (int cx = 0; cx < 40; cx++) {
                java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
                if (!f.nufli && cx < 3) set.add(0);
                else {
                    int k = UnderlayFrame.cell(y, cx);
                    boolean grey = fixedGrey(f, y, cx);
                    set.add(grey ? 15 : f.fg[k]);
                    set.add(grey ? 15 : f.bg[k]);
                    for (int c : underColours(f, cx * 8, y)) set.add(c);
                }
                a[y * 40 + cx] = set.stream().mapToInt(Integer::intValue).toArray();
            }
        return a;
    }

    // ------------------------------------------------------------------------------------------------ encoding

    /** Sprite layer and bitmap bits that show the dithered picture as well as possible. */
    private static void encode(UnderlayFrame f, float[] es, int[] wanted) {
        // sprite units: 2 lines of a colour pair × 2 pixels (hires strips, NUFLI bug multicolour),
        // × 4 pixels (MUFLI multicolour), × 1 pixel (NUFLI bug hires sprite, chosen together with multicolour)
        IntStream.range(0, UnderlayFrame.PAIRS).parallel().forEach(p -> {
            int[] lines = pairLines(p);
            for (int x = 0; x < W; ) {
                int cx = x >> 3;
                if (!f.nufli && cx < 3) { x += 8; continue; }
                if (f.nufli && cx < 3) { bugUnit(f, es, lines, x); x += 2; continue; }
                int st = UnderlayFrame.strip(cx);
                if (st == 0) { x += 8; continue; }
                boolean mc = f.multicolour[st][p];
                int unit = mc ? 4 : 2;
                int[] values = mc ? new int[]{0, 1, 2, 3} : new int[]{0, 1};
                int bestV = 0;
                float bestC = Float.MAX_VALUE;
                for (int v : values) {
                    float c = 0;
                    for (int y : lines)
                        for (int i = 0; i < unit; i++) c += pixelCost(f, es, x + i, y, v);
                    if (c < bestC) { bestC = c; bestV = v; }
                }
                for (int y : lines) for (int i = 0; i < unit; i++) f.sprite[y * W + x + i] = bestV;
                x += unit;
            }
        });
        // bitmap bits: the cell's foreground where it is closer than what shows through
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                f.bits[i] = false;
                int under = f.colour(x, y);
                int cx = x >> 3;
                if (!f.nufli && cx < 3) continue;
                int fgc = fixedGrey(f, y, cx) ? 15 : f.fg[UnderlayFrame.cell(y, cx)];
                f.bits[i] = es[i * 16 + fgc] < es[i * 16 + under];
            }
    }

    /** Cost of a pixel with sprite value v: the better of the foreground (bit set) and what v shows. */
    private static float pixelCost(UnderlayFrame f, float[] es, int x, int y, int v) {
        int i = y * W + x;
        int old = f.sprite[i];
        boolean oldBit = f.bits[i];
        f.sprite[i] = v;
        f.bits[i] = false;
        int under = f.colour(x, y);
        f.sprite[i] = old;
        f.bits[i] = oldBit;
        int cx = x >> 3;
        int fgc = fixedGrey(f, y, cx) ? 15 : f.fg[UnderlayFrame.cell(y, cx)];
        return Math.min(es[i * 16 + fgc], es[i * 16 + under]);
    }

    /** NUFLI bug area, 2 pixels × 2 lines: multicolour value shared, hires sprite bit per pixel column. */
    private static void bugUnit(UnderlayFrame f, float[] es, int[] lines, int x) {
        float bestC = Float.MAX_VALUE;
        int bestV = 0;
        for (int v = 0; v < 16; v++) {                     // bits 0/1: hires sprite in pixel x / x+1, 2..3: mc
            int mc = v >> 2;
            float c = 0;
            for (int y : lines)
                for (int i = 0; i < 2; i++) c += pixelCost(f, es, x + i, y, (v >> i & 1) | (mc << 1));
            if (c < bestC) { bestC = c; bestV = v; }
        }
        for (int y : lines)
            for (int i = 0; i < 2; i++) f.sprite[y * W + x + i] = (bestV >> i & 1) | ((bestV >> 2) << 1);
    }

    // ------------------------------------------------------------------------------------------------ MUIFLI

    /** MUIFLI: two MUFLI frames shown alternately; frame B aims at 2 × target − frame A (and back). */
    static Out[] convertMuifli(float[] t320, ConvertSettings s, ColorMatcher m, Palette pal, FliConverter.Progress p) {
        int rounds = Math.max(1, Math.min(4, s.passes()));
        Out a = convertFrame(t320, s, m, false, p, 0.0, 0.25);
        Out b = convertFrame(complement(t320, a, pal), s, m, false, p, 0.25, 0.5);
        double bestErr = mixError(t320, a, b, pal);
        Out bestA = a, bestB = b;
        for (int r = 1; r < rounds && !p.cancelled(); r++) {
            double base = 0.5 + 0.5 * (r - 1) / Math.max(1, rounds - 1);
            a = convertFrame(complement(t320, b, pal), s, m, false, p, base, base + 0.1);
            b = convertFrame(complement(t320, a, pal), s, m, false, p, base + 0.1, base + 0.2);
            double err = mixError(t320, a, b, pal);
            if (err < bestErr) { bestErr = err; bestA = a; bestB = b; }
        }
        return new Out[]{bestA, bestB};
    }

    private static float[] complement(float[] t, Out known, Palette pal) {
        float[] out = new float[t.length];
        for (int i = 0; i < W * H; i++) {
            int c = known.idx()[i];
            out[i * 3] = ImageAdjust.clamp(2 * t[i * 3] - pal.r(c));
            out[i * 3 + 1] = ImageAdjust.clamp(2 * t[i * 3 + 1] - pal.g(c));
            out[i * 3 + 2] = ImageAdjust.clamp(2 * t[i * 3 + 2] - pal.b(c));
        }
        return out;
    }

    private static double mixError(float[] t, Out a, Out b, Palette pal) {
        float[] mix = new float[t.length];
        for (int i = 0; i < W * H; i++) {
            int ca = a.idx()[i], cb = b.idx()[i];
            mix[i * 3] = (pal.r(ca) + pal.r(cb)) * 0.5f;
            mix[i * 3 + 1] = (pal.g(ca) + pal.g(cb)) * 0.5f;
            mix[i * 3 + 2] = (pal.b(ca) + pal.b(cb)) * 0.5f;
        }
        return FliConverter.blurredErrorRgb(t, mix, W);
    }
}
