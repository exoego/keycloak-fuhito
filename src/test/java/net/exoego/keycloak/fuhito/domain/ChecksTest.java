package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void optionalValuesMayBeMissing() {
        assertNull(Checks.optionalCode("code", null));
        assertEquals("a", Checks.optionalCode("code", "a"));
        assertEquals("invalid.code", invalid(() -> Checks.optionalCode("code", "a b")).code());
        assertNull(Checks.optionalName("name", null));
        assertEquals("a", Checks.optionalName("name", " a "));
        assertNull(Checks.optionalDate("date", null));
    }

    @Test
    void readsADate() {
        assertEquals(LocalDate.of(2026, 4, 1), Checks.date("validFrom", "2026-04-01"));
        assertEquals("invalid.required", invalid(() -> Checks.date("validFrom", null)).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2026/04/01", "2026-4-1", "2026-02-30", "today"})
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
    @ValueSource(strings = {"0", "-1", "100.01", "33.333"})
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
    void acceptsNoLocalizedNames() {
        assertEquals(Map.of(), Checks.localizedNames("names", null));
    }

    @Test
    void normalizesLanguageTagsAndKeepsTheOrder() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("JA", " 内科 ");
        names.put("en-us", "Internal Medicine");

        Map<String, String> result = Checks.localizedNames("names", names);

        assertEquals(List.of("ja", "en-US"), List.copyOf(result.keySet()));
        assertEquals("内科", result.get("ja"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ja_JP", "not a tag", "und"})
    void rejectsABadLanguageTag(String tag) {
        FuhitoException e = invalid(() -> Checks.localizedNames("names", Map.of(tag, "x")));
        assertEquals("invalid.language_tag", e.code());
        assertEquals(tag, e.params().get("value"));
    }

    @Test
    void rejectsAMissingLanguageTag() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(null, "x");
        assertEquals("null", invalid(() -> Checks.localizedNames("names", names)).params().get("value"));
    }

    @Test
    void rejectsABlankLocalizedName() {
        FuhitoException e = invalid(() -> Checks.localizedNames("names", Map.of("ja", " ")));
        assertEquals("invalid.name", e.code());
        assertEquals("names.ja", e.params().get("field"));
    }
}
