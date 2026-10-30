package de.flipperj.output;

import de.c64gfx.prg.Asm6502;

import de.flipperj.convert.ConversionResult;
import de.flipperj.convert.FliFrame;
import de.flipperj.convert.FliMode;

/**
 * Builds a runnable .prg file (LOAD"PIC",8 : RUN, or SYS 2061) containing displayer and picture data.
 *
 * <pre>
 * Memory map
 *   $0801        BASIC line "SYS 2061"
 *   $080D        JMP main
 *   $0900-$09C7  $D018 table (200 lines)
 *   $0A00-$0AC7  $D011 table
 *   $0B00-       displayer code
 *   $3C00-$3FE7  color RAM data (copied to $D800)
 *   $3FF0        background frame A, $3FF1 background frame B (IFLI)
 *   $4000-$5FFF  8 screens frame A (VIC bank 1)
 *   $6000-$7F3F  bitmap frame A
 *   IFLI only:
 *   $8000-$BF3F  frame B in the load image, copied to $C000-$FF3F (VIC bank 3) at start
 * </pre>
 *
 * Timing (PAL, 63 cycles per line), no raster IRQ needed:
 * <ol>
 *   <li>$D011=$38 makes raster line $30 a regular bad line (RC=0, hidden by the top border).</li>
 *   <li>In line $32 a late write to $D011 (YSCROLL=2) triggers a bad line; the CPU is halted right
 *       after the write and resumes deterministically at cycle 55. From here on timing is stable.</li>
 *   <li>Line $33 (picture line 0) is a regular bad line; from picture line 1 the classic 23-cycle FLI
 *       loop runs up to raster line $F7.</li>
 *   <li>Raster lines $F8..$FA (picture lines 197..199) cannot be bad lines and show the colors of line 196.</li>
 * </ol>
 */
public final class PrgBuilder {
    public static final int FIRST_RASTER_LINE = 0x33;
    private static final int D018TAB = 0x0900, D011TAB = 0x0A00, CODE = 0x0B00;
    private static final int CRAM_DATA = 0x3C00, BG_A = 0x3FF0, BG_B = 0x3FF1;

    private PrgBuilder() {}

    public static byte[] build(ConversionResult r) {
        FliMode mode = r.mode;
        Asm6502 a = new Asm6502(0x0801);
        // BASIC: 10 SYS2061
        a.bytes(0x0B, 0x08, 0x0A, 0x00, 0x9E, '2', '0', '6', '1', 0x00, 0x00, 0x00);
        a.jmp("main");

        a.org(D018TAB).label("d018tab");
        for (int i = 0; i < 200; i++) a.bytes(((i & 7) << 4) | 0x08);
        a.org(D011TAB).label("d011tab");
        for (int i = 0; i < 200; i++) a.bytes(0x38 | ((FIRST_RASTER_LINE + i) & 7));

        a.org(CODE).label("main");
        a.sei();
        a.ldaImm(0x7F).staAbs(0xDC0D).staAbs(0xDD0D).ldaAbs(0xDC0D).ldaAbs(0xDD0D);
        a.ldaImm(0x00).staAbs(0xD01A).staAbs(0xD015).staAbs(0xD020);
        a.ldaImm(0xFF).staAbs(0xD019);

        if (mode.interlaced) {
            // copy frame B from $8000 to $C000 (RAM under I/O and KERNAL)
            a.ldaImm(0x34).staZp(0x01);
            a.ldaImm(0x00).staZp(0xFB).staZp(0xFD);
            a.ldaImm(0x80).staZp(0xFC);
            a.ldaImm(0xC0).staZp(0xFE);
            a.ldxImm(0x40).ldyImm(0x00);
            a.label("copy").ldaIndY(0xFB).staIndY(0xFD).iny().bne("copy");
            a.incZp(0xFC).incZp(0xFE).dex().bne("copy");
            a.ldaImm(0x35).staZp(0x01);              // KERNAL off, I/O on
            a.ldaImm(0).staZp(0x02);                 // frame toggle
            a.ldaImmLow("nmi").staAbs(0xFFFA);       // RESTORE must not crash
            a.ldaImmHigh("nmi").staAbs(0xFFFB);
        }

        if (mode.multicolor) {
            a.ldxImm(0);
            a.label("cram");
            a.ldaAbsX(CRAM_DATA).staAbsX(0xD800);
            a.ldaAbsX(CRAM_DATA + 0x100).staAbsX(0xD900);
            a.ldaAbsX(CRAM_DATA + 0x200).staAbsX(0xDA00);
            a.ldaAbsX(CRAM_DATA + 0x2E8).staAbsX(0xDAE8);
            a.inx().bne("cram");
        }
        a.ldaAbs(0xDD02).oraImm(0x03).staAbs(0xDD02);
        a.ldaAbs(BG_A).staAbs(0xD021);
        a.ldaImm(mode.multicolor ? 0x18 : 0x08).staAbs(0xD016);
        a.ldaAbs(0xDD00).andImm(0xFC).oraImm(0x02).staAbs(0xDD00);   // VIC bank 1 ($4000)
        a.ldaImm(0x08).staAbs(0xD018);
        a.ldaImm(0x38).staAbs(0xD011);

        a.label("frame");
        a.label("w1").bitAbs(0xD011).bpl("w1");     // wait for raster >= 256
        a.label("w2").bitAbs(0xD011).bmi("w2");     // wait for line 0
        if (mode.interlaced) {
            a.ldaZp(0x02).eorImm(1).staZp(0x02).beq("showA");
            // frame B: bank 3 ($C000), XSCROLL=1
            a.ldaAbs(0xDD00).andImm(0xFC).staAbs(0xDD00);
            a.ldaImm(0x19).staAbs(0xD016);
            a.ldaAbs(BG_B).staAbs(0xD021);
            a.jmp("sync");
            a.label("showA");
            a.ldaAbs(0xDD00).andImm(0xFC).oraImm(0x02).staAbs(0xDD00);
            a.ldaImm(0x18).staAbs(0xD016);
            a.ldaAbs(BG_A).staAbs(0xD021);
        }
        a.jmp("sync");
        // the timing-critical part starts on a fresh page so no branch crosses a page boundary
        a.org((a.pc() + 0xFF) & 0xFF00);
        a.label("sync");
        a.ldaImm(0x32);
        a.label("w3").cmpAbs(0xD012).bne("w3");
        a.nop().nop().nop().nop().nop();          // make sure the write lands after cycle 15 of line $32
        a.ldaImm(0x3A).staAbs(0xD011);             // late bad line in $32 -> CPU synced (resumes at cycle 55)
        a.ldaAbs("d018tab").staAbs(0xD018);        // cycles 55-62: screen 0 for picture line 0
        a.ldaImm(0x3B).staAbs(0xD011);             // cycles 63-5: line $33 becomes a regular bad line
        a.ldxImm(1);                               // 6-7
        a.nop().nop().nop().nop();                 // 8-11, halted from 12, resumes 55-58
        a.jmp("loop");                             // 59-61
        // classic FLI loop: exactly 23 cycles
        a.label("loop");
        a.ldaAbsX("d018tab").staAbs(0xD018);
        a.ldaAbsX("d011tab").staAbs(0xD011);
        a.inx().cpxImm(FliFrame.LAST_FLI_LINE + 1).bne("loop");
        a.label("loopEnd");
        a.ldaImm(0x38).staAbs(0xD011);
        a.jmp("frame");
        a.label("nmi").rti();
        a.assertSamePage("loop", "loopEnd");
        if (a.pc() > CRAM_DATA) throw new IllegalStateException("Displayer code too large");

        // picture data
        a.org(CRAM_DATA);
        FliFrame fa = r.frames.get(0);
        a.bytes(fa.colorRam);
        a.org(BG_A).bytes(fa.background);
        a.bytes(mode.interlaced ? r.frames.get(1).background : 0);
        a.org(0x4000);
        appendFrame(a, fa);
        if (mode.interlaced) {
            a.org(0x8000);
            appendFrame(a, r.frames.get(1));
        }
        byte[] body = a.assemble();
        byte[] prg = new byte[body.length + 2];
        prg[0] = 0x01;
        prg[1] = 0x08;
        System.arraycopy(body, 0, prg, 2, body.length);
        return prg;
    }

    /** Appends 8 screens (+$0000-$1FFF) and the bitmap (+$2000-$3F3F); +$3FFF = 0 is the idle byte. */
    private static void appendFrame(Asm6502 a, FliFrame f) {
        int base = a.pc();
        for (int s = 0; s < 8; s++) a.bytes(f.screens[s]);
        a.bytes(f.bitmap);
        a.org(base + 0x3FFF).bytes(0);
    }
}
