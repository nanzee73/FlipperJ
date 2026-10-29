package de.flipperj.gui;

import java.awt.*;
import java.util.Locale;

/** Operating system detection and the platform's menu shortcut modifier. */
public final class Platform {
    public static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    private Platform() {}

    /**
     * System properties that must be set before the first AWT class is loaded:
     * menu bar at the top of the screen, application name in the app menu, and the window title bar
     * following the system appearance (light/dark).
     */
    public static void initBeforeAwt(String appName) {
        if (!IS_MAC) return;
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", appName);
        System.setProperty("apple.awt.application.appearance", "system");
    }

    /** Ctrl on Windows/Linux, ⌘ on macOS. */
    public static int menuShortcutMask() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (HeadlessException e) {
            return java.awt.event.InputEvent.CTRL_DOWN_MASK;
        }
    }
}
