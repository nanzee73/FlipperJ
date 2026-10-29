package de.flipperj;

import de.flipperj.gui.MainWindow;
import de.flipperj.gui.Platform;

import javax.swing.*;
import java.awt.*;

/** Entry point: without arguments the GUI starts, with arguments the command line converter runs. */
public final class App {
    public static final String NAME = "FlipperJ";
    /** Version from the jar manifest (set by the build), "dev" when started from the IDE. */
    public static final String VERSION = version();

    private App() {}

    private static String version() {
        String v = App.class.getPackage() != null ? App.class.getPackage().getImplementationVersion() : null;
        return v != null ? v : "dev";
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Cli.run(args);
            return;
        }
        // macOS: menu bar at the top of the screen, app name, title bar follows light/dark mode.
        // Must happen before the first AWT class is used.
        Platform.initBeforeAwt(NAME);
        de.flipperj.i18n.I18n.init();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to the default look and feel
            }
            // GTK (Linux) draws the slider value above the thumb, where it gets cut off;
            // the value is shown next to each slider anyway
            UIManager.put("Slider.paintValue", Boolean.FALSE);
            de.flipperj.gui.Icons.installTaskbarIcon();
            MainWindow w = new MainWindow();
            installDesktopHandlers(w);
            w.setVisible(true);
        });
    }

    /** macOS application menu: About, Settings… (⌘,) and Quit (⌘Q) call into the app. */
    private static void installDesktopHandlers(MainWindow w) {
        if (!Desktop.isDesktopSupported()) return;
        Desktop d = Desktop.getDesktop();
        try {
            if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler(e -> w.showAbout());
            if (d.isSupported(Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler(e -> w.showOptions());
            if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler((e, r) -> {
                w.dispose();
                r.performQuit();
            });
        } catch (Exception ignored) {
            // not supported on this platform
        }
    }
}
