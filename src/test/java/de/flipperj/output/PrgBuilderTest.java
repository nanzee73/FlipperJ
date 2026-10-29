package de.flipperj.output;

import de.flipperj.convert.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrgBuilderTest {

    private static ConversionResult dummy(FliMode mode) {
        FliFrame a = new FliFrame(), b = new FliFrame();
        a.background = 6;
        b.background = 11;
        a.bitmap[0] = 0x55;
        a.screens[3][7] = 0x12;
        a.colorRam[999] = 9;
        b.bitmap[7999] = (byte) 0xAA;
        List<FliFrame> frames = mode.interlaced ? List.of(a, b) : List.of(a);
        return new ConversionResult(mode, frames, true, new float[320 * 200 * 3]);
    }

    private static int at(byte[] prg, int address) {
        int load = (prg[0] & 0xFF) | (prg[1] & 0xFF) << 8;
        return prg[address - load + 2] & 0xFF;
    }

    @Test
    void basicStubAndJump() {
        byte[] prg = PrgBuilder.build(dummy(FliMode.FLI));
        assertEquals(0x01, prg[0]);
        assertEquals(0x08, prg[1]);
        assertEquals(0x9E, at(prg, 0x0805));          // SYS token
        assertEquals(0x4C, at(prg, 0x080D));          // JMP main
    }

    @Test
    void dataLandsAtDocumentedAddresses() {
        byte[] prg = PrgBuilder.build(dummy(FliMode.IFLI));
        assertEquals(6, at(prg, 0x3FF0));
        assertEquals(11, at(prg, 0x3FF1));
        assertEquals(9, at(prg, 0x3C00 + 999));
        assertEquals(0x12, at(prg, 0x4000 + 3 * 0x400 + 7));
        assertEquals(0x55, at(prg, 0x6000));
        assertEquals(0xAA, at(prg, 0xA000 + 7999));
        assertEquals(0xC000, 0x0801 + prg.length - 2);
    }

    @Test
    void tablesMatchFliTiming() {
        byte[] prg = PrgBuilder.build(dummy(FliMode.AFLI));
        for (int i = 0; i < 200; i++) {
            assertEquals(((i & 7) << 4) | 8, at(prg, 0x0900 + i));
            assertEquals(0x38 | ((0x33 + i) & 7), at(prg, 0x0A00 + i));
        }
    }

    @Test
    void rawCombinedImageHasLoadAddress3C00() {
        byte[] raw = RawExporter.combined(dummy(FliMode.FLI));
        assertEquals(0x00, raw[0]);
        assertEquals(0x3C, raw[1]);
        assertEquals(0x55, raw[2 + 0x6000 - 0x3C00] & 0xFF);
    }
}
