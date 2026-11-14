package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.EmploymentRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.OrgUnitRow;
import net.exoego.keycloak.fuhito.service.ImportDocument.RefItemRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Counts;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportServiceTest {

    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems,
            Clock.systemUTC());
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final Map<String, String> usernames = Map.of("nurse", "user-nurse", "doctor", "user-doctor");
    private final UserDirectory users = new UserDirectory() {
        @Override
        public boolean exists(String userId) {
            return usernames.containsValue(userId);
        }

        @Override
        public Optional<String> findIdByUsername(String username) {
            return Optional.ofNullable(usernames.get(username));
        }
    };
    private final EmploymentService employments =
            new EmploymentService(employmentStore, assignmentStore, refItems, users,
            new MemoryReportingLineStore());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, employmentStore, orgUnitStore, refItems,
            new MemoryReportingLineStore());
    private final ImportService service = new ImportService(new RefItemService(refItems), refItems, orgUnits,
            orgUnitStore, new ImportService.People(employments, employmentStore, assignments, assignmentStore, users));

    private static ImportDocument doc(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
            List<OrgUnitRow> orgUnits) {
        return new ImportDocument(effectiveDate, dryRun, source, refItems, orgUnits, null, null);
    }

    private static OrgUnitRow unit(String externalId, String name, String parentExternalId) {
        return new OrgUnitRow(null, externalId, null, name, null, null, null, null, null, parentExternalId, null,
                null, null, null);
    }

    private ImportReport importUnits(String effectiveDate, OrgUnitRow... rows) {
        return service.apply(doc(effectiveDate, false, null, null, List.of(rows)));
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

        ImportReport first = service.apply(doc("2026-04-01", false, null, rows, null));
        ImportReport second = service.apply(doc("2026-04-01", false, null, rows, null));

        assertEquals(Map.of("refItems", new Counts(2, 0, 0)), first.counts());
        assertEquals(Map.of("refItems", new Counts(0, 0, 2)), second.counts());
        assertEquals(List.of(), first.errors());
        assertEquals(2, first.writes().size());
        assertTrue(first.writes().getFirst().created());
        assertEquals("FUHITO_REF_ITEM", first.writes().getFirst().resourceType());
        assertEquals("fuhito/ref-items/" + first.writes().getFirst().resourceId(), first.writes().getFirst().path());
        assertEquals(List.of(), second.writes());
        RefItem hospital = refItems.findByCode(RefKind.UNIT_TYPE, "hospital").orElseThrow();
        assertEquals("病院", hospital.name());
        assertEquals(hospital, first.writes().getFirst().state());
        assertFalse(refItems.findByCode(RefKind.OCCUPATION, "nurse").orElseThrow().active());
    }

    @Test
    void changesAReferenceItemThatDiffers() {
        service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "hospital", "病院", null, null, null, null)), null));

        ImportReport report = service.apply(doc("2026-04-01", false, null,
                List.of(new RefItemRow("UNIT_TYPE", "hospital", "総合病院", null, 3, null, false)), null));

        assertEquals(Map.of("refItems", new Counts(0, 1, 0)), report.counts());
        assertFalse(report.writes().getFirst().created());
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

        ImportReport report = service.apply(doc("2026-04-01", false, null, rows, null));

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
        assertEquals(LocalDate.of(2001, 4, 1), versions.getFirst().period().from());
        assertEquals(LocalDate.of(2027, 4, 1), versions.getFirst().period().to());
        assertEquals(2, report.writes().size());
    }

    @Test
    void theSourceSeparatesExternalIds() {
        ImportReport report = service.apply(doc("2026-04-01", false, "hr-a", null, List.of(
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

        ImportReport report = service.apply(doc("2026-04-01", false, null, null, rows));

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

    private static EmploymentRow employment(String externalId, String username, String status, String validTo,
            String endReason) {
        return new EmploymentRow(null, externalId, username, null, null, null, null, status, null, null, null,
                validTo, endReason);
    }

    private static AssignmentRow assignment(String externalId, String employment, String orgUnit, boolean primary,
            String validTo) {
        return new AssignmentRow(null, externalId, employment, orgUnit, null, null, null, null, primary, null, null,
                null, null, validTo);
    }

    private ImportReport importPeople(String effectiveDate, List<EmploymentRow> employmentRows,
            List<AssignmentRow> assignmentRows) {
        return service.apply(new ImportDocument(effectiveDate, false, null, null, null, employmentRows,
                assignmentRows));
    }

    private void importTwoUnits() {
        importUnits("2026-04-01", unit("H", "Hospital", null), unit("W", "Ward", "H"));
    }

    private List<String> assignmentsOf(String employmentExternalId) {
        String id = employmentStore.findByExternalId("import", employmentExternalId).orElseThrow().id();
        return assignmentStore.rowsOfEmployment(id).stream()
                .map(r -> r.externalId() + " " + r.period().from() + " " + r.period().to() + " " + r.primary())
                .sorted()
                .toList();
    }

    @Test
    void importsStaffInOneDocumentAndIsIdempotent() {
        refItems.insert(new RefItem("t", RefKind.EMPLOYMENT_TYPE, "part-time", "x", Map.of(), 0, Map.of(), true));
        refItems.insert(new RefItem("o", RefKind.OCCUPATION, "nurse", "x", Map.of(), 0, Map.of(), true));
        ImportDocument document = new ImportDocument("2026-04-01", false, "hr", null,
                List.of(unit("H", "Hospital", null), unit("W", "Ward", "H")),
                List.of(new EmploymentRow(null, "E1", "nurse", null, "N-1", "part-time", null, null,
                                new BigDecimal("50"), Map.of("k", "v"), "2020-04-01", null, null),
                        new EmploymentRow(null, "E2", null, "user-doctor", null, null, null, null, null, null, null,
                                null, null)),
                List.of(new AssignmentRow(null, "A1", "E1", "W", null, "nurse", null, true, true,
                                new BigDecimal("100"), 2, Map.of("a", 1), "2026-04-01", null),
                        new AssignmentRow(null, "A2", "E2", "H", null, null, null, null, null, null, null, null,
                                null, null)));

        ImportReport first = service.apply(document);
        ImportReport second = service.apply(document);

        assertEquals(List.of(), errors(first));
        assertEquals(new Counts(2, 0, 0), first.counts().get("employments"));
        assertEquals(new Counts(2, 0, 0), first.counts().get("assignments"));
        assertEquals(List.of(), errors(second));
        assertEquals(new Counts(0, 0, 2), second.counts().get("employments"));
        assertEquals(new Counts(0, 0, 2), second.counts().get("assignments"));
        assertEquals(List.of(), second.writes());
        var nurse = employmentStore.findByExternalId("hr", "E1").orElseThrow();
        assertEquals("user-nurse", nurse.userId());
        assertEquals("N-1", nurse.staffNumber());
        assertEquals(LocalDate.of(2020, 4, 1), nurse.period().from());
        assertEquals(List.of("FUHITO_ORG_UNIT", "FUHITO_ORG_UNIT", "FUHITO_EMPLOYMENT", "FUHITO_EMPLOYMENT",
                "FUHITO_ASSIGNMENT", "FUHITO_ASSIGNMENT"),
                first.writes().stream().map(ImportReport.Write::resourceType).toList());
        assertTrue(first.writes().get(2).state() instanceof EmploymentView);
        assertTrue(first.writes().get(4).state() instanceof AssignmentView);
    }

    @Test
    void aSecondDocumentTransfersOnePersonAndEndsAnother() {
        importTwoUnits();
        refItems.insert(new RefItem("r", RefKind.END_REASON, "resignation", "x", Map.of(), 0, Map.of(), true));
        importPeople("2026-04-01",
                List.of(employment("E1", "nurse", null, null, null), employment("E2", "doctor", null, null, null)),
                List.of(assignment("A1", "E1", "W", true, null), assignment("A2", "E2", "H", true, null)));

        // The new primary assignment comes first in the list. The ending one is still applied before it.
        ImportReport report = importPeople("2027-04-01",
                List.of(employment("E1", "nurse", "ON_LEAVE", null, null),
                        employment("E2", "doctor", null, "2027-04-01", "resignation")),
                List.of(assignment("A3", "E1", "H", true, null), assignment("A1", "E1", "W", true, "2027-04-01")));

        assertEquals(List.of(), errors(report));
        assertEquals(new Counts(0, 2, 0), report.counts().get("employments"));
        assertEquals(new Counts(1, 1, 0), report.counts().get("assignments"));
        assertEquals(List.of("A1 2026-04-01 2027-04-01 true", "A3 2027-04-01 null true"), assignmentsOf("E1"));
        assertEquals(List.of("A2 2026-04-01 2027-04-01 true"), assignmentsOf("E2"));
        var doctor = employments.get(employmentStore.findByExternalId("import", "E2").orElseThrow().id());
        assertEquals("resignation", doctor.endReason());
        var nurse = employments.get(employmentStore.findByExternalId("import", "E1").orElseThrow().id());
        assertEquals(List.of("ACTIVE", "ON_LEAVE"),
                nurse.terms().stream().map(t -> t.terms().status().name()).toList());
        assertEquals(List.of("/terms", "/end", "/end", ""), report.writes().stream()
                .map(w -> w.path().substring(w.path().indexOf('/', 7) + 37)).toList());

        // The same second document again changes nothing, the ended rows included.
        ImportReport again = importPeople("2027-04-01",
                List.of(employment("E1", "nurse", "ON_LEAVE", null, null),
                        employment("E2", "doctor", null, "2027-04-01", "resignation")),
                List.of(assignment("A3", "E1", "H", true, null), assignment("A1", "E1", "W", true, "2027-04-01")));
        assertEquals(List.of(), errors(again));
        assertEquals(List.of(), again.writes());
    }

    @Test
    void aChangeOfAssignmentDetailsTakesEffectOnTheDate() {
        importTwoUnits();
        refItems.insert(new RefItem("j", RefKind.JOB_TITLE, "chief", "x", Map.of(), 0, Map.of(), true));
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null)));

        ImportReport report = importPeople("2027-04-01", List.of(),
                List.of(new AssignmentRow(null, "A1", "E1", "W", "chief", null, null, true, false, null, null, null,
                        null, null)));

        assertEquals(new Counts(0, 1, 0), report.counts().get("assignments"));
        String id = assignmentStore.findAssignmentIdByExternalId("import", "A1").orElseThrow();
        assertEquals(List.of("null", "chief"),
                assignments.get(id).rows().stream().map(r -> String.valueOf(r.jobTitle())).toList());
    }

    @Test
    void reportsEveryBadPeopleRow() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null)));
        List<EmploymentRow> employmentRows = new ArrayList<>();
        employmentRows.add(employment("E1", "doctor", null, null, null));
        employmentRows.add(employment("E9", "nobody", null, null, null));
        employmentRows.add(employment("E8", null, null, null, null));
        employmentRows.add(employment(null, "nurse", null, null, null));
        employmentRows.add(null);
        employmentRows.add(employment("E7", "doctor", "GONE", null, null));
        employmentRows.add(employment("E7", "doctor", null, null, null));
        List<AssignmentRow> assignmentRows = new ArrayList<>();
        assignmentRows.add(assignment("A1", "E1", "H", false, null));
        assignmentRows.add(assignment("A9", "NOPE", "W", false, null));
        assignmentRows.add(assignment("A8", "E1", "NOPE", false, null));
        assignmentRows.add(assignment("A7", null, "W", false, null));
        assignmentRows.add(assignment("A6", "E1", null, false, null));
        assignmentRows.add(assignment(null, "E1", "W", false, null));
        assignmentRows.add(null);
        assignmentRows.add(assignment("A5", "E1", "W", false, null));
        assignmentRows.add(assignment("A5", "E1", "W", false, null));

        ImportReport report = importPeople("2026-04-01", employmentRows, assignmentRows);

        assertEquals(List.of(
                "employments[0] import.user_mismatch",
                "employments[1] import.username_unknown",
                "employments[2] invalid.required",
                "employments[3] invalid.required",
                "employments[4] invalid.required",
                "employments[5] invalid.choice",
                "employments[6] import.duplicate_row",
                "assignments[0] import.assignment_mismatch",
                "assignments[1] import.employment_unknown",
                "assignments[2] import.org_unit_unknown",
                "assignments[3] invalid.required",
                "assignments[4] invalid.required",
                "assignments[5] invalid.required",
                "assignments[6] invalid.required",
                "assignments[8] import.duplicate_row"), errors(report));
        assertEquals(new Counts(1, 0, 0), report.counts().get("assignments"));
    }

    @Test
    void anExistingRowMustExistOnTheEffectiveDate() {
        importTwoUnits();
        importPeople("2026-04-01", List.of(employment("E1", "nurse", null, null, null)),
                List.of(assignment("A1", "E1", "W", false, null)));

        ImportReport report = importPeople("2026-03-01", List.of(employment("E1", "nurse", "ON_LEAVE", null, null)),
                List.of(assignment("A1", "E1", "W", true, null)));

        assertEquals(List.of("employments[0] employment.not_valid_on", "assignments[0] assignment.not_valid_on"),
                errors(report));
    }

    @Test
    void rejectsABadDocument() {
        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.apply(null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.apply(doc(null, false, null, null, null))).code());
        assertEquals("invalid.date", assertThrows(FuhitoException.class,
                () -> service.apply(doc("April", false, null, null, null))).code());
        assertEquals("invalid.code", assertThrows(FuhitoException.class,
                () -> service.apply(doc("2026-04-01", false, "bad source", null, null))).code());
        ImportReport empty = service.apply(doc("2026-04-01", true, null, null, null));
        assertEquals(Map.of(), empty.counts());
        assertNull(empty.counts().get("orgUnits"));
        assertEquals(Map.of("refItems", new Counts(0, 0, 0), "orgUnits", new Counts(0, 0, 0)),
                service.apply(doc("2026-04-01", false, null, List.of(), List.of())).counts());
    }
}
