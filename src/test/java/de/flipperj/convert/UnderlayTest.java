package de.flipperj.convert;

import de.c64gfx.dither.Dither;
import de.c64gfx.format.C64Formats;
import de.c64gfx.format.C64Picture;
import de.c64gfx.palette.Palettes;
import de.flipperj.output.UnderlayFile;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UnderlayTest {

    /** Random frame with a sprite layer that keeps the sprite units (2 or 4 pixels, line pairs). */
    private static UnderlayFrame randomFrame(boolean nufli, long seed) {
        Random r = new Random(seed);
        UnderlayFrame f = new UnderlayFrame(nufli);
        for (int i = 0; i < 4000; i++) { f.fg[i] = r.nextInt(16); f.bg[i] = r.nextInt(16); }
        for (int st = 1; st <= 6; st++)
            for (int p = 0; p < UnderlayFrame.PAIRS; p++) {
                f.stripColour[st][p] = r.nextInt(16);
                f.multicolour[st][p] = !nufli && r.nextBoolean();
            }
        f.mc1 = 3; f.mc3 = 7; f.bugHires = 2; f.bugMc1 = 5; f.bugMc2 = 6; f.bugMc3 = 9;
        for (int i = 0; i < f.bits.length; i++) f.bits[i] = r.nextInt(3) == 0;
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 320; x++) {
                int i = y * 320 + x, cx = x >> 3, st = UnderlayFrame.strip(cx);
                if (y > 0 && (y & 1) == 0) { f.sprite[i] = f.sprite[i - 320]; continue; }   // pairs 2p-1, 2p
                if (nufli && cx < 3) { f.sprite[i] = r.nextInt(2) | ((x & 1) == 1 ? f.sprite[i - 1] & 6 : r.nextInt(4) << 1); continue; }
                if (st == 0) continue;
                boolean mc = f.multicolour[st][UnderlayFrame.pair(y)];
                int unit = mc ? 4 : 2;
                f.sprite[i] = (x - 24) % unit == 0 ? r.nextInt(mc ? 4 : 2) : f.sprite[i - 1];
            }
        return f;
    }

    @Test
    void editorFilesReadBackExactly() throws Exception {
        for (boolean nufli : new boolean[]{true, false}) {
            UnderlayFrame f = randomFrame(nufli, 7);
            UnderlayFile u = UnderlayFile.build(f);
            assertEquals(0, u.conflicts, "sprite bytes shared by different lines");
            byte[] d = u.bytes();
            assertEquals(nufli ? UnderlayFile.NUFLI_SIZE : UnderlayFile.MUFLI_SIZE, d.length);
            C64Picture p = C64Formats.read(nufli ? "x.nuf" : "x.muf", d);
            for (int y = 0; y < 200; y++)
                for (int x = 0; x < 320; x++)
                    assertEquals(f.colour(x, y), p.frame1()[y * 320 + x], (nufli ? "NUFLI " : "MUFLI ") + x + "," + y);
        }
    }

    @Test
    void muifliKeepsBothFrames() throws Exception {
        UnderlayFrame a = randomFrame(false, 1), b = randomFrame(false, 2);
        byte[] d = UnderlayFile.muifli(UnderlayFile.build(a).bytes(), UnderlayFile.build(b).bytes());
        assertEquals(UnderlayFile.MUIFLI_SIZE, d.length);
        C64Picture p = C64Formats.read("x.mui", d);
        assertNotNull(p.frame2());
        for (int i = 0; i < 64000; i++) {
            assertEquals(a.colour(i % 320, i / 320), p.frame1()[i]);
            assertEquals(b.colour(i % 320, i / 320), p.frame2()[i]);
        }
    }

    @Test
    void displayerFitsEveryLinePair() {
        float[] t = ConverterTest.testTarget();
        for (FliMode mode : new FliMode[]{FliMode.NUFLI, FliMode.MUFLI}) {
            ConversionResult r = FliConverter.convert(t, ConverterTest.settings(mode, Dither.FLOYD_STEINBERG, true),
                    FliConverter.Progress.NONE);
            UnderlayFrame f = r.underlay.get(0);
            for (int p = 1; p < UnderlayFrame.PAIRS; p++) assertTrue(de.flipperj.output.UnderlayPrg.fits(f, p), mode + " pair " + p);
            byte[] prg = de.flipperj.output.PrgBuilder.build(r);
            assertEquals(0x01, prg[0]);
            assertEquals(0x08, prg[1]);
            assertEquals(0x7A00, 0x0801 + prg.length - 2, mode + ": picture data up to $79FF");
        }
    }

    @Test
    void convertsAllUnderlayModes() {
        float[] t = ConverterTest.testTarget();
        for (FliMode mode : new FliMode[]{FliMode.NUFLI, FliMode.MUFLI, FliMode.MUIFLI}) {
            ConversionResult r = FliConverter.convert(t, ConverterTest.settings(mode, Dither.FLOYD_STEINBERG, true),
                    FliConverter.Progress.NONE);
            assertNotNull(r.editorFile, mode.name());
            assertEquals(mode.interlaced, r.frameIndices(r.mode.interlaced ? 1 : 0) != null && mode.interlaced);
            double e = r.meanError(Palettes.PEPTO);
            assertTrue(e < 80, mode + " mean error " + e);
        }
    }
}
