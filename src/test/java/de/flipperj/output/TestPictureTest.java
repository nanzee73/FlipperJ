package de.flipperj.output;

import de.flipperj.convert.ConversionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPictureTest {

    /** Hires x of multicolor pixel 0 in character column cx. */
    private static int x(int cx) { return cx * 8; }

    @Test
    void everyLineShowsItsOwnColoursAndNumber() {
        ConversionResult r = TestPicture.result();
        for (int y = 0; y < 200; y++) {
            int shown = Math.min(y, 196);                        // lines 197..199 repeat the colours of line 196
            assertEquals(TestPicture.barColour(shown), r.colorIndex(0, x(5), y), "bar, line " + y);
            assertEquals(TestPicture.markerColour(shown), r.colorIndex(0, x(30), y), "marker, line " + y);
            int n = 0;
            for (int b = 0; b < 8; b++)
                n = n << 1 | (r.colorIndex(0, x(14 + b), y) == TestPicture.WHITE ? 1 : 0);
            assertEquals(y, n, "barcode, line " + y);
            assertEquals(TestPicture.BLACK, r.colorIndex(0, x(14) + 7, y), "separator, line " + y);
        }
        assertEquals(TestPicture.BLACK, r.colorIndex(0, x(1), 0), "line 0 has no FLI bug");
        assertEquals(15, r.colorIndex(0, x(1), 1), "line 1 shows the FLI bug (light grey)");
        assertEquals(TestPicture.WHITE, r.colorIndex(0, x(30), 0));
        assertEquals(TestPicture.RED, r.colorIndex(0, x(30), 8));
    }

    @Test
    void buildsARunnablePrg() {
        byte[] prg = PrgBuilder.build(TestPicture.result());
        assertEquals(0x01, prg[0]);
        assertEquals(0x08, prg[1]);
        assertTrue(prg.length > 0x7F40 - 0x0801);
    }
}
