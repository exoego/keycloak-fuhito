package net.exoego.keycloak.fuhito.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalizedTest {

    private static final Localized<String> NAMES = new Localized<>("ja", Map.of("ja", "営業部", "en", "Sales",
            "en-GB", "Sales (UK)", "zh-Hant", "營業部"));

    @Test
    void aReaderGetsTheTagThenTheLanguageThenThePrimaryValue() {
        assertEquals("Sales (UK)", NAMES.in(Locale.forLanguageTag("en-GB")));
        assertEquals("Sales", NAMES.in(Locale.forLanguageTag("en-US")));
        assertEquals("Sales", NAMES.in(Locale.ENGLISH));
        assertEquals("營業部", NAMES.in(Locale.forLanguageTag("zh-Hant")));
        assertEquals("営業部", NAMES.in(Locale.forLanguageTag("zh-Hans")));
        assertEquals("営業部", NAMES.in(Locale.FRENCH));
        assertEquals("営業部", NAMES.in(null));
        assertEquals("営業部", NAMES.primary());
    }

    @Test
    void theValuesFollowTheTags() {
        assertEquals(List.of("en", "en-GB", "ja", "zh-Hant"), List.copyOf(NAMES.byLanguage().keySet()));
        assertEquals(new Localized<>("en", Map.of("en", "Sales")), Localized.of("en", "Sales"));
    }

    @Test
    void thePrimaryLanguageNeedsAValue() {
        assertThrows(IllegalArgumentException.class, () -> new Localized<>("fr", Map.of("ja", "営業部")));
    }

    @Test
    void checksTheNamesOfARequest() {
        Map<String, OrgUnitName> names = new LinkedHashMap<>();
        names.put("JA", new OrgUnitName(" 営業部 ", " 営業 ", "えいぎょうぶ"));
        names.put("en-us", new OrgUnitName("Sales", null, null));

        Localized<OrgUnitName> checked = Localized.check("ja", names, (field, name) -> name.checked(field));

        assertEquals(new Localized<>("ja", Map.of("ja", new OrgUnitName("営業部", "営業", "えいぎょうぶ"),
                "en-US", new OrgUnitName("Sales", null, null))), checked);
    }

    @Test
    void rejectsBadNames() {
        assertEquals(Map.of("field", "primaryLanguage"), invalid(null, Map.of("ja", "x"), "invalid.required"));
        assertEquals(Map.of("field", "primaryLanguage", "value", "ja_JP"),
                invalid("ja_JP", Map.of("ja", "x"), "invalid.language_tag"));
        assertEquals(Map.of("field", "names"), invalid("ja", null, "invalid.required"));
        assertEquals(Map.of("field", "names", "value", "??"), invalid("ja", Map.of("??", "x"), "invalid.language_tag"));
        Map<String, OrgUnitName> missing = new LinkedHashMap<>();
        missing.put("en-us", null);
        assertEquals(Map.of("field", "names.en-US"), invalid("en", missing, "invalid.required"));
        assertEquals(Map.of("value", "fr"), invalid("FR", Map.of("ja", new OrgUnitName("x", null, null)),
                "invalid.primary_language"));
    }

    @Test
    void rejectsBadNamesOfALanguage() {
        assertEquals(Map.of("field", "names.ja.name"),
                invalid("ja", Map.of("ja", new OrgUnitName(null, null, null)), "invalid.required"));
        assertEquals(Map.of("field", "names.ja.shortName"),
                invalid("ja", Map.of("ja", new OrgUnitName("x", " ", null)), "invalid.name"));
        assertEquals(Map.of("field", "names.ja.phoneticName"),
                invalid("ja", Map.of("ja", new OrgUnitName("x", null, "x".repeat(256))), "invalid.name"));
    }

    private static Map<String, String> invalid(String primaryLanguage, Map<String, ?> names, String code) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> Localized.check(primaryLanguage, names,
                (field, name) -> ((OrgUnitName) name).checked(field)));
        assertEquals(code, e.code());
        return e.params();
    }
}
