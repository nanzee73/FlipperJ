package de.flipperj.i18n;

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

class I18nTest {
    private static final Pattern FORMAT = Pattern.compile("%(\\.\\d+)?[sdf]");

    private static Properties load(String file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/de/flipperj/i18n/" + file)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static String file(Locale l) {
        return l.getLanguage().equals("en") ? "messages.properties" : "messages_" + l.getLanguage() + ".properties";
    }

    private static List<String> formats(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    void everyLanguageHasTheSameKeys() throws Exception {
        Set<String> en = new TreeSet<>(load("messages.properties").stringPropertyNames());
        for (Locale l : I18n.SUPPORTED) {
            Set<String> other = new TreeSet<>(load(file(l)).stringPropertyNames());
            Set<String> missing = new TreeSet<>(en);
            missing.removeAll(other);
            Set<String> extra = new TreeSet<>(other);
            extra.removeAll(en);
            assertEquals(0, missing.size() + extra.size(), l + ": missing " + missing + ", extra " + extra);
        }
    }

    @Test
    void placeholdersMatchEnglish() throws Exception {
        Properties en = load("messages.properties");
        for (Locale l : I18n.SUPPORTED) {
            Properties p = load(file(l));
            for (String key : en.stringPropertyNames()) {
                assertEquals(0, formats(en.getProperty(key)).equals(formats(p.getProperty(key))) ? 0 : 1,
                        l + " " + key + ": " + p.getProperty(key));
            }
        }
    }
}
