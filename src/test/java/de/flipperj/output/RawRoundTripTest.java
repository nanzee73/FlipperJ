package de.flipperj.output;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.dither.Dither;
import de.c64gfx.format.C64Formats;
import de.c64gfx.format.C64Picture;
import de.c64gfx.palette.Palettes;
import de.c64gfx.swing.ImageAdjust;
import de.flipperj.convert.ConversionResult;
import de.flipperj.convert.ConvertSettings;
import de.flipperj.convert.FliConverter;
import de.flipperj.convert.FliMode;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** FlipperJ's raw .fli / .afl / .ifl files read back with C64GfxLib look exactly like FlipperJ's preview. */
class RawRoundTripTest {

    @Test
    void rawFilesReadBackLikeThePreview() throws Exception {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++)
                img.setRGB(x, y, java.awt.Color.HSBtoRGB(x / 320f, 0.8f, 0.15f + 0.85f * y / 200f));
        float[] target = ImageAdjust.toAdjustedRgb(img, ImageAdjust.Settings.NEUTRAL, false);

        for (FliMode mode : FliMode.values()) {
            if (!java.util.List.of("fli", "afl", "ifl").contains(mode.rawExtension)) continue;
            ConvertSettings s = new ConvertSettings(mode, Palettes.PEPTO, ColorMatcher.Space.YUV, 25, 50, false,
                    Dither.FLOYD_STEINBERG, 80, true, false, true, 1, -1);
            ConversionResult r = FliConverter.convert(target, s, FliConverter.Progress.NONE);
            byte[] file = RawExporter.combined(r);
            C64Picture p = C64Formats.read("pic." + mode.rawExtension, file);
            assertEquals(mode.interlaced, p.interlaced(), mode.name());
            assertArrayEquals(r.perceivedRgb(Palettes.PEPTO), p.render(Palettes.PEPTO), mode.name());
        }
    }
}
