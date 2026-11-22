package net.exoego.keycloak.fuhito.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalizedTest {

    private static final Localized<String> NAMES = new Localized<>("ja", Map.of("ja", "営業部", "en", "Sales",
            "en-GB", "Sales (UK)", "zh-Hant", "營業部"));

    @Test
    void aReaderGetsTheTagThenTheLanguageThenThePrimaryValue() {
        assertEquals("Sales (UK)", NAMES.in("en-GB"));
        assertEquals("Sales", NAMES.in("en-US"));
        assertEquals("Sales", NAMES.in("en"));
        assertEquals("營業部", NAMES.in("zh-Hant"));
        assertEquals("営業部", NAMES.in("zh-Hans"));
        assertEquals("営業部", NAMES.in("fr"));
        assertEquals("営業部", NAMES.in(null));
        assertEquals("営業部", NAMES.primary());
    }

    @Test
    void aReaderMatchesATagWithCaseIgnored() {
        Localized<String> names = new Localized<>("JA", Map.of("JA", "営業部", "en-us", "Sales (US)", "EN", "Sales"));
        assertEquals("営業部", names.in("ja"));
        assertEquals("Sales (US)", names.in("EN-US"));
        assertEquals("Sales", names.in("en-GB"));
        assertEquals("Sales", names.in("en"));
    }

    @Test
    void aReaderMatchesNoOtherFormOfATag() {
        Localized<String> names = new Localized<>("ja", Map.of("ja", "営業部", "iw", "מחלקת מכירות"));
        assertEquals("מחלקת מכירות", names.in("iw"));
        assertEquals("מחלקת מכירות", names.in("IW-IL"));
        assertEquals("営業部", names.in("he"));
        assertEquals("営業部", names.in("he-IL"));
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
    void keepsTheTagsOfARequestAsSent() {
        Map<String, OrgUnitName> names = new LinkedHashMap<>();
        names.put("JA", new OrgUnitName("営業部", "営業", "えいぎょうぶ"));
        names.put("en-us", new OrgUnitName("Sales", null, null));
        names.put("iw", new OrgUnitName("מכירות", null, null));

        Localized<OrgUnitName> checked = Localized.check("JA", names, (field, name) -> name.checked(field));

        assertEquals(new Localized<>("JA", Map.of("JA", new OrgUnitName("営業部", "営業", "えいぎょうぶ"),
                "en-us", new OrgUnitName("Sales", null, null), "iw", new OrgUnitName("מכירות", null, null))),
                checked);
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
        assertEquals(Map.of("field", "names.en-us"), invalid("en", missing, "invalid.required"));
        assertEquals(Map.of("value", "FR"), invalid("FR", Map.of("ja", new OrgUnitName("x", null, null)),
                "invalid.primary_language"));
    }

    @Test
    void thePrimaryLanguageIsTheSameTextAsAKey() {
        assertEquals(Map.of("value", "ja"), invalid("ja", Map.of("JA", new OrgUnitName("営業部", null, null)),
                "invalid.primary_language"));
        assertEquals(Map.of("value", "EN-us"), invalid("EN-us", Map.of("en-us", new OrgUnitName("Sales", null,
                null)), "invalid.primary_language"));
    }

    @Test
    void rejectsTwoTagsThatDifferOnlyInCase() {
        Map<String, OrgUnitName> names = new LinkedHashMap<>();
        names.put("ja", new OrgUnitName("営業部", null, null));
        names.put("JA", new OrgUnitName("販売部", null, null));
        assertEquals(Map.of("field", "names.JA", "value", "JA"), invalid("ja", names, "invalid.duplicate_language"));

        Map<String, OrgUnitName> regions = new LinkedHashMap<>();
        regions.put("en-us", new OrgUnitName("Sales", null, null));
        regions.put("en-US", new OrgUnitName("Sales", null, null));
        assertEquals(Map.of("field", "names.en-US", "value", "en-US"),
                invalid("en-US", regions, "invalid.duplicate_language"));
    }

    @Test
    void takesTwoTagsThatNameOneLanguageInTwoWays() {
        Map<String, OrgUnitName> names = new LinkedHashMap<>();
        names.put("iw", new OrgUnitName("מכירות", null, null));
        names.put("he", new OrgUnitName("מכירות", null, null));
        assertEquals(List.of("he", "iw"), List.copyOf(Localized.check("iw", names,
                (field, name) -> name.checked(field)).byLanguage().keySet()));
    }

    @Test
    void checksTheTagsOfAMerge() {
        Map<String, OrgUnitName> removing = new LinkedHashMap<>();
        removing.put("JA", new OrgUnitName("営業部", null, null));
        removing.put("en-us", null);
        Localized.checkLanguages(removing);

        removing.put("ja", null);
        FuhitoException e = assertThrows(FuhitoException.class, () -> Localized.checkLanguages(removing));
        assertEquals("invalid.duplicate_language", e.code());
        assertEquals(Map.of("field", "names.ja", "value", "ja"), e.params());

        e = assertThrows(FuhitoException.class, () -> Localized.checkLanguages(Map.of("en_US", "x")));
        assertEquals("invalid.language_tag", e.code());
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
