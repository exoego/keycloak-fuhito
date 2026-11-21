package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksTest {

    private static FuhitoException invalid(Runnable check) {
        FuhitoException e = assertThrows(FuhitoException.class, check::run);
        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        return e;
    }

    @Test
    void requiresAValue() {
        assertEquals("x", Checks.required("f", "x"));
        FuhitoException e = invalid(() -> Checks.required("f", null));
        assertEquals("invalid.required", e.code());
        assertEquals(Map.of("field", "f"), e.params());
    }

    @Test
    void requiresAtMostOneOfTwoWays() {
        Checks.notBoth("f", "x", "g", null);
        Checks.notBoth("f", null, "g", "y");
        Checks.notBoth("f", null, "g", null);
        FuhitoException e = invalid(() -> Checks.notBoth("f", "x", "g", "x"));
        assertEquals("invalid.twice", e.code());
        assertEquals(Map.of("field", "f", "otherField", "g"), e.params());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "A1", "unit_type", "a.b-c", "9lives"})
    void acceptsACode(String code) {
        assertEquals(code, Checks.code("code", code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "_a", "-a", ".a", "a b", "部署", "a/b"})
    void rejectsABadCode(String code) {
        assertEquals("invalid.code", invalid(() -> Checks.code("code", code)).code());
    }

    @Test
    void limitsTheLengthOfACode() {
        assertEquals(64, Checks.code("code", "a".repeat(64)).length());
        assertEquals("invalid.code", invalid(() -> Checks.code("code", "a".repeat(65))).code());
        assertEquals("invalid.required", invalid(() -> Checks.code("code", null)).code());
    }

    @Test
    void takesNoOtherValueOfAFieldThatNeverChanges() {
        Checks.fixed("code", "a", "a");
        Checks.fixed("code", "a", null);
        Checks.fixed("code", null, null);
        FuhitoException e = invalid(() -> Checks.fixed("code", "a", "b"));
        assertEquals("invalid.value_fixed", e.code());
        assertEquals(Map.of("field", "code"), e.params());
        assertEquals("invalid.value_fixed", invalid(() -> Checks.fixed("source", null, "hr")).code());
    }

    @Test
    void takesOneOfTheStoredValuesOfAFieldThatTheWriteDoesNotChange() {
        Checks.fixedAmong("parentId", Set.of("a", "b"), "b");
        Checks.fixedAmong("parentId", Set.of("a"), null);
        Checks.fixedAmong("parentId", Set.of(), null);
        FuhitoException e = invalid(() -> Checks.fixedAmong("parentId", Set.of("a", "b"), "c"));
        assertEquals("invalid.value_fixed", e.code());
        assertEquals(Map.of("field", "parentId"), e.params());
        assertEquals("invalid.value_fixed", invalid(() -> Checks.fixedAmong("parentId", Set.of(), "a")).code());
    }

    @Test
    void takesTwoNamesOfOneThing() {
        Checks.agree("personId", "u1", "userId", "u1");
        Checks.agree("personId", "u1", "userId", null);
        Checks.agree("personId", null, "userId", null);
        FuhitoException e = invalid(() -> Checks.agree("personId", "u1", "userId", "u2"));
        assertEquals("invalid.mismatch", e.code());
        assertEquals(Map.of("field", "personId", "otherField", "userId"), e.params());
        // A person with no user has no user to agree with.
        assertEquals("invalid.mismatch", invalid(() -> Checks.agree("personId", null, "userId", "u1")).code());
    }

    @Test
    void stripsAName() {
        assertEquals("内科", Checks.name("name", " 内科　"));
    }

    @Test
    void rejectsABlankOrMissingName() {
        assertEquals("invalid.name", invalid(() -> Checks.name("name", " 　")).code());
        assertEquals("invalid.required", invalid(() -> Checks.name("name", null)).code());
    }

    @Test
    void countsCharactersOfAName() {
        // One character outside the basic plane takes two Java chars.
        String kanji = "𠮷";
        assertEquals(255, Checks.name("name", kanji.repeat(255)).codePointCount(0, 510));
        assertEquals("invalid.name", invalid(() -> Checks.name("name", kanji.repeat(256))).code());
    }

    @Test
    void aTextFitsAColumnByCharacters() {
        assertTrue(Checks.fits("😀".repeat(255), 255));
        assertFalse(Checks.fits("😀".repeat(256), 255));
        assertTrue(Checks.fits("", 0));
    }

    @Test
    void anOptionalTextFitsItsColumn() {
        assertNull(Checks.optionalText("ref", null, 255));
        assertEquals("😀".repeat(255), Checks.optionalText("ref", "😀".repeat(255), 255));
        FuhitoException e = invalid(() -> Checks.optionalText("ref", "x".repeat(256), 255));
        assertEquals("invalid.too_long", e.code());
        assertEquals(Map.of("field", "ref", "max", "255"), e.params());
        assertEquals("invalid.blank", invalid(() -> Checks.optionalText("ref", " ", 255)).code());
    }

    @Test
    void optionalValuesMayBeMissing() {
        assertNull(Checks.optionalCode("code", null));
        assertEquals("a", Checks.optionalCode("code", "a"));
        assertEquals("invalid.code", invalid(() -> Checks.optionalCode("code", "a b")).code());
        assertNull(Checks.optionalName("name", null));
        assertEquals("a", Checks.optionalName("name", " a "));
        assertNull(Checks.optionalDate("date", null));
    }

    @Test
    void anOptionalTextIsNullOrHasText() {
        assertNull(Checks.optionalText("note", null));
        assertEquals(" a ", Checks.optionalText("note", " a "));
        for (String blank : List.of("", " ", "\t", "　")) {
            FuhitoException e = invalid(() -> Checks.optionalText("note", blank));
            assertEquals("invalid.blank", e.code());
            assertEquals(Map.of("field", "note"), e.params());
        }
    }

    @Test
    void aStorableTextHasNoNulAndNoHalfOfASurrogatePair() {
        assertNull(Checks.storable("note", null));
        // Control characters, a whole surrogate pair, a ZWJ sequence, and a combining mark are stored as sent.
        String kept = new String(new int[] {1, 0x1F, 0x7F, 0x2028, 0x1F600, 0x1F468, 0x200D, 0x1F467, 0x304B, 0x3099},
                0, 10);
        assertEquals(kept, Checks.storable("note", kept));
        Map<String, String> bad = Map.of("a" + (char) 0 + "b", "U+0000", "a" + (char) 0xD800 + "b", "U+D800",
                String.valueOf((char) 0xD83D), "U+D83D", (char) 0xDC00 + "a", "U+DC00");
        bad.forEach((text, codePoint) -> {
            FuhitoException e = invalid(() -> Checks.storable("note", text));
            assertEquals("invalid.character", e.code());
            assertEquals(Map.of("field", "note", "codePoint", codePoint), e.params());
        });
    }

    @Test
    void anOptionalListIsNullOrHasItems() {
        assertNull(Checks.optionalList("tags", null));
        assertEquals(List.of("a"), Checks.optionalList("tags", List.of("a")));
        for (Collection<?> empty : List.of(List.of(), Set.of())) {
            FuhitoException e = invalid(() -> Checks.optionalList("tags", empty));
            assertEquals("invalid.blank", e.code());
            assertEquals(Map.of("field", "tags"), e.params());
        }
    }

    @Test
    void requiresTheVersionThatTheResourceHas() {
        Checks.version(3L, 3);
        assertEquals("version.required", invalid(() -> Checks.version(null, 3)).code());
        FuhitoException old = assertThrows(FuhitoException.class, () -> Checks.version(2L, 3));
        assertEquals(FuhitoException.Reason.CONFLICT, old.reason());
        assertEquals("version.conflict", old.code());
        assertEquals(Map.of("version", "3"), old.params());
    }

    @Test
    void readsADate() {
        assertEquals(LocalDate.of(2026, 4, 1), Checks.date("validFrom", "2026-04-01"));
        assertEquals(Checks.FIRST_DATE, Checks.date("validFrom", "0001-01-01"));
        assertEquals(Checks.LAST_DATE, Checks.date("validFrom", "9999-12-31"));
        assertEquals("invalid.required", invalid(() -> Checks.date("validFrom", null)).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026/04/01", "2026-4-1", "2026-02-30", "today", "-5000-01-01", "+10000-01-01",
            "0000-12-31", "+2026-04-01"})
    void rejectsABadDate(String text) {
        FuhitoException e = invalid(() -> Checks.date("validFrom", text));
        assertEquals("invalid.date", e.code());
        assertEquals(Map.of("field", "validFrom", "value", text), e.params());
    }

    @Test
    void buildsAPeriod() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        assertEquals(ValidPeriod.startingAt(from), Checks.period(from, null));
        assertEquals(new ValidPeriod(from, from.plusDays(1)), Checks.period(from, from.plusDays(1)));
        for (LocalDate to : List.of(from, from.minusDays(1))) {
            FuhitoException e = invalid(() -> Checks.period(from, to));
            assertEquals("invalid.period", e.code());
            assertEquals(Map.of("validFrom", "2026-04-01", "validTo", to.toString()), e.params());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "50", "80.5", "100", "100.00"})
    void acceptsAPercentage(String value) {
        assertEquals(new BigDecimal(value), Checks.optionalPercent("workPercent", new BigDecimal(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100.01", "33.333", "1E+999999999", "1E-999999999"})
    void rejectsABadPercentage(String value) {
        FuhitoException e = invalid(() -> Checks.optionalPercent("workPercent", new BigDecimal(value)));
        assertEquals("invalid.percent", e.code());
        assertEquals(Map.of("field", "workPercent", "value", value), e.params());
    }

    @Test
    void readsAnEnumName() {
        assertNull(Checks.optionalPercent("workPercent", null));
        assertEquals(EmploymentStatus.ON_LEAVE,
                Checks.optionalEnum("status", EmploymentStatus.class, "ON_LEAVE", EmploymentStatus.ACTIVE));
        assertEquals(EmploymentStatus.ACTIVE,
                Checks.optionalEnum("status", EmploymentStatus.class, null, EmploymentStatus.ACTIVE));
        FuhitoException e = invalid(() -> Checks.optionalEnum("status", EmploymentStatus.class, "on_leave", null));
        assertEquals("invalid.choice", e.code());
        assertEquals(Map.of("field", "status", "value", "on_leave"), e.params());
        assertEquals(3, EmploymentStatus.values().length);
    }

    @Test
    void normalizesALanguageTag() {
        assertEquals("ja", Checks.languageTag("names", "JA"));
        assertEquals("en-US", Checks.languageTag("names", "en-us"));
        assertEquals("zh-Hant", Checks.languageTag("names", "zh-hant"));
    }

    // The last one is a good tag of 65 characters, one more than the column of a name row has.
    @ParameterizedTest
    @ValueSource(strings = {"", "ja_JP", "not a tag", "und",
        "en-x-12345678-12345678-12345678-12345678-12345678-12345678-123456"})
    void rejectsABadLanguageTag(String tag) {
        FuhitoException e = invalid(() -> Checks.languageTag("names", tag));
        assertEquals("invalid.language_tag", e.code());
        assertEquals(Map.of("field", "names", "value", tag), e.params());
    }

    @Test
    void rejectsAMissingLanguageTag() {
        assertEquals("null", invalid(() -> Checks.languageTag("names", null)).params().get("value"));
    }
}
