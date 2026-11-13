package net.exoego.keycloak.fuhito.domain;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void fillsInTheValues() {
        assertEquals("The code \"hospital\" is already used for the kind UNIT_TYPE.",
                Messages.format("ref_item.code_taken", Map.of("kind", "UNIT_TYPE", "code", "hospital"), Locale.ENGLISH));
    }

    @Test
    void usesJapaneseWhenAsked() {
        assertEquals("コード「hospital」は種類 UNIT_TYPE ですでに使われています。",
                Messages.format("ref_item.code_taken", Map.of("kind", "UNIT_TYPE", "code", "hospital"), Locale.JAPAN));
    }

    @Test
    void usesEnglishForALanguageWithNoBundle() {
        Locale jvmDefault = Locale.getDefault();
        Locale.setDefault(Locale.JAPAN);
        try {
            assertEquals("name is required.",
                    Messages.format("invalid.required", Map.of("field", "name"), Locale.FRENCH));
        } finally {
            Locale.setDefault(jvmDefault);
        }
    }

    @Test
    void everyBundleHasTheSameKeys() throws Exception {
        assertEquals(keys("messages.properties"), keys("messages_ja.properties"));
    }

    private static Set<String> keys(String file) throws Exception {
        Properties properties = new Properties();
        try (var in = new InputStreamReader(
                MessagesTest.class.getResourceAsStream("/net/exoego/keycloak/fuhito/" + file), StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        return new TreeSet<>(properties.stringPropertyNames());
    }

    @Test
    void returnsTheCodeWhenNoMessageExists() {
        assertEquals("no.such.code", Messages.format("no.such.code", Map.of(), Locale.ENGLISH));
    }
}
