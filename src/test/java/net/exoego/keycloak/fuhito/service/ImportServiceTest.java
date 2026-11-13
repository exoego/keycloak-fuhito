package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Counts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportServiceTest {

    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, new MemoryAssignmentStore(), refItems,
            Clock.systemUTC());
    private final ImportService service = new ImportService(new RefItemService(refItems), refItems, orgUnits,
            orgUnitStore);

    private static OrgUnitRow unit(String externalId, String name, String parentExternalId) {
        return new OrgUnitRow(null, externalId, null, name, null, null, null, null, null, parentExternalId, null,
                null, null, null);
    }

    private ImportReport importUnits(String effectiveDate, OrgUnitRow... rows) {
        return service.apply(new ImportDocument(effectiveDate, false, null, null, List.of(rows)));
    }

    private List<String> tree(String asOf) {
        return orgUnits.list(asOf, false).stream()
                .map(s -> s.version().name() + (s.parentId() == null ? "" : " < "
                        + orgUnits.get(s.parentId(), asOf).version().name()))
                .toList();
    }

    private static List<String> errors(ImportReport report) {
        return report.errors().stream().map(e -> e.list() + "[" + e.row() + "] " + e.error().code()).toList();
    }

    @Test
    void importsReferenceItemsAndIsIdempotent() {
        List<RefItemRow> rows = List.of(
                new RefItemRow("UNIT_TYPE", "hospital", " 病院 ", Map.of("en", "Hospital"), 1, Map.of("k", "v"), null),
                new RefItemRow("OCCUPATION", "nurse", "看護師", null, null, null, false));

        ImportReport first = service.apply(new ImportDocument("2026-04-01", false, null, rows, null));
        ImportReport second = service.apply(new ImportDocument("2026-04-01", false, null, rows, null));

        assertEquals(Map.of("refItems", new Counts(2, 0, 0)), first.counts());
        assertEquals(Map.of("refItems", new Counts(0, 0, 2)), second.counts());
        assertEquals(List.of(), first.errors());
        assertEquals(2, first.writes().size());
        assertTrue(first.writes().get(0).created());
        assertEquals("FUHITO_REF_ITEM", first.writes().get(0).resourceType());
        assertEquals("fuhito/ref-items/" + first.writes().get(0).resourceId(), first.writes().get(0).path());
        assertEquals(List.of(), second.writes());
        RefItem hospital = refItems.findByCode(RefKind.UNIT_TYPE, "hospital").orElseThrow();
        assertEquals("病院", hospital.name());
        assertEquals(hospital, first.writes().get(0).state());
        assertFalse(refItems.findByCode(RefKind.OCCUPATION, "nurse").orElseThrow().active());
    }

    @Test
    void changesAReferenceItemThatDiffers() {
        service.apply(new ImportDocument("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "hospital", "病院", null, null, null, null)), null));

        ImportReport report = service.apply(new ImportDocument("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "hospital", "総合病院", null, 3, null, false)), null));

        assertEquals(Map.of("refItems", new Counts(0, 1, 0)), report.counts());
        assertFalse(report.writes().get(0).created());
        RefItem item = refItems.findByCode(RefKind.UNIT_TYPE, "hospital").orElseThrow();
        assertEquals("総合病院", item.name());
        assertEquals(3, item.displayOrder());
        assertFalse(item.active());
    }

    @Test
    void reportsEveryBadReferenceItemAndStillAppliesTheGoodOnes() {
        List<RefItemRow> rows = new ArrayList<>();
        rows.add(new RefItemRow("UNIT_TYPE", "ok", "OK", null, null, null, null));
        rows.add(new RefItemRow("NOPE", "x", "x", null, null, null, null));
        rows.add(null);
        rows.add(new RefItemRow(null, "x", "x", null, null, null, null));
        rows.add(new RefItemRow("UNIT_TYPE", "bad code", "x", null, null, null, null));
        rows.add(new RefItemRow("UNIT_TYPE", "blank", " ", null, null, null, null));

        ImportReport report = service.apply(new ImportDocument("2026-04-01", false, null, rows, null));

        assertEquals(List.of("refItems[1] invalid.choice", "refItems[2] invalid.required",
                "refItems[3] invalid.required", "refItems[4] invalid.code", "refItems[5] invalid.name"),
                errors(report));
        assertEquals(Map.of("refItems", new Counts(1, 0, 0)), report.counts());
    }

    @Test
    void importsOrgUnitsInAnyOrder() {
        ImportReport report = importUnits("2026-04-01",
                unit("W1", "Ward", "N"),
                unit("N", "Nursing", "H"),
                unit("H", "Hospital", null));

        assertEquals(List.of(), errors(report));
        assertEquals(Map.of("orgUnits", new Counts(3, 0, 0)), report.counts());
        assertEquals(List.of("Hospital", "Nursing < Hospital", "Ward < Nursing"), tree("2026-04-01"));
        assertEquals(List.of("Hospital", "Nursing", "Ward"), report.writes().stream()
                .map(w -> ((OrgUnitState) w.state()).version().name()).toList());
        assertEquals("import", orgUnitStore.findUnitByExternalId("import", "H").orElseThrow().source());
    }

    @Test
    void theSameDocumentTwiceChangesNothing() {
        OrgUnitRow[] rows = {
            new OrgUnitRow("hr", "H", "hq", " Hospital ", Map.of("ja", "病院"), "H", Map.of("ja", "病"), "ほすぴたる",
                    null, null, 2, Map.of("beds", 100), null, null),
            new OrgUnitRow("hr", "N", null, "Nursing", null, null, null, null, null, "H", null, null, null, null)};
        importUnits("2026-04-01", rows);

        ImportReport again = importUnits("2026-04-01", rows);
        ImportReport later = importUnits("2027-04-01", rows);

        assertEquals(Map.of("orgUnits", new Counts(0, 0, 2)), again.counts());
        assertEquals(Map.of("orgUnits", new Counts(0, 0, 2)), later.counts());
        assertEquals(List.of(), later.writes());
        assertEquals(1, orgUnitStore.versions(orgUnitStore.findUnitByExternalId("hr", "H").orElseThrow().id()).size());
    }

    @Test
    void aChangedDocumentRenamesMovesAndEndsFromTheEffectiveDate() {
        importUnits("2026-04-01", unit("H", "Hospital", null), unit("A", "A", "H"), unit("B", "B", "H"),
                unit("C", "C", "A"));

        ImportReport report = importUnits("2027-04-01",
                unit("H", "General Hospital", null),
                unit("C", "C", "B"),
                new OrgUnitRow(null, "A", null, "A", null, null, null, null, null, "H", null, null, null,
                        "2027-04-01"),
                unit("B", "B", "H"),
                unit("D", "D", "B"));

        assertEquals(List.of(), errors(report));
        assertEquals(Map.of("orgUnits", new Counts(1, 3, 1)), report.counts());
        assertEquals(List.of("Hospital", "A < Hospital", "C < A", "B < Hospital"), tree("2027-03-31"));
        assertEquals(List.of("General Hospital", "B < General Hospital", "C < B", "D < B"), tree("2027-04-01"));
        assertEquals(List.of("", "/parent", "/end", ""), report.writes().stream()
                .map(w -> w.path().substring("fuhito/org-units/".length() + 36)).toList());
        // The same document again finds nothing to do, the ended unit included.
        assertEquals(new Counts(0, 0, 4), importUnits("2027-03-31",
                unit("H", "Hospital", null), unit("B", "B", "H"), unit("C", "C", "A"),
                new OrgUnitRow(null, "A", null, "A", null, null, null, null, null, "H", null, null, null,
                        "2027-04-01")).counts().get("orgUnits"));
    }

    @Test
    void aNewUnitMayHaveItsOwnStartDateAndAnEndDate() {
        ImportReport report = importUnits("2026-04-01",
                new OrgUnitRow(null, "OLD", null, "Old", null, null, null, null, null, null, null, null, "2001-04-01",
                        "2027-04-01"));

        assertEquals(List.of(), errors(report));
        var versions = orgUnitStore.versions(orgUnitStore.findUnitByExternalId("import", "OLD").orElseThrow().id());
        assertEquals(LocalDate.of(2001, 4, 1), versions.get(0).period().from());
        assertEquals(LocalDate.of(2027, 4, 1), versions.get(0).period().to());
        assertEquals(2, report.writes().size());
    }

    @Test
    void theSourceSeparatesExternalIds() {
        ImportReport report = service.apply(new ImportDocument("2026-04-01", false, "hr-a", null, List.of(
                unit("1", "From A", null),
                new OrgUnitRow("hr-b", "1", null, "From B", null, null, null, null, null, null, null, null, null,
                        null),
                new OrgUnitRow("hr-b", "2", null, "Child of B", null, null, null, null, null, "1", null, null, null,
                        null))));

        assertEquals(List.of(), errors(report));
        assertEquals(List.of("From A", "From B", "Child of B < From B"), tree("2026-04-01"));
        assertEquals("hr-a", orgUnitStore.findUnitByExternalId("hr-a", "1").orElseThrow().source());
    }

    @Test
    void aParentMayBeStoredAlready() {
        importUnits("2026-04-01", unit("H", "Hospital", null));

        assertEquals(List.of(), errors(importUnits("2026-10-01", unit("N", "Nursing", "H"))));
        assertEquals(List.of("Hospital", "Nursing < Hospital"), tree("2026-10-01"));
    }

    @Test
    void reportsEveryBadOrgUnitRow() {
        List<OrgUnitRow> rows = new ArrayList<>();
        rows.add(unit("OK", "OK", null));
        rows.add(unit(null, "No ID", null));
        rows.add(null);
        rows.add(unit("OK", "Duplicate", null));
        rows.add(unit("ORPHAN", "Orphan", "NOPE"));
        rows.add(unit("BLANK", " ", null));
        rows.add(unit("CHILD", "Child of blank", "BLANK"));
        rows.add(unit("X", "X", "Y"));
        rows.add(unit("Y", "Y", "X"));
        rows.add(new OrgUnitRow("bad source", "S", null, "S", null, null, null, null, null, null, null, null, null,
                null));
        rows.add(new OrgUnitRow(null, "TYPE", null, "T", null, null, null, null, "nope", null, null, null, null,
                null));

        ImportReport report = service.apply(new ImportDocument("2026-04-01", false, null, null, rows));

        assertEquals(List.of(
                "orgUnits[1] invalid.required",
                "orgUnits[2] invalid.required",
                "orgUnits[3] import.duplicate_row",
                "orgUnits[4] import.parent_unknown",
                "orgUnits[5] invalid.name",
                "orgUnits[6] import.parent_failed",
                "orgUnits[8] import.parent_cycle",
                "orgUnits[7] import.parent_failed",
                "orgUnits[9] invalid.code",
                "orgUnits[10] org_unit.unit_type_unknown"), errors(report));
        assertEquals(new Counts(1, 0, 0), report.counts().get("orgUnits"));
        assertEquals(Map.of("parentExternalId", "NOPE"), report.errors().get(3).error().params());
    }

    @Test
    void anExistingUnitMustExistOnTheEffectiveDate() {
        importUnits("2026-04-01", unit("H", "Hospital", null));

        assertEquals(List.of("orgUnits[0] org_unit.not_valid_on"),
                errors(importUnits("2026-03-01", unit("H", "Renamed", null))));
    }

    @Test
    void rejectsABadDocument() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.apply(null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.apply(new ImportDocument(null, false, null, null, null))).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.apply(new ImportDocument("April", false, null, null, null))).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.apply(new ImportDocument("2026-04-01", false, "bad source", null, null))).code());
        ImportReport empty = service.apply(new ImportDocument("2026-04-01", true, null, null, null));
        assertEquals(Map.of(), empty.counts());
        assertNull(empty.counts().get("orgUnits"));
        assertEquals(Map.of("refItems", new Counts(0, 0, 0), "orgUnits", new Counts(0, 0, 0)),
                service.apply(new ImportDocument("2026-04-01", false, null, List.of(), List.of())).counts());
    }
}
