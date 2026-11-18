package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemServiceTest {

    private final MemoryRefItemStore store = new MemoryRefItemStore();
    private final RefItemService service = new RefItemService(store);

    private RefItem create(String code, Integer displayOrder) {
        return service.create(RefKind.UNIT_TYPE, code, code + " name", null, displayOrder, null);
    }

    @Test
    void createsAnActiveItemWithDefaults() {
        RefItem item = create("hospital", null);

        assertEquals(36, item.id().length());
        assertEquals("UNIT_TYPE", item.kind());
        assertTrue(item.is(RefKind.UNIT_TYPE));
        assertEquals("hospital", item.code());
        assertEquals("hospital name", item.name());
        assertEquals(Map.of(), item.names());
        assertEquals(0, item.displayOrder());
        assertEquals(Map.of(), item.attributes());
        assertTrue(item.active());
        assertEquals(item, service.get(item.id()));
    }

    @Test
    void keepsLocalizedNamesAndAttributes() {
        RefItem item = service.create(RefKind.UNIT_TYPE, "ward", "病棟", Map.of("en", "Ward"), 3, Map.of("beds", true));

        assertEquals(Map.of("en", "Ward"), item.names());
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
                () -> service.create(null, "a", "A", null, null, null)).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "bad code", "A", null, null, null)).code());
        assertEquals("invalid.name", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "a", " ", null, null, null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.list((String) null, false, null, Page.of(null, null))).code());
    }

    @Test
    void listsByDisplayOrderThenCodeWithoutInactiveItems() {
        RefItem b = create("b", 1);
        RefItem a = create("a", 1);
        RefItem first = create("z", 0);
        RefItem inactive = create("off", 0);
        service.update(inactive.id(), "off", null, 0, null, false);

        assertEquals(List.of(first, a, b), service.list(RefKind.UNIT_TYPE, false, Page.of(null, null)));
        assertEquals(4, service.list(RefKind.UNIT_TYPE, true, Page.of(null, null)).size());
        assertEquals(List.of(a), service.list(RefKind.UNIT_TYPE, false, Page.of(1, 1)));
    }

    @Test
    void updatesEverythingButKindAndCode() {
        RefItem item = create("dept", 5);

        RefItem changed = service.update(item.id(), "Department", Map.of("ja", "部"), 7, Map.of("k", "v"), false);

        assertEquals(item.id(), changed.id());
        assertEquals("dept", changed.code());
        assertEquals("Department", changed.name());
        assertEquals(Map.of("ja", "部"), changed.names());
        assertEquals(7, changed.displayOrder());
        assertEquals(Map.of("k", "v"), changed.attributes());
        assertFalse(changed.active());
        assertEquals(changed, service.get(item.id()));
    }

    @Test
    void updateFallsBackToDefaults() {
        RefItem item = service.create(RefKind.UNIT_TYPE, "dept", "部", Map.of("en", "Dept"), 5, Map.of("k", "v"));

        RefItem changed = service.update(item.id(), "部", null, null, null, null);

        assertEquals(Map.of(), changed.names());
        assertEquals(0, changed.displayOrder());
        assertEquals(Map.of(), changed.attributes());
        assertTrue(changed.active());
    }

    @Test
    void onlyAUnitTypeCanBeMarkedAsALegalEntity() {
        RefItem company = service.create(RefKind.UNIT_TYPE, "company", "Company", null, null, null, new RefItemService.Marks(true, null, null));
        RefItem ward = service.create(RefKind.UNIT_TYPE, "ward", "Ward", null, null, null, new RefItemService.Marks(null, null, null));

        assertTrue(company.legalEntity());
        assertFalse(ward.legalEntity());
        assertTrue(service.get(company.id()).legalEntity());

        // A change that does not speak about the mark keeps it. A change that does can take it away.
        assertTrue(service.update(company.id(), "Corporation", null, null, null, null).legalEntity());
        assertFalse(service.update(company.id(), "Corporation", null, null, null, null, new RefItemService.Marks(null, null, null), null).legalEntity());
        assertTrue(service.update(ward.id(), "Ward", null, null, null, null, new RefItemService.Marks(true, null, null), null).legalEntity());

        FuhitoException wrongKind = assertThrows(FuhitoException.class,
                () -> service.create(RefKind.OCCUPATION, "nurse", "Nurse", null, null, null, new RefItemService.Marks(true, null, null)));
        assertEquals(FuhitoException.Reason.INVALID, wrongKind.reason());
        assertEquals("ref_item.legal_entity_kind", wrongKind.code());
        RefItem grade = service.create(RefKind.GRADE, "g1", "G1", null, null, null, new RefItemService.Marks(false, null, null));
        assertEquals("ref_item.legal_entity_kind", assertThrows(FuhitoException.class,
                () -> service.update(grade.id(), "G1", null, null, null, null, new RefItemService.Marks(true, null, null), null)).code());
    }

    @Test
    void aUnitTypeKeepsItsMarkWhileItMakesAnEmployerALegalEntity() {
        RefItem company = service.create(RefKind.UNIT_TYPE, "company", "Company", null, null, null, new RefItemService.Marks(true, null, null));
        store.typesOfEmployers.add(company.id());

        FuhitoException e = assertThrows(FuhitoException.class,
                () -> service.update(company.id(), "Company", null, null, null, null, new RefItemService.Marks(false, null, null), null));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("ref_item.legal_entity_in_use", e.code());
        assertEquals("ref_item.legal_entity_in_use", assertThrows(FuhitoException.class,
                () -> service.update(company.id(), "Company", null, null, null, null, new RefItemService.Marks(null, null, null), null)).code());
        // Every other change still works.
        assertTrue(service.update(company.id(), "Corporation", null, 5, null, null, new RefItemService.Marks(true, null, null), null).legalEntity());
        assertTrue(service.update(company.id(), "Corporation", null, null, null, null).legalEntity());

        store.typesOfEmployers.clear();
        assertFalse(service.update(company.id(), "Company", null, null, null, null, new RefItemService.Marks(false, null, null), null).legalEntity());
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
                assertThrows(FuhitoException.class, () -> service.update("nope", "x", null, null, null, null)).code());
        assertEquals("ref_item.not_found",
                assertThrows(FuhitoException.class, () -> service.delete("nope")).code());
    }

    @Test
    void anUpdateCanSayThatTheItemStaysActive() {
        RefItem item = create("dept", 5);

        assertTrue(service.update(item.id(), "Department", null, null, Map.of("k", "v"), true).active());
    }

    @Test
    void onlyAnAssignmentKindMarksActingHeads() {
        RefItem acting = service.create(RefKind.ASSIGNMENT_KIND, "acting", "Acting", null, null, null, new RefItemService.Marks(null, true, null));

        assertTrue(acting.actingHead());
        assertTrue(service.update(acting.id(), "Acting", null, null, null, null).actingHead());
        assertFalse(service.update(acting.id(), "Acting", null, null, null, null, new RefItemService.Marks(null, null, null), null).actingHead());
        assertEquals("ref_item.acting_head_kind", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.JOB_TITLE, "chief", "Chief", null, null, null, new RefItemService.Marks(null, true, null))).code());
    }

    @Test
    void aLateralUnitTypeIsNoLegalEntityAndKeepsItsMarkWhileInUse() {
        RefItem committee = service.create(RefKind.UNIT_TYPE, "committee", "Committee", null, null, null,
                new RefItemService.Marks(null, null, true));

        assertTrue(committee.lateral());
        assertEquals("ref_item.lateral_kind", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.JOB_TITLE, "chair", "Chair", null, null, null,
                        new RefItemService.Marks(null, null, true))).code());
        assertEquals("ref_item.lateral_legal_entity", assertThrows(FuhitoException.class,
                () -> service.create(RefKind.UNIT_TYPE, "union", "Union", null, null, null,
                        new RefItemService.Marks(true, null, true))).code());
        assertFalse(service.update(committee.id(), "Committee", null, null, null, null, RefItemService.Marks.NONE, null)
                .lateral());

        store.inUse.add(committee.id());
        assertEquals("ref_item.lateral_in_use", assertThrows(FuhitoException.class,
                () -> service.update(committee.id(), "Committee", null, null, null, null,
                        new RefItemService.Marks(null, null, true), null)).code());
        assertEquals("Board", service.update(committee.id(), "Board", null, null, null, null).name());
    }

    // Today is 2026-06-15 for the tests of dated names.
    private final RefItemService dated = new RefItemService(store, AttributeRules.NONE, kind -> true,
            Today.of(Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneOffset.UTC)));

    private RefItem title(String validFrom) {
        return dated.create("JOB_TITLE", "chief", "Chief", Map.of("ja", "主任"), null, null, RefItemService.Marks.NONE,
                true, validFrom);
    }

    private RefItem rename(RefItem item, String name, String validFrom) {
        return dated.update(item.id(), name, null, null, null, null, RefItemService.Marks.NONE, validFrom);
    }

    private List<String> periods(RefItem item) {
        return dated.history(item.id()).names().stream()
                .map(n -> n.validFrom() + ".." + n.validTo() + " " + n.name())
                .toList();
    }

    @Test
    void anItemWithoutADateHasItsNamesOnEveryDate() {
        RefItem item = title(null);

        assertNull(item.validFrom());
        assertNull(item.validTo());
        assertEquals("Chief", dated.get(item.id(), "1900-01-01").name());
        assertEquals(List.of("null..null Chief"), periods(item));
    }

    @Test
    void aRenameFromADateKeepsTheOldNameForTheDatesBefore() {
        RefItem item = title(null);

        RefItem renamed = rename(item, "Senior Chief", "2027-04-01");

        assertEquals("Senior Chief", renamed.name());
        assertEquals(LocalDate.of(2027, 4, 1), renamed.validFrom());
        assertEquals("Chief", dated.get(item.id(), "2027-03-31").name());
        assertEquals(Map.of("ja", "主任"), dated.get(item.id(), "2027-03-31").names());
        assertEquals("Senior Chief", dated.get(item.id(), "2027-04-01").name());
        assertEquals(Map.of(), dated.get(item.id(), "2027-04-01").names());
        assertEquals("Chief", dated.get(item.id()).name());
        assertEquals(LocalDate.of(2027, 4, 1), dated.get(item.id()).validTo());
        assertEquals(List.of("Chief"), dated.list("JOB_TITLE", false, "2027-03-31", Page.of(null, null))
                .stream().map(RefItem::name).toList());
        assertEquals(List.of("Senior Chief"), dated.list("JOB_TITLE", false, "2027-04-01", Page.of(null, null))
                .stream().map(RefItem::name).toList());
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

        assertEquals("Head", renamed.name());
        assertEquals(List.of("null..2027-04-01 Head", "2027-04-01..null C"), periods(item));
    }

    @Test
    void namesFromADateStartThere() {
        RefItem item = title("2026-04-01");

        assertEquals(LocalDate.of(2026, 4, 1), item.validFrom());
        // The item has no dates of its own, so an earlier date gets the first names.
        assertEquals("Chief", dated.get(item.id(), "2020-01-01").name());
        assertEquals(List.of("2026-04-01..null Chief"), periods(item));
    }

    @Test
    void aRenameBeforeTheFirstNamesLastsUntilThem() {
        RefItem item = title("2026-04-01");

        rename(item, "Assistant", "2020-04-01");

        assertEquals(List.of("2020-04-01..2026-04-01 Assistant", "2026-04-01..null Chief"), periods(item));
        assertEquals("Assistant", dated.get(item.id(), "2019-01-01").name());
    }

    @Test
    void theSameNamesMakeNoNewPeriod() {
        RefItem item = title(null);

        dated.update(item.id(), "Chief", Map.of("ja", "主任"), 3, null, null, RefItemService.Marks.NONE, "2027-04-01");

        assertEquals(List.of("null..null Chief"), periods(item));
        assertEquals(3, dated.get(item.id()).displayOrder());
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
