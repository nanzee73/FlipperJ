package de.flipperj;

import de.flipperj.color.ColorMatcher;
import de.flipperj.convert.*;
import de.flipperj.image.ImageAdjust;
import de.flipperj.image.ImageFitter;
import de.flipperj.output.PrgBuilder;
import de.flipperj.output.RawExporter;
import de.flipperj.palette.Palette;
import de.flipperj.palette.Palettes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Command line interface for batch conversion. */
public final class Cli {
    private Cli() {}

    static void usage() {
        System.out.println("""
                FlipperJ - FLI/AFLI/IFLI converter for the Commodore 64

                Usage: flipperj [options] <input image> <output.prg>
                       (no arguments starts the GUI)

                Options:
                  --mode fli|afli|ifli        default: fli
                  --palette NAME|FILE         source palette (default: Pepto); built-in names:
                                              pepto, colodore, deekay, palette, community, ptoing,
                                              c64hq, ccs64, c64s, vice
                  --space rgb|yuv|hsl         matching color space (default: yuv)
                  --weight-u N --weight-v N   YUV weights 0..100 (default: 25 / 50)
                  --luma                      respect luma ramp
                  --dither NAME               none, fs, atkinson, jarvis, stucki, sierra, bayer2, bayer4, bayer8
                  --strength N                dither strength 0..100 (default: 80)
                  --no-bleeding               keep diffused error inside each cell
                  --serpentine                serpentine scanning
                  --no-fli-bug                ignore the FLI bug (e.g. when you cover it with sprites)
                  --passes N                  refinement passes 1..5 (default: 2)
                  --bg N                      fixed background color 0..15
                  --brightness N --contrast N --gamma N --saturation N   (gamma/saturation in percent)
                  --scale stretch|fit|fill    for images that are not 320x200/160x200 (default: fit)
                  --raw                       additionally write raw data files
                  --no-prg                    do not write the .prg
                  --preview FILE.png          write a PNG preview of the result
                """);
    }

    public static void run(String[] args) throws Exception {
        FliMode mode = FliMode.FLI;
        Palette pal = Palettes.PEPTO;
        ColorMatcher.Space space = ColorMatcher.Space.YUV;
        int wu = 25, wv = 50, strength = 80, passes = 2, bg = -1;
        int bright = 0, contrast = 0, gamma = 100, sat = 100;
        boolean luma = false, bleeding = true, serp = false, bug = true, raw = false, prg = true;
        Dither dither = Dither.FLOYD_STEINBERG;
        ImageFitter.ScaleMode scale = ImageFitter.ScaleMode.FIT;
        Path preview = null, in = null, out = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> { usage(); return; }
                case "--mode" -> mode = FliMode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--palette" -> pal = paletteByName(args[++i]);
                case "--space" -> space = ColorMatcher.Space.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--weight-u" -> wu = Integer.parseInt(args[++i]);
                case "--weight-v" -> wv = Integer.parseInt(args[++i]);
                case "--luma" -> luma = true;
                case "--dither" -> dither = ditherByName(args[++i]);
                case "--strength" -> strength = Integer.parseInt(args[++i]);
                case "--no-bleeding" -> bleeding = false;
                case "--serpentine" -> serp = true;
                case "--no-fli-bug" -> bug = false;
                case "--passes" -> passes = Integer.parseInt(args[++i]);
                case "--bg" -> bg = Integer.parseInt(args[++i]);
                case "--brightness" -> bright = Integer.parseInt(args[++i]);
                case "--contrast" -> contrast = Integer.parseInt(args[++i]);
                case "--gamma" -> gamma = Integer.parseInt(args[++i]);
                case "--saturation" -> sat = Integer.parseInt(args[++i]);
                case "--scale" -> scale = ImageFitter.ScaleMode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--raw" -> raw = true;
                case "--no-prg" -> prg = false;
                case "--preview" -> preview = Path.of(args[++i]);
                default -> {
                    if (in == null) in = Path.of(a);
                    else if (out == null) out = Path.of(a);
                    else throw new IllegalArgumentException("Unexpected argument: " + a);
                }
            }
        }
        if (in == null || out == null) { usage(); return; }

        BufferedImage src = ImageIO.read(in.toFile());
        if (src == null) throw new IllegalArgumentException("Unsupported image format: " + in);
        ImageFitter.Settings fs = ImageFitter.Settings.defaults();
        fs = new ImageFitter.Settings(null, scale, fs.filter(), fs.aspect(), fs.fill());
        BufferedImage fitted = ImageFitter.fit(src, fs);
        ImageAdjust.Settings as = new ImageAdjust.Settings(bright, contrast, gamma, sat);
        float[] target = ImageAdjust.toAdjustedRgb(fitted, as, true);

        ConvertSettings cs = new ConvertSettings(mode, pal, space, wu, wv, luma, dither, strength,
                bleeding, serp, bug, passes, bg);
        long t0 = System.currentTimeMillis();
        ConversionResult r = FliConverter.convert(target, cs, (msg, f) -> System.out.printf("  %3.0f%% %s%n", f * 100, msg));
        System.out.printf("Converted in %d ms, mean error %.1f%n", System.currentTimeMillis() - t0, r.meanError(pal));

        if (prg) {
            Files.write(out, PrgBuilder.build(r));
            System.out.println("Wrote " + out);
        }
        if (raw) {
            Path dir = out.toAbsolutePath().getParent();
            String base = out.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            for (Path p : RawExporter.export(r, dir, base)) System.out.println("Wrote " + p);
        }
        if (preview != null) {
            ImageIO.write(r.render(pal), "png", preview.toFile());
            System.out.println("Wrote " + preview);
        }
    }

    static Palette paletteByName(String n) throws Exception {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "pepto" -> Palettes.PEPTO;
            case "colodore" -> Palettes.COLODORE;
            case "deekay" -> Palettes.DEEKAY;
            case "palette" -> Palettes.PALETTE_OFFENCE;
            case "community" -> Palettes.COMMUNITY;
            case "ptoing" -> Palettes.PTOING;
            case "c64hq" -> Palettes.C64HQ;
            case "ccs64" -> Palettes.CCS64;
            case "c64s" -> Palettes.C64S;
            case "vice" -> Palettes.VICE_OLD;
            default -> Palettes.load(Path.of(n));
        };
    }

    static Dither ditherByName(String n) {
        return switch (n.toLowerCase(Locale.ROOT)) {
            case "none" -> Dither.NONE;
            case "fs", "floyd", "floyd-steinberg" -> Dither.FLOYD_STEINBERG;
            case "atkinson" -> Dither.ATKINSON;
            case "jarvis" -> Dither.JARVIS;
            case "stucki" -> Dither.STUCKI;
            case "sierra" -> Dither.SIERRA_LITE;
            case "bayer2" -> Dither.BAYER2;
            case "bayer4" -> Dither.BAYER4;
            case "bayer8" -> Dither.BAYER8;
            default -> throw new IllegalArgumentException("Unknown dither: " + n);
        };
    }
}
