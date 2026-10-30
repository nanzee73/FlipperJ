package de.flipperj.i18n;

import de.c64gfx.swing.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FlipperJ's own texts (the shared ones are checked in C64GfxLib). */
class I18nTest {
    private static final Pattern FORMAT = Pattern.compile("%(\\.\\d+)?[sdf]");

    private static Properties load(Locale l) throws Exception {
        String file = l.getLanguage().equals("en") ? "messages.properties" : "messages_" + l.getLanguage() + ".properties";
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/de/flipperj/i18n/" + file)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static List<String> formats(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    void everyLanguageHasTheSameKeys() throws Exception {
        Set<String> en = new TreeSet<>(load(Locale.ENGLISH).stringPropertyNames());
        for (Locale l : AppConfig.LIBRARY_LANGUAGES) {
            Set<String> other = new TreeSet<>(load(l).stringPropertyNames());
            assertEquals(en, other, l.toString());
        }
    }

    @Test
    void placeholdersMatchEnglish() throws Exception {
        Properties en = load(Locale.ENGLISH);
        for (Locale l : AppConfig.LIBRARY_LANGUAGES) {
            Properties p = load(l);
            for (String key : en.stringPropertyNames())
                assertEquals(formats(en.getProperty(key)), formats(p.getProperty(key)), l + " " + key);
        }
    }
}
