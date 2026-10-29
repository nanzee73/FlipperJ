package de.flipperj.palette;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PalettesTest {

    @Test
    void builtInsHaveSixteenColors() {
        for (Palette p : Palettes.builtIns()) assertEquals(16, p.toArray().length, p.name());
    }

    @Test
    void vplRoundTrip(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("test.vpl");
        Palettes.saveVpl(Palettes.COLODORE, f);
        assertArrayEquals(Palettes.COLODORE.toArray(), Palettes.load(f).toArray());
    }

    @Test
    void loadsGimpPalette(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder("GIMP Palette\nName: test\n#\n");
        for (int i = 0; i < 16; i++) sb.append(i * 16).append(' ').append(i).append(' ').append(255 - i).append(" c").append(i).append('\n');
        Path f = dir.resolve("t.gpl");
        Files.writeString(f, sb.toString());
        Palette p = Palettes.load(f);
        assertEquals((32 << 16) | (2 << 8) | 253, p.rgb(2));
    }

    @Test
    void loadsHexList(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int c : Palettes.PEPTO.toArray()) sb.append(String.format("#%06X%n", c));
        Path f = dir.resolve("p.hex");
        Files.writeString(f, sb.toString());
        assertArrayEquals(Palettes.PEPTO.toArray(), Palettes.load(f).toArray());
    }
}
