package de.flipperj.output;

import de.flipperj.convert.ConversionResult;
import de.flipperj.convert.FliFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw data export. Per frame (suffix _a/_b for IFLI):
 * <ul>
 *   <li>NAME_bitmap.bin   – 8000 bytes</li>
 *   <li>NAME_screens.bin  – 8 × 1024 bytes (screen n at offset n*1024; picture line y uses screen y&amp;7)</li>
 *   <li>NAME_colorram.bin – 1000 bytes (multicolor only)</li>
 *   <li>NAME_bg.bin       – 1 byte ($D021)</li>
 * </ul>
 * Additionally NAME.fli / .afl / .ifl: one contiguous image with load address $3C00
 * (color RAM $3C00, backgrounds $3FF0/$3FF1, screens $4000, bitmap $6000, IFLI frame B from $8000).
 */
public final class RawExporter {
    private RawExporter() {}

    public static List<Path> export(ConversionResult r, Path dir, String baseName) throws IOException {
        List<Path> written = new ArrayList<>();
        for (int i = 0; i < r.frames.size(); i++) {
            FliFrame f = r.frames.get(i);
            String n = baseName + (r.frames.size() > 1 ? (i == 0 ? "_a" : "_b") : "");
            byte[] screens = new byte[8 * 1024];
            for (int s = 0; s < 8; s++) System.arraycopy(f.screens[s], 0, screens, s * 1024, 1024);
            written.add(write(dir.resolve(n + "_bitmap.bin"), f.bitmap));
            written.add(write(dir.resolve(n + "_screens.bin"), screens));
            if (r.mode.multicolor) written.add(write(dir.resolve(n + "_colorram.bin"), f.colorRam));
            written.add(write(dir.resolve(n + "_bg.bin"), new byte[]{(byte) f.background}));
        }
        written.add(write(dir.resolve(baseName + "." + r.mode.rawExtension), combined(r)));
        return written;
    }

    static byte[] combined(ConversionResult r) {
        int start = 0x3C00;
        int end = r.mode.interlaced ? 0xBF40 : 0x7F40;
        byte[] mem = new byte[end - start];
        FliFrame a = r.frames.get(0);
        System.arraycopy(a.colorRam, 0, mem, 0, 1000);
        mem[0x3FF0 - start] = (byte) a.background;
        if (r.mode.interlaced) mem[0x3FF1 - start] = (byte) r.frames.get(1).background;
        putFrame(mem, 0x4000 - start, a);
        if (r.mode.interlaced) putFrame(mem, 0x8000 - start, r.frames.get(1));
        byte[] out = new byte[mem.length + 2];
        out[0] = (byte) start;
        out[1] = (byte) (start >> 8);
        System.arraycopy(mem, 0, out, 2, mem.length);
        return out;
    }

    private static void putFrame(byte[] mem, int off, FliFrame f) {
        for (int s = 0; s < 8; s++) System.arraycopy(f.screens[s], 0, mem, off + s * 1024, 1024);
        System.arraycopy(f.bitmap, 0, mem, off + 0x2000, 8000);
    }

    private static Path write(Path p, byte[] data) throws IOException {
        Files.write(p, data);
        return p;
    }
}
