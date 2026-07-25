package ro.uvt.pokedex.core.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H87 — the bundles must stay key-identical. Drift is the classic i18n bug: a key added only to the Romanian
 * bundle renders as a raw {@code ??key??} placeholder for English readers, and nobody notices because the
 * default locale looks fine.
 */
class MessageBundleParityTest {

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessageBundleParityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("bundle %s must exist", resource).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    void romanianAndEnglishBundlesDeclareTheSameKeys() throws IOException {
        Set<String> ro = new TreeSet<>(load("messages.properties").stringPropertyNames());
        Set<String> en = new TreeSet<>(load("messages_en.properties").stringPropertyNames());

        assertThat(ro).as("keys missing from messages_en.properties").isEqualTo(en);
    }

    @Test
    void noValueIsBlankAndPlaceholdersMatchAcrossBundles() throws IOException {
        Properties ro = load("messages.properties");
        Properties en = load("messages_en.properties");

        for (String key : ro.stringPropertyNames()) {
            String roValue = ro.getProperty(key);
            String enValue = en.getProperty(key);
            assertThat(roValue).as("blank value for %s (ro)", key).isNotBlank();
            assertThat(enValue).as("blank value for %s (en)", key).isNotBlank();
            // A {0} present in one language but not the other means one side silently drops the argument.
            assertThat(placeholders(enValue)).as("placeholder mismatch for %s", key).isEqualTo(placeholders(roValue));
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\d+)}").matcher(value);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
