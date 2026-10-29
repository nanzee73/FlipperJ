package de.flipperj;

import de.flipperj.gui.MainWindow;

import javax.swing.*;

/** Entry point: without arguments the GUI starts, with arguments the command line converter runs. */
public final class App {
    public static final String NAME = "FlipperJ";
    public static final String VERSION = "0.1.0";

    private App() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            Cli.run(args);
            return;
        }
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
            new MainWindow().setVisible(true);
        });
    }
}
