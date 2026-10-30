package de.flipperj.output;

import de.flipperj.convert.ConversionResult;
import de.flipperj.convert.FliFrame;
import de.flipperj.convert.FliMode;

import java.util.List;

/**
 * FLI test picture to check the displayer timing in an emulator (VICE) or on a real C64: every picture line gets
 * its own screen RAM colours, so a line that shows the colours of another line stands out at once.
 * Compare the screen with the expected picture FlipperJ writes next to the .prg.
 *
 * <pre>
 * columns  0..2   FLI bug area: line 0 black (a normal bad line), all other lines light grey ($FF screen data) –
 *                  if line 0 is grey too, the first picture line already has the FLI bug
 * columns  3..12  colour bar: line y has colour 1 + (y mod 15) – the sequence 1,2,…,15,1,2,… must be unbroken
 * columns 14..21  line number y in binary, 8 bits, most significant bit left: white = 1, dark grey = 0
 * columns 23..39  row marker: line 0 white, first line of every character row red, line 196 yellow, others blue
 * </pre>
 *
 * Lines 197..199 cannot have own colours (no bad lines possible there); they repeat line 196 – yellow bar marker,
 * colour bar and barcode colours of line 196 (the barcode bits still come from the bitmap, so they read 197..199).
 */
public final class TestPicture {
    private TestPicture() {}

    public static final int BLACK = 0, WHITE = 1, RED = 2, BLUE = 6, YELLOW = 7, DARK_GREY = 11;

    /** Expected colour of the colour bar in line y (as written into the screen RAM of that line). */
    public static int barColour(int y) { return 1 + y % 15; }

    /** Expected colour of the row marker in line y (as written into the screen RAM of that line). */
    public static int markerColour(int y) {
        if (y == 0) return WHITE;
        if (y == FliFrame.LAST_FLI_LINE) return YELLOW;
        return (y & 7) == 0 ? RED : BLUE;
    }

    public static FliFrame frame() {
        FliFrame f = new FliFrame();
        f.background = BLACK;
        java.util.Arrays.fill(f.colorRam, (byte) WHITE);
        for (int y = 0; y < 200; y++) {
            int row = y >> 3, line = y & 7;
            byte[] screen = f.screens[line];
            for (int cx = 0; cx < 40; cx++) {
                int bits, scr;
                if (cx < 3) {                                       // FLI bug area: pair 01, black in line 0
                    bits = 0b01_01_01_01;
                    scr = BLACK << 4;
                } else if (cx >= 3 && cx <= 12) {                          // colour bar: pair 01 = screen high nibble
                    bits = 0b01_01_01_01;
                    scr = barColour(y) << 4;
                } else if (cx >= 14 && cx <= 21) {                  // barcode: 11 = colour RAM (white), 10 = dark grey
                    boolean one = ((y >> (7 - (cx - 14))) & 1) != 0;
                    bits = one ? 0b11_11_11_00 : 0b10_10_10_00;     // last pixel stays black as a separator
                    scr = DARK_GREY;
                } else if (cx >= 23) {                              // row marker
                    bits = 0b01_01_01_01;
                    scr = markerColour(y) << 4;
                } else {
                    bits = 0;
                    scr = 0;
                }
                f.bitmap[row * 320 + cx * 8 + line] = (byte) bits;
                screen[row * 40 + cx] = (byte) scr;
            }
        }
        return f;
    }

    /** The test picture as a conversion result (FLI, with FLI bug) for {@link PrgBuilder} and the preview. */
    public static ConversionResult result() {
        return new ConversionResult(FliMode.FLI, List.of(frame()), true, new float[320 * 200 * 3]);
    }
}
