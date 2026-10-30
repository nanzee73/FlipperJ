package de.flipperj.convert;

import de.c64gfx.dither.Dither;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.swing.ImageAdjust;
import de.c64gfx.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConverterTest {

    /** Colorful test picture: hue gradient horizontally, brightness vertically. */
    static float[] testTarget() {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++)
                img.setRGB(x, y, java.awt.Color.HSBtoRGB(x / 320f, 0.8f, 0.15f + 0.85f * y / 200f));
        return ImageAdjust.toAdjustedRgb(img, ImageAdjust.Settings.NEUTRAL, false);
    }

    static ConvertSettings settings(FliMode mode, Dither d, boolean bug) {
        return new ConvertSettings(mode, Palettes.PEPTO, ColorMatcher.Space.YUV, 25, 50, false, d, 80,
                true, false, bug, 2, -1);
    }

    @Test
    void multicolorDataRendersExactlyLikeTheDitheredPixels() {
        float[] t = ImageAdjust.halveWidth(testTarget(), 320, 200);
        for (boolean bug : new boolean[]{true, false}) {
            for (Dither d : Dither.values()) {
                ConvertSettings s = settings(FliMode.FLI, d, bug);
                FliConverter.FrameOut fo = FliConverter.convertMc(t, s, s.matcher(), null, -1, 2,
                        FliConverter.Progress.NONE, 0, 1);
                assertArrayEquals(fo.idx(), VicRenderer.render(fo.frame(), true, bug), d + " bug=" + bug);
            }
        }
    }

    @Test
    void hiresDataRendersExactlyLikeTheDitheredPixels() {
        for (boolean bug : new boolean[]{true, false}) {
            ConvertSettings s = settings(FliMode.AFLI, Dither.FLOYD_STEINBERG, bug);
            FliConverter.FrameOut fo = FliConverter.convertHires(testTarget(), s, s.matcher(), 1, FliConverter.Progress.NONE);
            assertArrayEquals(fo.idx(), VicRenderer.render(fo.frame(), false, bug));
        }
    }

    @Test
    void fliBugCellsAreBackgroundOnly() {
        ConversionResult r = FliConverter.convert(testTarget(), settings(FliMode.FLI, Dither.FLOYD_STEINBERG, true),
                FliConverter.Progress.NONE);
        int bg = r.frames.get(0).background;
        for (int y = 1; y < 200; y++)
            for (int x = 0; x < 24; x++) assertEquals(bg, r.colorIndex(0, x, y));
    }

    @Test
    void ifliSharesColorRam() {
        ConversionResult r = FliConverter.convert(testTarget(), settings(FliMode.IFLI, Dither.FLOYD_STEINBERG, true),
                FliConverter.Progress.NONE);
        assertEquals(2, r.frames.size());
        assertArrayEquals(r.frames.get(0).colorRam, r.frames.get(1).colorRam);
    }

    @Test
    void bottomLinesShareScreenOfLine196() {
        ConversionResult r = FliConverter.convert(testTarget(), settings(FliMode.AFLI, Dither.NONE, false),
                FliConverter.Progress.NONE);
        FliFrame f = r.frames.get(0);
        for (int y = 197; y < 200; y++)
            for (int cx = 0; cx < 40; cx++) assertEquals(f.screenByte(196, cx), f.screenByte(y, cx));
    }

    @Test
    void bayerMatricesArePermutations() {
        for (int n : new int[]{2, 4, 8}) {
            Set<Integer> seen = new HashSet<>();
            for (int[] row : Dither.bayer(n)) for (int v : row) seen.add(v);
            assertEquals(n * n, seen.size());
        }
    }
}
