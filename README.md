# FlipperJ

A Java re-implementation of the idea behind **Mufflon**: convert any PC picture into C64 FLI graphics,
with a live preview, several palettes, dithering and a runnable `.prg` output.

## Features

| Area | What you get |
|---|---|
| Modes | **FLI** (multicolor, 160×200), **AFLI** (hires FLI, 320×200), **IFLI** (multicolor interlace, two frames). NUFLI/MUIFLI are shown in the UI but not implemented yet. |
| Input | Everything Java ImageIO reads (PNG, JPEG, GIF, BMP) plus the TwelveMonkeys plugins (WebP, TIFF, PSD, TGA, PCX, PNM, IFF/ILBM, SGI, PICT, ICNS), and C64 pictures via C64GfxLib: Koala, Art Studio, Advanced Art Studio, Drazpaint, Drazlace, FLI / FLI Designer / FLI Graph / FLI Editor, AFLI, Gunpaint, Funpaint, NUFLI, MUFLI, MUIFLI and FlipperJ's own raw `.fli/.afl/.ifl` files (also compressed ones), drawn with the selected source palette. Drag & drop supported. The open dialog filters on “All image formats” by default (built from the installed readers); delete/rename buttons of the GTK file chooser are hidden. The output path follows each newly loaded picture (same name, next to the picture or in the output folder you chose). |
| Crop & scale | Opens automatically for pictures that are not 320×200 or 160×200 – nothing is converted before you confirm, *Cancel* keeps the previous picture: draggable crop frame, aspect lock (PAL ≈1.50:1, square pixels, NTSC, free), stretch / fit (letterbox with border colour) / fill, four scaling filters. |
| Palettes | Pepto, Colodore, DeeKay, PALette (PAL/Offence), Community Colors, Ptoing, C64HQ, CCS64, C64S, VICE (old default). Custom palettes: edit in the colour editor or load `.vpl` (VICE), `.gpl` (GIMP) and hex lists. Separate source (matching) and destination (display) palette, like Mufflon. |
| Matching | RGB, YUV (with U/V weights) or HSL; optional “respect luma ramp”. |
| Prepare | Contrast, brightness, gamma, saturation. |
| Dithering | None, Floyd-Steinberg, Atkinson, Jarvis-Judice-Ninke, Stucki, Sierra Lite, Bayer 2×2/4×4/8×8. Strength, serpentine, and **bleeding on/off** (off = diffused error never leaves its cell). |
| Quality | Multiple passes: colour selection is repeated on the values the ditherer wanted; the best pass wins. Error map view. |
| Live preview | The result updates automatically (debounced) whenever a setting changes; *Convert* then only writes the files. Can be switched off. Every settings group has a *Defaults* button. Sliders have ◀/▶ step buttons (hold to repeat), a ▲ mark at the default value, snap to the default while dragging, and reset on double click. |
| Languages | English, German, Polish, Swedish and Dutch. By default the UI follows the operating system language (English if it is not supported); a language chosen under *Language* or in the settings is remembered. Swing's own dialogs follow the chosen language. Texts live in `src/main/resources/de/flipperj/i18n/messages*.properties` – another language is just another file. |
| Settings | *File ▸ Settings…* (macOS: *FlipperJ ▸ Settings…*, ⌘,): language, live preview, pixel grid, linked previews, PAL aspect, default outputs and output folder. Stored in the Java user preferences. |
| macOS | Menu bar at the top of the screen, About / Settings / Quit in the application menu, ⌘ shortcuts, native Finder file dialogs, title bar follows light/dark mode. |
| Reframe in preview | Right click on the original preview (or *File ▸ Reframe in preview*) shows the whole source picture with the 320×200 frame: mouse wheel resizes, left drag moves, right click / Enter applies, Esc cancels. The result preview updates live. |
| Previews | Zoom with the mouse wheel (around the cursor), pan with the right mouse button, double click to fit. Buttons for zoom in/out, fit, 4× and panning. Both previews can be linked; pixel and 8×8 cell grid when zoomed in. |
| Output | Runnable `.prg` with displayer (`LOAD"PIC",8` + `RUN`, or `SYS 2061`) and/or raw data, each switchable by checkbox. |

## Downloads (releases)

Ready-to-run packages are built by GitHub Actions (`.github/workflows/release.yml`) and attached to each release:

| File | System | Start |
|---|---|---|
| `FlipperJ-<version>-macos-arm64.dmg` / `-macos-x64.dmg` | macOS (Apple Silicon / Intel), Java included | open the .dmg, drag FlipperJ to *Applications* |
| `FlipperJ-<version>-windows-x64.msi` | Windows, Java included | install, start from the Start menu |
| `FlipperJ-<version>-windows-x64-portable.zip` | Windows, Java included, no installation | unzip, run `FlipperJ\FlipperJ.exe` |
| `FlipperJ-<version>-windows-x86.msi` / `-windows-x86-portable.zip` | 32-bit Windows, Java included | as above |
| `FlipperJ-<version>-linux-x64.deb` | Debian/Ubuntu, Java included | `sudo apt install ./FlipperJ-…deb`, then *FlipperJ* in the menu |
| `FlipperJ-<version>-linux-x64-portable.tar.gz` | any Linux, Java included | unpack, run `FlipperJ/bin/FlipperJ` |
| `FlipperJ-<version>-all-platforms-java17.zip` | any system with Java 17+ | unzip, run `bin/flipperj` (Linux/macOS) or `bin\flipperj.bat` (Windows) |
| `FlipperJ-<version>-all-platforms-java17.jar` | any system with Java 17+ | `java -jar FlipperJ-<version>-all-platforms-java17.jar` (or double-click) |

**Making a release:** create a tag `vX.Y.Z` (e.g. `v1.0.0`; the first number must be at least 1 because of macOS)
– in IntelliJ via *Git ▸ New Tag…* and *Push* with *Push Tags*, or on GitHub via *Releases ▸ Draft a new release*.
The workflow builds everything in about 10 minutes and attaches the files to the release.
A test build without release: *Actions ▸ Build & Release ▸ Run workflow* (files under the run's *Artifacts*).

**Development build:** every push to `main` that changes code or build files replaces the pre-release
*Development build* (tag `latest-dev`) on the Releases page with fresh packages (`…-dev-…` in the file names).
Changes to the README or other documents build nothing.

The macOS app is not signed (that needs a paid Apple Developer ID). On first start: right-click the app ▸ *Open* ▸ *Open*.

Build a package locally (on the target system): `./gradlew jpackage` (installer) or
`./gradlew jpackage -PinstallerType=app-image` (portable folder); output in `build/jpackage`.

## Shared library

Palettes, colour matching, dithering, the image panel, file dialogs, translations of shared texts and more come from
[C64GfxLib](https://github.com/nanzee73/C64GfxLib) (also used by MuleJ), via [JitPack](https://jitpack.io/#nanzee73/C64GfxLib) – no account or token needed.
To work on the library at the same time, check it out next to FlipperJ and build with `-Pc64gfxLocal`.

## Open in IntelliJ IDEA

1. *File ▸ Open…* and select the project folder (the one containing `build.gradle.kts`).
2. Trust the project; IntelliJ imports it as a Gradle project and downloads Gradle 8.14.3 and the dependencies.
3. Run `de.flipperj.App` (or the Gradle task *application ▸ run*).

Requires a JDK 17 or newer.

Command line:

```
./gradlew run                 # start the GUI
./gradlew test                # run the unit tests
./gradlew installDist         # build/install/flipperj/bin/flipperj(.bat)
```

## Command line usage

Without arguments the GUI starts. With arguments it converts in batch:

```
flipperj --mode fli --palette colodore --dither fs --strength 80 --raw --preview out.png in.jpg out.prg
flipperj --help
```

## How the conversion works

1. The picture is cropped/scaled to 320×200 and adjusted (Prepare). Multicolor modes average pixel pairs.
2. An error table holds the distance of every pixel to all 16 colours (in the chosen colour space).
3. Colour selection respects the hardware: one background (`$D021`) for the whole picture, one colour RAM
   value per 4×8 cell, two screen colours per cell **and per raster line** (that is what FLI adds).
   Every colour RAM value is tried per cell, and for every line the best screen-colour pair out of 136.
4. Dithering is restricted to the colours allowed at each pixel.
5. **IFLI**: frame A is converted first, then frame B (shifted one hires pixel to the right) is optimised
   towards `2 × target − A`, and vice versa in further rounds. Colour RAM is shared, the background may
   differ per frame.

The preview is rendered from the generated C64 data by a VIC-II model (`VicRenderer`), so what you see
is what the data contains; the unit tests check that data and preview match pixel by pixel.

### FLI bug and bottom lines

* Picture line 0 (raster `$33`) is a regular bad line, so it has no FLI bug.
* Lines 1–196 are forced late bad lines; the first 3 character columns read `$FF` (the FLI bug).
  With *Render FLI bug* on, those cells are kept at the background colour in multicolor modes
  (AFLI shows light grey there, which cannot be avoided). Switch it off if you cover the area with sprites.
* Raster lines `$F8`–`$FA` (picture lines 197–199) cannot be bad lines any more, so they reuse the
  screen colours of line 196. The converter takes this into account.

## PRG memory map

| Address | Content |
|---|---|
| `$0801` | BASIC line `10 SYS2061` |
| `$0900` / `$0A00` | `$D018` / `$D011` tables |
| `$0B00` | displayer code (timing-critical part page-aligned) |
| `$3C00–$3FE7` | colour RAM data (copied to `$D800`) |
| `$3FF0` / `$3FF1` | background frame A / B |
| `$4000–$5FFF` | 8 screens (VIC bank 1) |
| `$6000–$7F3F` | bitmap |
| `$8000–$BF3F` | IFLI only: frame B, copied to `$C000` (VIC bank 3) at start |

The displayer needs no raster IRQ: at `$D011=$38` raster line `$30` is a regular bad line (hidden by the
border). A late `$D011` write in line `$32` triggers a bad line, which halts the CPU right after the write
and lets it resume at cycle 55 – from there the timing is deterministic. Line `$33` is a regular bad line,
then the classic 23-cycle FLI loop runs until `$F7`. IFLI toggles bank (`$DD00`), `$D016` X-scroll and
`$D021` every frame.

## Raw data

With *Write raw data* the following files are written next to the `.prg` (`_a`/`_b` for IFLI frames):

* `NAME_bitmap.bin` (8000 bytes), `NAME_screens.bin` (8 × 1024 bytes, picture line *y* uses screen *y & 7*),
  `NAME_colorram.bin` (1000 bytes, multicolor), `NAME_bg.bin` (1 byte)
* `NAME.fli` / `.afl` / `.ifl`: one contiguous image with load address `$3C00`, same layout as the table above.

These layouts are FlipperJ’s own; they are not guaranteed to match FLI Graph, Gunpaint or other editors.

## Undo

*Edit ▸ Undo* (Ctrl+Z / ⌘Z) and *Redo* (Ctrl+Y / ⇧⌘Z) for every setting and the frame, up to 100 steps
(C64GfxLib `UndoHistory`). Loading a picture starts a new history.

## User guide

*Help ▸ User guide* (F1) explains every function in the language of the program (English, German, Polish,
Swedish, Dutch). The pages are `src/main/resources/de/flipperj/help/help_<language>.html`.

## Checking the displayer in VICE

*Help ▸ FLI test picture for VICE…* (or `flipperj --test-picture fli-test.prg`) writes a test picture and
`fli-test_expected.png` next to it. Load the .prg in VICE (PAL) and compare the screen with the PNG:

| Area | Must look like |
|---|---|
| Left 3 columns (FLI bug) | line 0 black, all other lines light grey – a grey line 0 means the first line already has the FLI bug |
| Colour bar | every line its own colour, the colours repeat every 15 lines without gaps or doubled lines |
| Barcode (8 columns) | the line number in binary, white = 1, dark grey = 0 |
| Right area | line 0 white, the first line of every character row red, line 196 yellow, the rest blue |
| Lines 197–199 | repeat the colours of line 196 (yellow), there are no bad lines down there |

A line that shows the colours of its neighbour, or a red line one pixel off the character rows, points to a
timing error in the displayer.

## Status and limitations

* The `.prg` displayer follows the VIC-II timing model (Christian Bauer’s “VIC article”) but has **not been
  verified in VICE (x64sc) or on real hardware yet**. If the first picture line looks wrong, the cycle
  comments in `PrgBuilder` are the place to adjust.
* NUFLI and MUIFLI (sprite underlay, as in Mufflon) are not implemented yet.
* C64 formats as input (Mufflon reads IFLI `.gun/.fun/.fp2` and Drazlace `.drl`) are not supported yet.
* PAL timing only.

## Icon

The leaping-dolphin icon (C64 cyan on C64 blue) lives in `src/main/resources/de/flipperj/icons` (16–512 px PNGs, used for the
window, taskbar/dock and About box). `packaging/` holds the SVG source, a 512 px PNG and a Windows `.ico`,
e.g. for `jpackage --icon packaging/flipperj.ico`.

## License

FlipperJ may be used, copied, changed and passed on for **noncommercial purposes only**, under the
[PolyForm Noncommercial License 1.0.0](LICENSE). For commercial use ask nanzee73 for permission.
Parts by others keep their own licenses, see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Project layout

```
src/main/java/de/flipperj/
  App.java, Cli.java          entry point, command line
  palette/                    palettes, .vpl/.gpl/hex loading
  color/ColorMatcher.java     colour distance in RGB/YUV/HSL
  image/                      crop & scale, prepare
  convert/                    FLI/AFLI/IFLI converter, dithering, VIC-II renderer
  output/                     mini 6502 assembler, PRG builder, raw export
  gui/                        Swing UI
src/test/java/...             JUnit 5 tests
```
