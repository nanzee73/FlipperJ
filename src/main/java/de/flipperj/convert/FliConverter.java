package de.flipperj.convert;

import de.c64gfx.dither.Dither;
import de.c64gfx.dither.Ditherer;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.swing.ImageAdjust;
import de.c64gfx.palette.Palette;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Converts a prepared 320×200 picture into FLI, AFLI or IFLI.
 * <p>
 * Per frame:
 * <ol>
 *   <li>Error table: distance of every pixel to all 16 colors.</li>
 *   <li>Color selection under the hardware constraints (global background, color RAM per cell,
 *       two screen colors per cell and raster line).</li>
 *   <li>Dithering where every pixel may only use the colors allowed in its cell/line.</li>
 *   <li>Optional further passes: color selection is repeated on the values the ditherer "wanted".
 *       The pass with the lowest (slightly blurred) error wins.</li>
 * </ol>
 */
public final class FliConverter {

    public interface Progress {
        void update(String message, double fraction);
        default boolean cancelled() { return false; }
        Progress NONE = (m, f) -> {};
    }

    /** Result of a single frame. */
    record FrameOut(FliFrame frame, int[] idx, float[] seen, int[] cram) {}

    private FliConverter() {}

    public static ConversionResult convert(float[] target320, ConvertSettings s, Progress p) {
        ColorMatcher m = s.matcher();
        List<FliFrame> frames = new ArrayList<>();
        switch (s.mode()) {
            case FLI -> {
                float[] t = ImageAdjust.halveWidth(target320, 320, 200);
                frames.add(convertMc(t, s, m, null, s.fixedBackground(), s.passes(), p, 0, 1).frame());
            }
            case AFLI -> frames.add(convertHires(target320, s, m, s.passes(), p).frame());
            case IFLI -> frames.addAll(convertIfli(target320, s, m, s.sourcePalette(), p));
            case NUFLI, MUFLI, MUIFLI -> { return UnderlayConverter.convert(target320, s, m, p); }
        }
        p.update("Done", 1.0);
        return new ConversionResult(s.mode(), frames, s.renderBug(), target320);
    }

    // ------------------------------------------------------------------ multicolor FLI

    static FrameOut convertMc(float[] t160, ConvertSettings s, ColorMatcher m, int[] fixedCram, int fixedBg,
                              int passes, Progress p, double p0, double p1) {
        final int W = 160;
        float[] target = t160;
        FrameOut best = null;
        double bestErr = Double.MAX_VALUE;
        int n = s.dither() == Dither.NONE ? 1 : Math.max(1, passes);
        for (int pass = 0; pass < n; pass++) {
            if (p.cancelled()) break;
            p.update("Selecting colours (pass " + (pass + 1) + "/" + n + ")", p0 + (p1 - p0) * pass / n);
            float[] et = m.errorTable(target);
            int bg = fixedBg >= 0 ? fixedBg : chooseBackgroundMc(et, s.renderBug(), fixedCram);
            int[] cram = new int[1000];
            int[] c1 = new int[200 * 40], c2 = new int[200 * 40];
            IntStream.range(0, 1000).parallel().forEach(cell ->
                    solveCellMc(et, cell % 40, cell / 40, bg, s.renderBug(),
                            fixedCram == null ? -1 : fixedCram[cell], cram, c1, c2, true));
            int[][] allowed = new int[200 * 40][];
            for (int y = 0; y < 200; y++)
                for (int cx = 0; cx < 40; cx++) {
                    int k = y * 40 + cx;
                    allowed[k] = VicRenderer.isBugCell(y, cx, s.renderBug())
                            ? new int[]{bg}
                            : new int[]{bg, c1[k], c2[k], cram[(y >> 3) * 40 + cx]};
                }
            p.update("Dithering", p0 + (p1 - p0) * (pass + 0.6) / n);
            int[] idx = new int[W * 200];
            float[] seen = new float[W * 200 * 3];
            dither(t160, W, 4, allowed, m, s, idx, seen);
            FliFrame f = encodeMc(idx, bg, cram, c1, c2, s.renderBug());
            double err = blurredError(t160, idx, W, s.sourcePalette());
            if (err < bestErr) { bestErr = err; best = new FrameOut(f, idx, seen, cram); }
            target = seen; // next pass: select colors on the values the ditherer wanted
        }
        return best;
    }

    /** Tries the most frequent nearest colors as background and keeps the cheapest. */
    private static int chooseBackgroundMc(float[] et, boolean bug, int[] fixedCram) {
        int n = et.length / 16;
        long[] count = new long[16];
        for (int i = 0; i < n; i++) {
            int b = 0;
            float bd = Float.MAX_VALUE;
            for (int k = 0; k < 16; k++) if (et[i * 16 + k] < bd) { bd = et[i * 16 + k]; b = k; }
            count[b]++;
        }
        Integer[] order = new Integer[16];
        for (int i = 0; i < 16; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(count[b], count[a]));
        int best = order[0];
        double bestCost = Double.MAX_VALUE;
        for (int ci = 0; ci < 5; ci++) {
            int bg = order[ci];
            double cost = IntStream.range(0, 1000).parallel().mapToDouble(cell ->
                    solveCellMc(et, cell % 40, cell / 40, bg, bug,
                            fixedCram == null ? -1 : fixedCram[cell], null, null, null, false)).sum();
            if (cost < bestCost) { bestCost = cost; best = bg; }
        }
        return best;
    }

    /**
     * Picks the color RAM value of one cell and the two screen colors for every line group.
     * @return error sum of the best solution
     */
    private static double solveCellMc(float[] et, int cx, int cy, int bg, boolean bug, int fixedCram,
                                      int[] cramOut, int[] c1Out, int[] c2Out, boolean write) {
        final int W = 160;
        List<int[]> groups = new ArrayList<>();
        for (int y = cy * 8; y < cy * 8 + 8; y++) {
            if (FliFrame.lineGroup(y) != y) continue;
            groups.add(y == FliFrame.LAST_FLI_LINE ? new int[]{196, 197, 198, 199} : new int[]{y});
        }
        int ng = groups.size();
        double bestTotal = Double.MAX_VALUE;
        int bestCram = 0;
        int[] bestA = new int[ng], bestB = new int[ng], curA = new int[ng], curB = new int[ng];
        int cramFrom = fixedCram >= 0 ? fixedCram : 0, cramTo = fixedCram >= 0 ? fixedCram : 15;
        float[] base = new float[16];
        for (int cram = cramFrom; cram <= cramTo; cram++) {
            double total = 0;
            for (int gi = 0; gi < ng && total < bestTotal; gi++) {
                int[] lines = groups.get(gi);
                if (VicRenderer.isBugCell(lines[0], cx, bug)) {
                    // bug cells are kept clean: background only
                    for (int y : lines) for (int px = 0; px < 4; px++) total += et[(y * W + cx * 4 + px) * 16 + bg];
                    curA[gi] = 15; curB[gi] = 15;
                    continue;
                }
                int q = 0;
                for (int y : lines) for (int px = 0; px < 4; px++) {
                    int i = (y * W + cx * 4 + px) * 16;
                    base[q++] = Math.min(et[i + bg], et[i + cram]);
                }
                float bestPair = Float.MAX_VALUE;
                int ba = 0, bb = 0;
                for (int a = 0; a < 16; a++) {
                    for (int b = a; b < 16; b++) {
                        float c = 0;
                        q = 0;
                        for (int y : lines) for (int px = 0; px < 4; px++) {
                            int i = (y * W + cx * 4 + px) * 16;
                            float v = base[q++];
                            float ea = et[i + a], eb = et[i + b];
                            if (ea < v) v = ea;
                            if (eb < v) v = eb;
                            c += v;
                        }
                        if (c < bestPair) { bestPair = c; ba = a; bb = b; }
                    }
                }
                total += bestPair;
                curA[gi] = ba; curB[gi] = bb;
            }
            if (total < bestTotal) {
                bestTotal = total;
                bestCram = cram;
                System.arraycopy(curA, 0, bestA, 0, ng);
                System.arraycopy(curB, 0, bestB, 0, ng);
            }
        }
        if (write) {
            cramOut[cy * 40 + cx] = bestCram;
            for (int gi = 0; gi < ng; gi++)
                for (int y : groups.get(gi)) {
                    c1Out[y * 40 + cx] = bestA[gi];
                    c2Out[y * 40 + cx] = bestB[gi];
                }
        }
        return bestTotal;
    }

    private static FliFrame encodeMc(int[] idx, int bg, int[] cram, int[] c1, int[] c2, boolean bug) {
        FliFrame f = new FliFrame();
        f.background = bg;
        for (int i = 0; i < 1000; i++) f.colorRam[i] = (byte) cram[i];
        for (int y = 0; y < 200; y++) {
            for (int cx = 0; cx < 40; cx++) {
                int k = y * 40 + cx;
                boolean bugCell = VicRenderer.isBugCell(y, cx, bug);
                if (y <= FliFrame.LAST_FLI_LINE) {
                    f.screens[y & 7][(y >> 3) * 40 + cx] = (byte) (bugCell ? 0xFF : (c1[k] << 4) | c2[k]);
                }
                int byteVal = 0;
                for (int px = 0; px < 4; px++) {
                    int col = idx[y * 160 + cx * 4 + px];
                    int bits;
                    if (bugCell) bits = col == bg ? 0 : 1;
                    else if (col == bg) bits = 0;
                    else if (col == c1[k]) bits = 1;
                    else if (col == c2[k]) bits = 2;
                    else bits = 3;
                    byteVal |= bits << (6 - 2 * px);
                }
                f.bitmap[(y >> 3) * 320 + cx * 8 + (y & 7)] = (byte) byteVal;
            }
        }
        return f;
    }

    // ------------------------------------------------------------------ hires (AFLI)

    static FrameOut convertHires(float[] t320, ConvertSettings s, ColorMatcher m, int passes, Progress p) {
        final int W = 320;
        float[] target = t320;
        FrameOut best = null;
        double bestErr = Double.MAX_VALUE;
        int n = s.dither() == Dither.NONE ? 1 : Math.max(1, passes);
        for (int pass = 0; pass < n; pass++) {
            if (p.cancelled()) break;
            p.update("Selecting colours (pass " + (pass + 1) + "/" + n + ")", (double) pass / n);
            float[] et = m.errorTable(target);
            int[] fg = new int[200 * 40], bk = new int[200 * 40];
            IntStream.range(0, 200 * 40).parallel().forEach(k -> {
                int y = k / 40, cx = k % 40;
                if (FliFrame.lineGroup(y) != y) return; // set by line 196
                int[] lines = y == FliFrame.LAST_FLI_LINE ? new int[]{196, 197, 198, 199} : new int[]{y};
                if (VicRenderer.isBugCell(y, cx, s.renderBug())) {
                    for (int ly : lines) { fg[ly * 40 + cx] = 15; bk[ly * 40 + cx] = 15; }
                    return;
                }
                float bestC = Float.MAX_VALUE;
                int ba = 0, bb = 0;
                for (int a = 0; a < 16; a++)
                    for (int b = a; b < 16; b++) {
                        float c = 0;
                        for (int ly : lines)
                            for (int px = 0; px < 8; px++) {
                                int i = (ly * W + cx * 8 + px) * 16;
                                c += Math.min(et[i + a], et[i + b]);
                            }
                        if (c < bestC) { bestC = c; ba = a; bb = b; }
                    }
                for (int ly : lines) { fg[ly * 40 + cx] = ba; bk[ly * 40 + cx] = bb; }
            });
            int[][] allowed = new int[200 * 40][];
            for (int k = 0; k < 200 * 40; k++) allowed[k] = fg[k] == bk[k] ? new int[]{fg[k]} : new int[]{fg[k], bk[k]};
            p.update("Dithering", (pass + 0.6) / n);
            int[] idx = new int[W * 200];
            float[] seen = new float[W * 200 * 3];
            dither(t320, W, 8, allowed, m, s, idx, seen);
            FliFrame f = new FliFrame();
            for (int y = 0; y < 200; y++)
                for (int cx = 0; cx < 40; cx++) {
                    int k = y * 40 + cx;
                    if (y <= FliFrame.LAST_FLI_LINE)
                        f.screens[y & 7][(y >> 3) * 40 + cx] = (byte) ((fg[k] << 4) | bk[k]);
                    int v = 0;
                    for (int px = 0; px < 8; px++)
                        if (fg[k] != bk[k] && idx[y * W + cx * 8 + px] == fg[k]) v |= 0x80 >> px;
                    f.bitmap[(y >> 3) * 320 + cx * 8 + (y & 7)] = (byte) v;
                }
            double err = blurredError(t320, idx, W, s.sourcePalette());
            if (err < bestErr) { bestErr = err; best = new FrameOut(f, idx, seen, null); }
            target = seen;
        }
        return best;
    }

    // ------------------------------------------------------------------ IFLI

    /**
     * IFLI: two multicolor FLI frames, the second one shifted by one hires pixel; the eye averages them.
     * Color RAM is shared by both frames, the background may differ per frame.
     * Frame B is optimized towards "2 × target − frame A" (and vice versa in later rounds).
     */
    private static List<FliFrame> convertIfli(float[] t320, ConvertSettings s, ColorMatcher m, Palette pal, Progress p) {
        int rounds = Math.max(1, Math.min(4, s.passes()));
        float[] tA = ImageAdjust.halveWidth(t320, 320, 200);
        FrameOut a = convertMc(tA, s, m, null, s.fixedBackground(), 1, p, 0.0, 0.25);
        int[] cram = a.cram();
        FrameOut b = convertMc(complementTarget(t320, a, pal, true), s, m, cram, -1, 1, p, 0.25, 0.5);
        double bestErr = ifliError(t320, a, b, pal);
        FrameOut bestA = a, bestB = b;
        for (int r = 1; r < rounds && !p.cancelled(); r++) {
            double base = 0.5 + 0.5 * (r - 1) / Math.max(1, rounds - 1);
            a = convertMc(complementTarget(t320, b, pal, false), s, m, cram, a.frame().background, 1, p, base, base + 0.1);
            b = convertMc(complementTarget(t320, a, pal, true), s, m, cram, b.frame().background, 1, p, base + 0.1, base + 0.2);
            double e = ifliError(t320, a, b, pal);
            if (e < bestErr) { bestErr = e; bestA = a; bestB = b; }
        }
        return List.of(bestA.frame(), bestB.frame());
    }

    /** Hires RGB of a frame; shifted = frame B (moved one pixel to the right). */
    private static float[] hiresRgb(FrameOut f, Palette pal, boolean shifted) {
        float[] out = new float[320 * 200 * 3];
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int c;
                if (!shifted) c = f.idx()[y * 160 + (x >> 1)];
                else c = x == 0 ? f.frame().background : f.idx()[y * 160 + ((x - 1) >> 1)];
                int i = (y * 320 + x) * 3;
                out[i] = pal.r(c); out[i + 1] = pal.g(c); out[i + 2] = pal.b(c);
            }
        return out;
    }

    /** Target for the other frame: 2·target − known frame, averaged onto multicolor pixels. */
    private static float[] complementTarget(float[] t320, FrameOut known, Palette pal, boolean forShifted) {
        float[] k = hiresRgb(known, pal, !forShifted);
        float[] out = new float[160 * 200 * 3];
        for (int y = 0; y < 200; y++)
            for (int j = 0; j < 160; j++) {
                int x0 = forShifted ? 2 * j + 1 : 2 * j;
                for (int c = 0; c < 3; c++) {
                    float sum = 0;
                    int cnt = 0;
                    for (int x = x0; x < x0 + 2 && x < 320; x++) {
                        int i = (y * 320 + x) * 3 + c;
                        sum += ImageAdjust.clamp(2 * t320[i] - k[i]);
                        cnt++;
                    }
                    out[(y * 160 + j) * 3 + c] = sum / cnt;
                }
            }
        return out;
    }

    private static double ifliError(float[] t320, FrameOut a, FrameOut b, Palette pal) {
        float[] ra = hiresRgb(a, pal, false), rb = hiresRgb(b, pal, true);
        float[] mix = new float[ra.length];
        for (int i = 0; i < mix.length; i++) mix[i] = (ra[i] + rb[i]) * 0.5f;
        return blurredErrorRgb(t320, mix, 320);
    }

    // ------------------------------------------------------------------ dithering

    /**
     * Error diffusion or ordered dithering restricted to the colors allowed per cell and line
     * (shared {@link Ditherer}: colour cells are {@code cellW}×1 because FLI colours change every line;
     * without bleeding the error stays inside the {@code cellW}×8 character cell).
     *
     * @param cellW cell width in pixels (4 for multicolor, 8 for hires)
     */
    static void dither(float[] tgt, int W, int cellW, int[][] allowed, ColorMatcher m, ConvertSettings s,
                       int[] outIdx, float[] outSeen) {
        int[] idx = Ditherer.run(tgt, W, 200, m, s.sourcePalette(),
                new Ditherer.Settings(s.dither(), s.ditherStrength(), s.serpentine(), s.bleeding()),
                new Ditherer.Cells(allowed, cellW, 1, cellW, 8), outSeen);
        System.arraycopy(idx, 0, outIdx, 0, idx.length);
    }

    // ------------------------------------------------------------------ scoring

    /** Error after a light 3×3 blur (rates dithering the way the eye averages it). */
    static double blurredError(float[] target, int[] idx, int W, Palette pal) {
        float[] res = new float[W * 200 * 3];
        for (int i = 0; i < W * 200; i++) {
            res[i * 3] = pal.r(idx[i]); res[i * 3 + 1] = pal.g(idx[i]); res[i * 3 + 2] = pal.b(idx[i]);
        }
        return blurredErrorRgb(target, res, W);
    }

    static double blurredErrorRgb(float[] a, float[] b, int W) {
        float[] ba = blur(a, W), bb = blur(b, W);
        double sum = 0;
        for (int i = 0; i < ba.length; i++) { double d = ba[i] - bb[i]; sum += d * d; }
        return sum / ba.length;
    }

    private static float[] blur(float[] src, int W) {
        int H = 200;
        float[] out = new float[src.length];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                for (int c = 0; c < 3; c++) {
                    float sum = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                            sum += src[(ny * W + nx) * 3 + c];
                            n++;
                        }
                    out[(y * W + x) * 3 + c] = sum / n;
                }
        return out;
    }
}
