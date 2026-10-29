package de.flipperj.i18n;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * User interface translations.
 * <p>
 * Texts live in {@code de/flipperj/i18n/messages*.properties} (UTF-8). English is the base bundle.
 * Components register their texts with {@code bind(...)}; switching the language re-applies all bindings,
 * so the UI changes language without a restart.
 * <p>
 * By default the UI follows the operating system language (falling back to English if it is not
 * supported). Once the user picks a language explicitly, that choice is stored and wins.
 * Swing's own dialogs (file chooser, color chooser, message boxes) follow via the default locale.
 */
public final class I18n {
    public static final List<Locale> SUPPORTED = List.of(
            Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("pl"), Locale.forLanguageTag("sv"),
            Locale.forLanguageTag("nl"));
    private static final String BUNDLE = "de.flipperj.i18n.messages";
    private static final String PREF_KEY = "language";
    /** The operating system language, captured before the app changes the default locale. */
    private static final Locale SYSTEM = Locale.getDefault();
    private static final List<Runnable> bindings = new ArrayList<>();
    private static Locale locale;
    private static boolean followsSystem = true;
    private static ResourceBundle bundle;

    private I18n() {}

    private static Preferences prefs() { return Preferences.userRoot().node("de/flipperj"); }

    /** Initializes from the stored choice; without one the UI follows the system language. */
    public static synchronized void init() {
        String stored = null;
        try {
            stored = prefs().get(PREF_KEY, null);
        } catch (Exception ignored) {
            // preferences not available
        }
        followsSystem = stored == null || stored.isBlank();
        apply(followsSystem ? SYSTEM : Locale.forLanguageTag(stored));
    }

    /** Supported locale for the given one (same language), English if not supported. */
    public static Locale supported(Locale l) {
        for (Locale s : SUPPORTED) if (s.getLanguage().equals(l.getLanguage())) return s;
        return Locale.ENGLISH;
    }

    /** Language the UI would use when following the system. */
    public static Locale systemLocale() { return supported(SYSTEM); }

    public static boolean followsSystem() { return followsSystem; }

    private static void apply(Locale l) {
        locale = supported(l);
        Locale.setDefault(locale);
        JComponent.setDefaultLocale(locale);
        bundle = ResourceBundle.getBundle(BUNDLE, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** Switches to a fixed language, stores the choice and updates every bound text. */
    public static synchronized void setLocale(Locale l) {
        followsSystem = false;
        apply(l);
        store(locale.toLanguageTag());
        refresh();
    }

    /** Follows the system language again (forgets the stored choice) and updates every bound text. */
    public static synchronized void useSystemLocale() {
        followsSystem = true;
        apply(SYSTEM);
        store(null);
        refresh();
    }

    private static void store(String tag) {
        try {
            if (tag == null) prefs().remove(PREF_KEY);
            else prefs().put(PREF_KEY, tag);
        } catch (Exception ignored) {
            // preferences not available
        }
    }

    private static void refresh() {
        for (Runnable r : new ArrayList<>(bindings)) r.run();
    }

    public static synchronized Locale locale() {
        if (locale == null) init();
        return locale;
    }

    /** Name of a language in that language, e.g. "Deutsch", "Polski". */
    public static String nativeName(Locale l) {
        String n = l.getDisplayLanguage(l);
        return n.isEmpty() ? l.toLanguageTag() : n.substring(0, 1).toUpperCase(l) + n.substring(1);
    }

    /** Translated text; with arguments it is used as a {@link String#format} pattern. */
    public static String tr(String key, Object... args) {
        if (bundle == null) init();
        String s;
        try {
            s = bundle.getString(key);
        } catch (MissingResourceException e) {
            s = key;
        }
        return args.length == 0 ? s : String.format(locale, s, args);
    }

    /** Runs the action now and again after every language switch. */
    public static synchronized void bind(Runnable r) {
        bindings.add(r);
        r.run();
    }

    public static <T extends AbstractButton> T text(T b, String key) {
        bind(() -> b.setText(tr(key)));
        return b;
    }

    public static JLabel label(String key) {
        JLabel l = new JLabel();
        bind(() -> l.setText(tr(key)));
        return l;
    }

    public static <T extends JComponent> T tip(T c, String key, Object... args) {
        bind(() -> c.setToolTipText(tr(key, args)));
        return c;
    }

    public static TitledBorder titled(JComponent owner, String key) {
        TitledBorder b = new TitledBorder("");
        bind(() -> {
            b.setTitle(tr(key));
            owner.repaint();
        });
        return b;
    }
}
