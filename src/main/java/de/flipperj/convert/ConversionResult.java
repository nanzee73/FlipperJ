package de.flipperj.convert;

import de.flipperj.palette.Palette;

import java.awt.image.BufferedImage;
import java.util.List;

/** Result of a conversion: C64 data plus preview rendering. */
public final class ConversionResult {
    public final FliMode mode;
    public final List<FliFrame> frames;
    public final boolean renderBug;
    /** Target (pre-converted picture), 320×200 RGB floats. */
    public final float[] target;
    private final int[][] indices;

    public ConversionResult(FliMode mode, List<FliFrame> frames, boolean renderBug, float[] target) {
        this.mode = mode;
        this.frames = frames;
        this.renderBug = renderBug;
        this.target = target;
        this.indices = new int[frames.size()][];
        for (int i = 0; i < frames.size(); i++) indices[i] = VicRenderer.render(frames.get(i), mode.multicolor, renderBug);
    }

    /** Perceived RGB per pixel (320×200) with the given palette. Interlaced frames are averaged. */
    public int[] perceivedRgb(Palette pal) {
        int[] out = new int[320 * 200];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 320; x++) {
                int a = colorIndex(0, x, y);
                if (!mode.interlaced) {
                    out[y * 320 + x] = pal.rgb(a);
                } else {
                    // frame B is shifted right by one hires pixel ($D016 XSCROLL=1)
                    int b = x == 0 ? frames.get(1).background & 15 : colorIndex(1, x - 1, y);
                    int r = (pal.r(a) + pal.r(b)) / 2, g = (pal.g(a) + pal.g(b)) / 2, bl = (pal.b(a) + pal.b(b)) / 2;
                    out[y * 320 + x] = (r << 16) | (g << 8) | bl;
                }
            }
        }
        return out;
    }

    /** Palette index of frame f at hires position x. */
    public int colorIndex(int f, int x, int y) {
        return mode.multicolor ? indices[f][y * 160 + (x >> 1)] : indices[f][y * 320 + x];
    }

    public int[] frameIndices(int f) { return indices[f]; }

    public BufferedImage render(Palette pal) {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 320, 200, perceivedRgb(pal), 0, 320);
        return img;
    }

    /** Error map: deviation between target and result (using the source palette) as a heat map. */
    public BufferedImage errorMap(Palette sourcePal) {
        int[] rgb = perceivedRgb(sourcePal);
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 320 * 200; i++) {
            float e = (float) pixelError(i, rgb[i]) / 160f;
            img.setRGB(i % 320, i / 320, heat(Math.min(1f, e)));
        }
        return img;
    }

    /** Mean per-pixel RGB distance, as a quality indicator. */
    public double meanError(Palette sourcePal) {
        int[] rgb = perceivedRgb(sourcePal);
        double sum = 0;
        for (int i = 0; i < 320 * 200; i++) sum += pixelError(i, rgb[i]);
        return sum / (320 * 200);
    }

    private double pixelError(int i, int rgb) {
        float dr = target[i * 3] - ((rgb >> 16) & 255);
        float dg = target[i * 3 + 1] - ((rgb >> 8) & 255);
        float db = target[i * 3 + 2] - (rgb & 255);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int heat(float t) {
        int r = (int) (255 * Math.min(1, t * 3));
        int g = (int) (255 * Math.max(0, Math.min(1, t * 3 - 1)));
        int b = (int) (255 * Math.max(0, Math.min(1, t * 3 - 2)));
        return (r << 16) | (g << 8) | b;
    }
}
