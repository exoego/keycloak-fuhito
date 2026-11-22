package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestoreCheckTest {

    private static final Map<String, Integer> LENGTHS = Map.of("id", 36, "code", 64, "name", 255, "source", 64);

    private static RealmExport of(List<RealmExport.HierarchyRow> hierarchies, List<RealmExport.Unit> units,
            List<RealmExport.EmploymentRow> employments, List<RealmExport.ExternalIdRow> externalIds,
            List<RealmExport.Definition> definitions) {
        return new RealmExport(RealmExport.FORMAT, null, null, definitions, hierarchies, units, null, employments,
                null, null, null, null, externalIds, null, null);
    }

    private static RealmExport hierarchy(String code, String name) {
        return of(List.of(new RealmExport.HierarchyRow("h-1", 1L, code, name)), null, null, null, null);
    }

    private static RealmExport unit(String source, String timeZone, String dayStart) {
        return of(null, List.of(new RealmExport.Unit("u-1", 1L, source, timeZone, dayStart, List.of(), List.of(),
                List.of(), null)), null, null, null);
    }

    private static RealmExport workPercent(String percent) {
        RealmExport.Terms terms = new RealmExport.Terms("t-1", "2020-04-01", null, null, null, "ACTIVE", null,
                new BigDecimal(percent), null, Map.of());
        return of(null, null, List.of(new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", null, "2020-04-01",
                null, null, null, null, null, List.of(terms))), null, null);
    }

    private static RealmExport terms(String validFrom, String contractEndDate) {
        return terms(validFrom, null, contractEndDate);
    }

    private static RealmExport terms(String validFrom, String validTo, String contractEndDate) {
        return terms(validFrom, validTo, contractEndDate, "ACTIVE");
    }

    private static RealmExport terms(String validFrom, String validTo, String contractEndDate, String status) {
        RealmExport.Terms terms = new RealmExport.Terms("t-1", validFrom, validTo, null, null, status, null, null,
                contractEndDate, Map.of());
        return of(null, null, List.of(new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", null, "2020-04-01",
                null, null, null, null, null, List.of(terms))), null, null);
    }

    private static RealmExport names(String validFrom, String validTo) {
        return names(validFrom, validTo, "ja");
    }

    private static RealmExport names(String validFrom, String validTo, String primaryLanguage) {
        return new RealmExport(RealmExport.FORMAT, null, List.of(new RealmExport.Item("i-1", 1L, "JOB_TITLE", "chief",
                0, Map.of(), true, false, false, false, false, List.of(new RealmExport.ItemNames("n-1", validFrom,
                        validTo, primaryLanguage, Map.of("ja", new RefItemName("Chief")))))), null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static RealmExport person(String primaryLanguage, Map<String, PersonName> names) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, null, List.of(new RealmExport.PersonRow(
                "p-1", 1L, null, primaryLanguage, names, Map.of(), null, null)), null, null, null, null, null, null,
                null, null);
    }

    private static RealmExport erasure(String erasedAt) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, null, null, null, null, null, null, null,
                null, null, List.of(new RealmExport.Erasure("p-1", erasedAt)));
    }

    private static RealmExport externalId(String value) {
        return of(null, null, null, List.of(new RealmExport.ExternalIdRow("x-1", 1L, "PERSON", "p-1", "hr", value)),
                null);
    }

    private static RealmExport order(String externalReference) {
        return new RealmExport(RealmExport.FORMAT, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(new RealmExport.Order("o-1", 1L, null, null, "April", null, null, "2024-04-01", null,
                        null, externalReference, "2024-03-01T00:00:00Z", null, null)), null);
    }

    private static RealmExport attribute(String name) {
        return of(null, null, null, null, List.of(new RealmExport.Definition("d-1", 1L, "PERSON", name, "STRING",
                null, false, false)));
    }

    private static RealmExport link(String hierarchy) {
        return of(List.of(new RealmExport.HierarchyRow("h-1", 1L, "project", "Project")), List.of(
                new RealmExport.Unit("u-1", 1L, null, null, null, List.of(), List.of(), List.of(), null),
                new RealmExport.Unit("u-2", 1L, null, null, null, List.of(), List.of(new RealmExport.Parent("l-1",
                        hierarchy, "u-1", "2020-04-01", null)), List.of(), null)), null, null, null);
    }

    private static void check(RealmExport document) {
        RestoreCheck.requireValues(document, list -> LENGTHS);
    }

    private static void assertBadValue(RealmExport document, String list, String id, String field) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> check(document));
        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", list, "id", id, "field", field), e.params());
    }

    private static void assertBroken(RealmExport document, String list, String id, String field, String rule) {
        FuhitoException e = assertThrows(FuhitoException.class, () -> check(document));
        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", list, "id", id, "field", field, "rule", rule), e.params());
    }

    @Test
    void aTextFitsItsColumnByCharacters() {
        assertDoesNotThrow(() -> check(hierarchy("c".repeat(64), "𠮷".repeat(255))));
        assertBadValue(hierarchy("c".repeat(65), "Project"), "hierarchies", "h-1", "code");
        assertBadValue(hierarchy("project", "𠮷".repeat(256)), "hierarchies", "h-1", "name");
        assertBadValue(unit("s".repeat(65), null, null), "org-units", "u-1", "source");
    }

    @Test
    void aValueBreaksTheRuleOfItsWrite() {
        assertBadValue(hierarchy("a b", "Project"), "hierarchies", "h-1", "code");
        assertBadValue(unit("h r", null, null), "org-units", "u-1", "source");
        assertBadValue(unit(null, "Mars/Base", null), "org-units", "u-1", "timeZone");
        assertBadValue(unit(null, null, "9am"), "org-units", "u-1", "dayStart");
        assertBadValue(workPercent("1000"), "employment-terms", "t-1", "workPercent");
        assertBadValue(externalId("x".repeat(256)), "external-ids", "x-1", "externalId");
        assertBadValue(attribute(" beds"), "attribute-definitions", "d-1", "name");
        assertDoesNotThrow(() -> check(unit("hr", "Asia/Tokyo", "04:00")));
        assertDoesNotThrow(() -> check(workPercent("100.00")));
        assertDoesNotThrow(() -> check(externalId("x".repeat(255))));
        assertDoesNotThrow(() -> check(externalId("😀".repeat(255))));
        assertBadValue(externalId("😀".repeat(256)), "external-ids", "x-1", "externalId");
    }

    @Test
    void aDateOrATimeIsOneThatAWriteTakes() {
        for (String date : List.of("-5000-01-01", "+10000-01-01", "0000-12-31", "someday")) {
            assertBadValue(terms(date, null), "employment-terms", "t-1", "validFrom");
            assertBadValue(terms("2020-04-01", date), "employment-terms", "t-1", "contractEndDate");
            assertBadValue(erasure(date + "T00:00:00Z"), "erasures", "p-1", "erasedAt");
        }
        assertDoesNotThrow(() -> check(terms("0001-01-01", "9999-12-31")));
        assertDoesNotThrow(() -> check(erasure("0001-01-01T00:00:00Z")));
        assertDoesNotThrow(() -> check(erasure("9999-12-31T23:59:59.999999Z")));
    }

    // An export gives a time in UTC with Z, and with 3 or 6 digits of a second when it has a fraction.
    @Test
    void aTimeIsInTheFormThatAnExportGives() {
        for (String time : List.of("2024-03-01T00:00:00Z", "2024-03-01T00:00:00.120Z", "2024-03-01T00:00:00.000100Z",
                "2024-03-01T00:00:00.123456Z")) {
            assertDoesNotThrow(() -> check(erasure(time)), time);
        }
        for (String time : List.of("2024-03-01T09:00:00+09:00", "2024-03-01T00:00:00+00:00",
                "2024-03-01T00:00:00.123456789Z", "2024-03-01T00:00:00.1234567Z", "2024-03-01T00:00:00.12Z",
                "2024-03-01T00:00:00.000Z", "2024-03-01T00:00:00.123000Z", "2024-03-01t00:00:00z",
                "2024-02-29T24:00:00Z", "2024-03-01T23:59:60Z")) {
            assertBadValue(erasure(time), "erasures", "p-1", "erasedAt");
        }
    }

    @Test
    void aPeriodEndsAfterItStarts() {
        assertBadValue(terms("2021-04-01", "2020-04-01", null), "employment-terms", "t-1", "validTo");
        assertBadValue(terms("2020-04-01", "2020-04-01", null), "employment-terms", "t-1", "validTo");
        assertDoesNotThrow(() -> check(terms("2020-04-01", "2020-04-02", null)));
        assertBadValue(names("2020-04-01", "2020-04-01"), "ref-item-names", "n-1", "validTo");
        assertDoesNotThrow(() -> check(names(null, "0001-01-01")));
    }

    @Test
    void aChoiceIsOneThatAWriteTakes() {
        assertBroken(terms("2020-04-01", null, null, "RETIRED"), "employment-terms", "t-1", "status",
                "invalid.choice");
        assertDoesNotThrow(() -> check(terms("2020-04-01", null, null, "ON_LEAVE")));
    }

    @Test
    void theNamesOfARowAreOnesThatAWriteTakes() {
        assertBroken(names(null, null, "en"), "ref-item-names", "n-1", "primaryLanguage",
                "invalid.primary_language");
        assertBroken(person(null, Map.of("ja", new PersonName("Hanako", null))), "people", "p-1", "primaryLanguage",
                "invalid.required");
        assertDoesNotThrow(() -> check(person(null, null)));
    }

    @Test
    void aTextHasNoSpacesAroundItAsInAWrite() {
        assertBadValue(hierarchy("project", "Project "), "hierarchies", "h-1", "name");
        assertBadValue(unit(null, " Asia/Tokyo", null), "org-units", "u-1", "timeZone");
        assertBadValue(unit(null, null, "04:00 "), "org-units", "u-1", "dayStart");
        assertBadValue(externalId("P-1\n"), "external-ids", "x-1", "externalId");
        assertBadValue(order("　DOC-9"), "change-orders", "o-1", "externalReference");
        assertBroken(person("ja", Map.of("ja", new PersonName(" Hanako", null))), "people", "p-1", "names.ja.name",
                "invalid.surrounding_space");
        assertBroken(person("ja", Map.of("ja", new PersonName("Hanako", "はなこ　"))), "people", "p-1",
                "names.ja.phoneticName", "invalid.surrounding_space");
        assertDoesNotThrow(() -> check(hierarchy("project", "My Project")));
        assertDoesNotThrow(() -> check(externalId("P 1")));
        assertDoesNotThrow(() -> check(order("DOC 9")));
    }

    @Test
    void aLanguageTagIsStoredAsTheDocumentHasIt() {
        assertDoesNotThrow(() -> check(person("JA", Map.of("JA", new PersonName("Hanako", null)))));
        assertDoesNotThrow(() -> check(person("en-us", Map.of("en-us", new PersonName("Hanako Yamada", null)))));
        assertDoesNotThrow(() -> check(person("iw", Map.of("iw", new PersonName("Hanako", null)))));
        assertBroken(names(null, null, "JA"), "ref-item-names", "n-1", "primaryLanguage",
                "invalid.primary_language");
        assertBroken(person("ja", Map.of("JA", new PersonName("Hanako", null))), "people", "p-1", "primaryLanguage",
                "invalid.primary_language");
        Map<String, PersonName> twice = new LinkedHashMap<>();
        twice.put("ja", new PersonName("花子", null));
        twice.put("JA", new PersonName("Hanako", null));
        assertBroken(person("ja", twice), "people", "p-1", "names.JA", "invalid.duplicate_language");
    }

    @Test
    void anEmploymentHasOneEmployer() {
        RealmExport document = new RealmExport(RealmExport.FORMAT, null, null, null, null, null, List.of(
                new RealmExport.PersonRow("p-1", 1L, null, null, null, Map.of(), null, null)), List.of(
                new RealmExport.EmploymentRow("e-1", 1L, "p-1", "u-1", "agency", "2020-04-01", null, null, null, null,
                        null, List.of())), null, null, null, null, null, null, null);

        FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreCheck.requireReferences(document));
        assertEquals("restore.bad_value", e.code());
        assertEquals(Map.of("list", "employments", "id", "e-1", "field", "externalEmployerId", "rule",
                "employment.employer_twice"), e.params());
    }

    @Test
    void aUnitGivesTheListsOfItsParentsAndSuccessors() {
        assertBadValue(of(null, List.of(new RealmExport.Unit("u-1", 1L, null, null, null, List.of(), null, List.of(),
                null)), null, null, null), "org-units", "u-1", "parents");
        assertBadValue(of(null, List.of(new RealmExport.Unit("u-1", 1L, null, null, null, List.of(), List.of(), null,
                null)), null, null, null), "org-units", "u-1", "successors");
    }

    @Test
    void aParentLinkNamesAHierarchyOfTheExportOrTheDefaultOne() {
        assertDoesNotThrow(() -> RestoreCheck.requireReferences(link("project")));
        assertDoesNotThrow(() -> RestoreCheck.requireReferences(link("default")));
        for (String code : List.of("race", "Project")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> RestoreCheck.requireReferences(link(code)));
            assertEquals("restore.reference_unknown", e.code());
            assertEquals(Map.of("list", "org-units", "field", "hierarchy", "value", code), e.params());
        }
    }
}
