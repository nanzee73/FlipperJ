package de.flipperj.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I18nTest {

    private static Set<String> keys(String file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/de/flipperj/i18n/" + file)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    @Test
    void everyLanguageHasTheSameKeys() throws Exception {
        Set<String> en = keys("messages.properties");
        Set<String> de = keys("messages_de.properties");
        Set<String> missing = new TreeSet<>(en);
        missing.removeAll(de);
        Set<String> extra = new TreeSet<>(de);
        extra.removeAll(en);
        assertEquals(0, missing.size() + extra.size(), "missing in de: " + missing + ", extra in de: " + extra);
    }
}
