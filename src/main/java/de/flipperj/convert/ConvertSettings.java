package de.flipperj.convert;

import de.c64gfx.dither.Dither;

import de.c64gfx.color.ColorMatcher;
import de.c64gfx.palette.Palette;

/**
 * All parameters of a conversion.
 *
 * @param sourcePalette   palette used for color matching
 * @param space           color space used for matching
 * @param weightU         U weight (0..100, 50 = neutral)
 * @param weightV         V weight (0..100, 50 = neutral)
 * @param respectLuma     penalize brightness deviations more
 * @param dither          dithering method
 * @param ditherStrength  strength 0..100 %
 * @param bleeding        error may be diffused across cell borders
 * @param serpentine      process lines alternately left→right / right→left
 * @param renderBug       take the FLI bug (first 3 cells) into account
 * @param passes          refinement passes for color selection (1..5)
 * @param fixedBackground fixed background color, or -1 for automatic
 */
public record ConvertSettings(
        FliMode mode,
        Palette sourcePalette,
        ColorMatcher.Space space,
        int weightU, int weightV,
        boolean respectLuma,
        Dither dither,
        int ditherStrength,
        boolean bleeding,
        boolean serpentine,
        boolean renderBug,
        int passes,
        int fixedBackground) {

    public ColorMatcher matcher() {
        return new ColorMatcher(sourcePalette, space, weightU, weightV, respectLuma);
    }
}
