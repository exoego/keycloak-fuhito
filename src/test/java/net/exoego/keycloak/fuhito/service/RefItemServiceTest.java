package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.ItemNames.english;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemServiceTest {

    private final MemoryRefItemStore store = new MemoryRefItemStore();
    private final RefItemService service = new RefItemService(store);

    private RefItem create(String code, Integer displayOrder) {
        return service.create(RefKind.UNIT_TYPE, code, "en", english(code + " name"), displayOrder, null);
    }

    private static String name(RefItem item) {
        return item.names().primary().name();
    }

    @Test
    void createsAnActiveItemWithDefaults() {
        RefItem item = create("hospital", null);

        assertEquals(36, item.id().length());
        assertEquals("UNIT_TYPE", item.kind());
        assertTrue(item.is(RefKind.UNIT_TYPE));
        assertEquals("hospital", item.code());
        assertEquals(ItemNames.of("hospital name"), item.names());
        assertEquals(0, item.displayOrder());
        assertEquals(Map.of(), item.attributes());
        assertTrue(item.active());
        assertEquals(item, service.get(item.id()));
    }

    @Test
    void keepsTheNamesOfEachLanguageAndAttributes() {
        RefItem item = service.create(RefKind.UNIT_TYPE, "ward", "ja",
                Map.of("ja", new RefItemName("病棟"), "en-us", new RefItemName("Ward")), 3, Map.of("beds", true));

        assertEquals(new Localized<>("ja", Map.of("ja", new RefItemName("病棟"), "en-us", new RefItemName("Ward"))),
                item.names());
        assertEquals("Ward", item.names().in("en-US").name());
        assertEquals("病棟", item.names().in("fr").name());
        assertEquals(3, item.displayOrder());
        assertEquals(Map.of("beds", true), item.attributes());
    }

    @Test
    void rejectsACodeThatIsTaken() {
        create("hospital", null);

        FuhitoException e = assertThrows(FuhitoException.class, () -> create("hospital", null));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.code_taken", e.code());
        assertEquals(Map.of("kind", "UNIT_TYPE", "code", "hospital"), e.params());
    }

    @Test
    void rejectsBadValues() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.create(null, "a", "en", english("A"), null, null)).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "bad code", "en", english("A"), null, null)).code());
        assertEquals("invalid.name", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "a", "en", english(" "), null, null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "a", "en", null, null, null)).code());
        FuhitoException primary = assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "a", "ja", english("A"), null, null));
        assertEquals("invalid.primary_language", primary.code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.list((String) null, false, null, Page.of(null, null))).code());
    }

    @Test
    void listsByDisplayOrderThenCodeWithoutInactiveItems() {
        RefItem b = create("b", 1);
        RefItem a = create("a", 1);
        RefItem first = create("z", 0);
        RefItem inactive = create("off", 0);
        service.update(inactive.id(), "en", english("off"), 0, null, false);

        assertEquals(List.of(first, a, b), service.list(RefKind.UNIT_TYPE, false, Page.of(null, null)));
        assertEquals(4, service.list(RefKind.UNIT_TYPE, true, Page.of(null, null)).size());
        assertEquals(List.of(a), service.list(RefKind.UNIT_TYPE, false, Page.of(1, 1)));
    }

    @Test
    void updatesEverythingButKindAndCode() {
        RefItem item = create("dept", 5);
        Map<String, RefItemName> names = Map.of("ja", new RefItemName("部"), "en", new RefItemName("Department"));

        RefItem changed = service.update(item.id(), "ja", names, 7, Map.of("k", "v"), false);

        assertEquals(item.id(), changed.id());
        assertEquals("dept", changed.code());
        assertEquals(new Localized<>("ja", names), changed.names());
        assertEquals(7, changed.displayOrder());
        assertEquals(Map.of("k", "v"), changed.attributes());
        assertFalse(changed.active());
        assertEquals(changed, service.get(item.id()));
    }

    @Test
    void updateFallsBackToDefaults() {
        RefItem item = service.create(RefKind.UNIT_TYPE, "dept", "ja",
                Map.of("ja", new RefItemName("部"), "en", new RefItemName("Dept")), 5, Map.of("k", "v"));

        RefItem changed = service.update(item.id(), "ja", Map.of("ja", new RefItemName("部")), null, null, null);

        assertEquals(Localized.of("ja", new RefItemName("部")), changed.names());
        assertEquals(0, changed.displayOrder());
        assertEquals(Map.of(), changed.attributes());
        assertTrue(changed.active());
    }

    @Test
    void onlyAUnitTypeCanBeMarkedAsALegalEntity() {
        RefItem company = service.create(RefKind.UNIT_TYPE, "company", "en", english("Company"), null, null, new RefItemService.Marks(true, null, null));
        RefItem ward = service.create(RefKind.UNIT_TYPE, "ward", "en", english("Ward"), null, null, new RefItemService.Marks(null, null, null));

        assertTrue(company.legalEntity());
        assertFalse(ward.legalEntity());
        assertTrue(service.get(company.id()).legalEntity());

        // A change that does not speak about the mark keeps it. A change that does can take it away.
        assertTrue(service.update(company.id(), "en", english("Corporation"), null, null, null).legalEntity());
        assertFalse(service.update(company.id(), "en", english("Corporation"), null, null, null, new RefItemService.Marks(null, null, null), null).legalEntity());
        assertTrue(service.update(ward.id(), "en", english("Ward"), null, null, null, new RefItemService.Marks(true, null, null), null).legalEntity());

        FuhitoException wrongKind = assertThrows(FuhitoException.class,
                () -> service.create(RefKind.OCCUPATION, "nurse", "en", english("Nurse"), null, null, new RefItemService.Marks(true, null, null)));
        assertEquals(FuhitoException.Reason.INVALID, wrongKind.reason());
        assertEquals("ref_item.legal_entity_kind", wrongKind.code());
        RefItem grade = service.create(RefKind.GRADE, "g1", "en", english("G1"), null, null, new RefItemService.Marks(false, null, null));
        assertEquals("ref_item.legal_entity_kind", assertThrows(FuhitoException.class,
                () -> service.update(grade.id(), "en", english("G1"), null, null, null, new RefItemService.Marks(true, null, null), null)).code());
    }

    @Test
    void aUnitTypeKeepsItsMarkWhileItMakesAnEmployerALegalEntity() {
        RefItem company = service.create(RefKind.UNIT_TYPE, "company", "en", english("Company"), null, null, new RefItemService.Marks(true, null, null));
        store.typesOfEmployers.add(company.id());

        FuhitoException e = assertThrows(FuhitoException.class,
                () -> service.update(company.id(), "en", english("Company"), null, null, null, new RefItemService.Marks(false, null, null), null));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.legal_entity_in_use", e.code());
        assertEquals("ref_item.legal_entity_in_use", assertThrows(FuhitoException.class,
                () -> service.update(company.id(), "en", english("Company"), null, null, null, new RefItemService.Marks(null, null, null), null)).code());
        // Every other change still works.
        assertTrue(service.update(company.id(), "en", english("Corporation"), 5, null, null, new RefItemService.Marks(true, null, null), null).legalEntity());
        assertTrue(service.update(company.id(), "en", english("Corporation"), null, null, null).legalEntity());

        store.typesOfEmployers.clear();
        assertFalse(service.update(company.id(), "en", english("Company"), null, null, null, new RefItemService.Marks(false, null, null), null).legalEntity());
    }

    @Test
    void deletesAnItem() {
        RefItem item = create("dept", null);

        service.delete(item.id());

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get(item.id()));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("ref_item.not_found", e.code());
    }

    @Test
    void doesNotDeleteAnItemInUse() {
        RefItem item = create("dept", null);
        store.inUse.add(item.id());

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.delete(item.id()));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.in_use", e.code());
        assertEquals(item, service.get(item.id()));
    }

    @Test
    void rejectsAnUnknownId() {
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.update("nope", "en", english("x"), null, null, null)).code());
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.delete("nope")).code());
    }

    @Test
    void anUpdateCanSayThatTheItemStaysActive() {
        RefItem item = create("dept", 5);

        assertTrue(service.update(item.id(), "en", english("Department"), null, Map.of("k", "v"), true).active());
    }

    @Test
    void onlyAnAssignmentKindMarksActingHeads() {
        RefItem acting = service.create(RefKind.ASSIGNMENT_KIND, "acting", "en", english("Acting"), null, null, new RefItemService.Marks(null, true, null));

        assertTrue(acting.actingHead());
        assertTrue(service.update(acting.id(), "en", english("Acting"), null, null, null).actingHead());
        assertFalse(service.update(acting.id(), "en", english("Acting"), null, null, null, new RefItemService.Marks(null, null, null), null).actingHead());
        assertEquals("ref_item.acting_head_kind", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.JOB_TITLE, "chief", "en", english("Chief"), null, null, new RefItemService.Marks(null, true, null))).code());
    }

    @Test
    void aLateralUnitTypeIsNoLegalEntityAndKeepsItsMarkWhileInUse() {
        RefItem committee = service.create(RefKind.UNIT_TYPE, "committee", "en", english("Committee"), null, null,
                new RefItemService.Marks(null, null, true));

        assertTrue(committee.lateral());
        assertEquals("ref_item.lateral_kind", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.JOB_TITLE, "chair", "en", english("Chair"), null, null,
                        new RefItemService.Marks(null, null, true))).code());
        assertEquals("ref_item.lateral_legal_entity", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "union", "en", english("Union"), null, null,
                        new RefItemService.Marks(true, null, true))).code());
        assertFalse(service.update(committee.id(), "en", english("Committee"), null, null, null, RefItemService.Marks.NONE, null)
                .lateral());

        store.inUse.add(committee.id());
        assertEquals("ref_item.lateral_in_use", assertThrows(FuhitoException.class,
                () -> service.update(committee.id(), "en", english("Committee"), null, null, null,
                        new RefItemService.Marks(null, null, true), null)).code());
        assertEquals("Board", name(service.update(committee.id(), "en", english("Board"), null, null, null)));
    }

    @Test
    void aRollupUnitTypeIsNoLateralUnitOrLegalEntityAndKeepsItsMarkWhileInUse() {
        RefItem region = service.create(RefKind.UNIT_TYPE, "region", "en", english("Region"), null, null,
                new RefItemService.Marks(null, null, null, true));

        assertTrue(region.rollup());
        assertFalse(region.lateral());
        assertTrue(store.find(region.id()).orElseThrow().rollup());
        assertEquals("ref_item.rollup_kind", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.JOB_TITLE, "chair", "en", english("Chair"), null, null,
                        new RefItemService.Marks(null, null, null, true))).code());
        assertEquals("ref_item.rollup_lateral", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "area", "en", english("Area"), null, null,
                        new RefItemService.Marks(null, null, true, true))).code());
        assertEquals("ref_item.rollup_legal_entity", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "group", "en", english("Group"), null, null,
                        new RefItemService.Marks(true, null, null, true))).code());
        assertEquals("ref_item.rollup_lateral", assertThrows(FuhitoException.class,
                () -> service.update(region.id(), "en", english("Region"), null, null, null,
                        new RefItemService.Marks(null, null, true, true), null)).code());
        // A change that names no marks keeps them.
        assertTrue(service.update(region.id(), "en", english("Area"), null, null, null).rollup());
        assertFalse(service.update(region.id(), "en", english("Region"), null, null, null, RefItemService.Marks.NONE,
                null).rollup());

        store.inUse.add(region.id());
        assertEquals("ref_item.rollup_in_use", assertThrows(FuhitoException.class,
                () -> service.update(region.id(), "en", english("Region"), null, null, null,
                        new RefItemService.Marks(null, null, null, true), null)).code());
        assertEquals("Kanto", name(service.update(region.id(), "en", english("Kanto"), null, null, null)));
    }

    // Today is 2026-06-15 for the tests of dated names.
    private final RefItemService dated = new RefItemService(store, AttributeRules.NONE, kind -> true,
            Today.of(Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneOffset.UTC)));

    private RefItem title(String validFrom) {
        return dated.create("JOB_TITLE", "chief", "en",
                Map.of("en", new RefItemName("Chief"), "ja", new RefItemName("主任")), null, null,
                RefItemService.Marks.NONE, true, validFrom);
    }

    private RefItem rename(RefItem item, String name, String validFrom) {
        return dated.update(item.id(), "en", english(name), null, null, null, RefItemService.Marks.NONE, validFrom);
    }

    private List<String> periods(RefItem item) {
        return dated.history(item.id()).names().stream()
                .map(n -> n.validFrom() + ".." + n.validTo() + " " + n.names().primary().name())
                .toList();
    }

    @Test
    void anItemWithoutADateHasItsNamesOnEveryDate() {
        RefItem item = title(null);

        assertNull(item.validFrom());
        assertNull(item.validTo());
        assertEquals("Chief", name(dated.get(item.id(), "1900-01-01")));
        assertEquals(List.of("null..null Chief"), periods(item));
    }

    @Test
    void aRenameFromADateKeepsTheOldNameForTheDatesBefore() {
        RefItem item = title(null);

        RefItem renamed = rename(item, "Senior Chief", "2027-04-01");

        assertEquals("Senior Chief", name(renamed));
        assertEquals(LocalDate.of(2027, 4, 1), renamed.validFrom());
        assertEquals("Chief", name(dated.get(item.id(), "2027-03-31")));
        assertEquals("主任", dated.get(item.id(), "2027-03-31").names().in("ja").name());
        assertEquals("Senior Chief", name(dated.get(item.id(), "2027-04-01")));
        assertEquals(ItemNames.of("Senior Chief"), dated.get(item.id(), "2027-04-01").names());
        assertEquals("Chief", name(dated.get(item.id())));
        assertEquals(LocalDate.of(2027, 4, 1), dated.get(item.id()).validTo());
        assertEquals(List.of("Chief"), dated.list("JOB_TITLE", false, "2027-03-31", Page.of(null, null))
                .stream().map(RefItemServiceTest::name).toList());
        assertEquals(List.of("Senior Chief"), dated.list("JOB_TITLE", false, "2027-04-01", Page.of(null, null))
                .stream().map(RefItemServiceTest::name).toList());
        assertEquals(List.of("null..2027-04-01 Chief", "2027-04-01..null Senior Chief"), periods(item));
    }

    @Test
    void aRenameInsideAPastPeriodLastsUntilTheNextStoredNames() {
        RefItem item = title(null);
        rename(item, "C", "2027-04-01");

        rename(item, "B", "2026-10-01");

        assertEquals(List.of("null..2026-10-01 Chief", "2026-10-01..2027-04-01 B", "2027-04-01..null C"),
                periods(item));
    }

    @Test
    void aRenameOnTheFirstDateOfStoredNamesCorrectsThem() {
        RefItem item = title(null);
        rename(item, "C", "2027-04-01");

        rename(item, "D", "2027-04-01");

        assertEquals(List.of("null..2027-04-01 Chief", "2027-04-01..null D"), periods(item));
    }

    @Test
    void aRenameWithoutADateChangesTheNamesOfToday() {
        RefItem item = title(null);
        rename(item, "C", "2027-04-01");

        RefItem renamed = rename(item, "Head", null);

        assertEquals("Head", name(renamed));
        assertEquals(List.of("null..2027-04-01 Head", "2027-04-01..null C"), periods(item));
    }

    @Test
    void namesFromADateStartThere() {
        RefItem item = title("2026-04-01");

        assertEquals(LocalDate.of(2026, 4, 1), item.validFrom());
        // The item has no dates of its own, so an earlier date gets the first names.
        assertEquals("Chief", name(dated.get(item.id(), "2020-01-01")));
        assertEquals(List.of("2026-04-01..null Chief"), periods(item));
    }

    @Test
    void aRenameBeforeTheFirstNamesLastsUntilThem() {
        RefItem item = title("2026-04-01");

        rename(item, "Assistant", "2020-04-01");

        assertEquals(List.of("2020-04-01..2026-04-01 Assistant", "2026-04-01..null Chief"), periods(item));
        assertEquals("Assistant", name(dated.get(item.id(), "2019-01-01")));
    }

    @Test
    void theSameNamesMakeNoNewPeriod() {
        RefItem item = title(null);

        dated.update(item.id(), "en", Map.of("ja", new RefItemName("主任"), "en", new RefItemName("Chief")), 3, null,
                null, RefItemService.Marks.NONE, "2027-04-01");

        assertEquals(List.of("null..null Chief"), periods(item));
        assertEquals(3, dated.get(item.id()).displayOrder());
    }

    @Test
    void anotherPrimaryLanguageIsANewName() {
        RefItem item = title(null);

        dated.update(item.id(), "ja", Map.of("ja", new RefItemName("主任"), "en", new RefItemName("Chief")), null,
                null, null, RefItemService.Marks.NONE, "2027-04-01");

        assertEquals(List.of("null..2027-04-01 Chief", "2027-04-01..null 主任"), periods(item));
    }

    @Test
    void rejectsABadDate() {
        RefItem item = title(null);

        assertEquals("invalid.date", assertThrows(FuhitoException.class, () -> title("tomorrow")).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> rename(item, "X", "2027-13-01")).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class, () -> dated.get(item.id(), "x")).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> dated.list("JOB_TITLE", false, "x", Page.of(null, null))).code());
        assertEquals(List.of("null..null Chief"), periods(item));
    }

    @Test
    void readsTheVersionAndTheHistoryOfAStoredItemOnly() {
        RefItem item = title(null);

        assertEquals(1, dated.version(item.id()));
        assertEquals(item.id(), dated.history(item.id()).item().id());
        assertEquals("ref_item.not_found", assertThrows(FuhitoException.class, () -> dated.version("nope")).code());
        assertEquals("ref_item.not_found", assertThrows(FuhitoException.class, () -> dated.history("nope")).code());
    }
}
