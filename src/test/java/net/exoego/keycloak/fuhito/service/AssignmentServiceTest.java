package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentServiceTest {

    private final MemoryAssignmentStore store = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employments = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnits = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final AssignmentService service = new AssignmentService(store, employments, orgUnits, refItems);
    private final EmploymentService employmentService = new EmploymentService(employments, refItems, id -> true);
    private final OrgUnitService orgUnitService = new OrgUnitService(orgUnits, refItems, Clock.systemUTC());

    private String employment;
    private String xHospital;
    private String yHospital;
    private String ward;

    @BeforeEach
    void createAnEmploymentAndUnits() {
        employment = employmentService.start(new NewEmployment("alice", "2026-04-01", null, null, null, null, null,
                null, null, null, null)).employment().id();
        xHospital = unit("X Hospital", null);
        yHospital = unit("Y Hospital", null);
        ward = unit("Ward", xHospital);
    }

    private String unit(String name, String parentId) {
        return orgUnitService.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2026-04-01",
                null, null, null, null)).unit().id();
    }

    private RefItem item(RefKind kind, String code, boolean active) {
        RefItem item = new RefItem(kind + "-" + code, kind, code, code, Map.of(), 0, Map.of(), active);
        refItems.insert(item);
        return item;
    }

    private AssignmentValues to(String orgUnitId, String validFrom) {
        return new AssignmentValues(employment, orgUnitId, validFrom, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static AssignmentValues details(String validFrom, String jobTitle, Boolean head) {
        return new AssignmentValues(null, null, validFrom, null, jobTitle, null, null, head, null, null, null, null,
                null, null);
    }

    private FuhitoException addFailure(AssignmentValues request) {
        return assertThrows(FuhitoException.class, () -> service.add(request));
    }

    private static List<String> periods(AssignmentView view) {
        return view.rows().stream()
                .map(r -> r.row().period().from() + " " + r.row().period().to() + " " + r.jobTitle())
                .toList();
    }

    @Test
    void addsAnAssignmentWithOnlyAnEmploymentAUnitAndADate() {
        AssignmentView view = service.add(to(ward, "2026-04-01"));

        assertEquals(36, view.assignmentId().length());
        assertEquals("alice", view.userId());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), view.period());
        assertEquals(1, view.rows().size());
        Assignment row = view.rows().get(0).row();
        assertEquals(view.assignmentId(), row.assignmentId());
        assertEquals(employment, row.employmentId());
        assertEquals(ward, row.orgUnitId());
        assertEquals(view.period(), row.period());
        assertNull(row.jobTitleId());
        assertNull(row.occupationId());
        assertNull(row.kindId());
        assertFalse(row.head());
        assertFalse(row.primary());
        assertNull(row.allocationPercent());
        assertEquals(0, row.displayOrder());
        assertEquals(Map.of(), row.attributes());
        assertNull(view.rows().get(0).jobTitle());
        assertEquals(view, service.get(view.assignmentId()));
    }

    @Test
    void addsAnAssignmentWithEveryValue() {
        RefItem title = item(RefKind.JOB_TITLE, "head-nurse", true);
        RefItem occupation = item(RefKind.OCCUPATION, "nurse", true);
        RefItem kind = item(RefKind.ASSIGNMENT_KIND, "acting", true);

        AssignmentView view = service.add(new AssignmentValues(employment, ward, "2026-04-01", "2027-04-01",
                "head-nurse", "nurse", "acting", true, true, new BigDecimal("60"), 3, Map.of("shift", "night"),
                "A-1", "hr"));

        AssignmentView.Row row = view.rows().get(0);
        assertEquals(new ValidPeriod(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 4, 1)), row.row().period());
        assertEquals(title.id(), row.row().jobTitleId());
        assertEquals("head-nurse", row.jobTitle());
        assertEquals(occupation.id(), row.row().occupationId());
        assertEquals("nurse", row.occupation());
        assertEquals(kind.id(), row.row().kindId());
        assertEquals("acting", row.kind());
        assertTrue(row.row().head());
        assertTrue(row.row().primary());
        assertEquals(new BigDecimal("60"), row.row().allocationPercent());
        assertEquals(3, row.row().displayOrder());
        assertEquals(Map.of("shift", "night"), row.row().attributes());
        assertEquals("A-1", row.row().externalId());
        assertEquals("hr", row.row().source());
    }

    @Test
    void oneEmploymentHoldsManyAssignmentsAtOnceAcrossRoots() {
        String committee = unit("Committee", yHospital);

        service.add(to(ward, "2026-04-01"));
        service.add(to(xHospital, "2026-04-01"));
        service.add(to(yHospital, "2026-04-01"));
        service.add(to(committee, "2026-04-01"));
        service.add(to(ward, "2026-04-01"));

        assertEquals(5, store.rowsOfEmployment(employment).size());
        assertEquals(5, store.rowsOfEmployment(employment).stream().map(Assignment::assignmentId).distinct().count());
    }

    @Test
    void anAssignmentLastsAsLongAsAFixedTermEmployment() {
        String fixed = employmentService.start(new NewEmployment("bob", "2026-04-01", "2027-04-01", null, null, null,
                null, null, null, null, null)).employment().id();

        AssignmentView view = service.add(new AssignmentValues(fixed, ward, "2026-10-01", null, null, null, null,
                null, null, null, null, null, null, null));

        assertEquals(new ValidPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)), view.period());
        assertEquals("bob", view.userId());
    }

    @Test
    void rejectsAnAssignmentOutsideItsEmployment() {
        String fixed = employmentService.start(new NewEmployment("bob", "2026-04-01", "2027-04-01", null, null, null,
                null, null, null, null, null)).employment().id();

        for (String[] dates : new String[][] {{"2026-03-31", null}, {"2026-10-01", "2027-04-02"}, {"2027-04-01", null}}) {
            FuhitoException e = addFailure(new AssignmentValues(fixed, ward, dates[0], dates[1], null, null, null,
                    null, null, null, null, null, null, null));
            assertEquals(dates[0].equals("2027-04-01") ? "invalid.period" : "assignment.outside_employment", e.code());
        }
        FuhitoException e = addFailure(to(ward, "2026-03-31"));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals(Map.of("employmentId", employment, "validFrom", "2026-03-31"), e.params());
    }

    @Test
    void rejectsAnOrgUnitThatDoesNotCoverThePeriod() {
        String later = orgUnitService.create(new NewOrgUnit(null, "Later", null, null, null, null, null, null,
                "2027-04-01", null, null, null, null)).unit().id();
        String ending = unit("Ending", null);
        orgUnitService.end(ending, "2027-04-01");

        assertEquals("assignment.org_unit_not_valid", addFailure(to(later, "2026-10-01")).code());
        FuhitoException e = addFailure(to(ending, "2026-10-01"));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("assignment.org_unit_not_valid", e.code());
        assertEquals(Map.of("orgUnitId", ending, "validFrom", "2026-10-01"), e.params());
        // An assignment that ends with the unit is fine.
        service.add(new AssignmentValues(employment, ending, "2026-10-01", "2027-04-01", null, null, null, null, null,
                null, null, null, null, null));
        assertEquals("assignment.org_unit_unknown", addFailure(to("nope", "2026-10-01")).code());
    }

    @Test
    void rejectsBadValues() {
        item(RefKind.JOB_TITLE, "retired", false);
        item(RefKind.OCCUPATION, "nurse", true);

        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.add(null)).code());
        assertEquals("invalid.required", addFailure(new AssignmentValues(null, ward, "2026-04-01", null, null, null,
                null, null, null, null, null, null, null, null)).code());
        assertEquals("invalid.required", addFailure(to(null, "2026-04-01")).code());
        assertEquals("invalid.required", addFailure(to(ward, null)).code());
        assertEquals("assignment.employment_unknown", addFailure(new AssignmentValues("nope", ward, "2026-04-01",
                null, null, null, null, null, null, null, null, null, null, null)).code());
        for (String[] codes : new String[][] {{"retired", null, null}, {"nurse", null, null}, {null, "nope", null},
                {null, null, "nope"}}) {
            assertEquals("ref_item.unknown", addFailure(new AssignmentValues(employment, ward, "2026-04-01", null,
                    codes[0], codes[1], codes[2], null, null, null, null, null, null, null)).code());
        }
        assertEquals("invalid.percent", addFailure(new AssignmentValues(employment, ward, "2026-04-01", null, null,
                null, null, null, null, new BigDecimal("101"), null, null, null, null)).code());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void aChangeSplitsTheDetailsAndKeepsTheId() {
        item(RefKind.JOB_TITLE, "staff", true);
        item(RefKind.JOB_TITLE, "chief", true);
        item(RefKind.JOB_TITLE, "director", true);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", null, null, null,
                null, null, null, null, "A-1", "hr")).assignmentId();
        service.changeDetails(id, details("2028-04-01", "director", true));

        AssignmentView view = service.changeDetails(id, details("2027-04-01", "chief", null));

        assertEquals(id, view.assignmentId());
        assertEquals(List.of("2026-04-01 2027-04-01 staff", "2027-04-01 2028-04-01 chief", "2028-04-01 null director"),
                periods(view));
        assertEquals(List.of(false, false, true), view.rows().stream().map(r -> r.row().head()).toList());
        assertTrue(view.rows().stream().allMatch(r -> r.row().orgUnitId().equals(ward)
                && r.row().employmentId().equals(employment) && "A-1".equals(r.row().externalId())
                && "hr".equals(r.row().source())));
        assertEquals(3, view.rows().stream().map(r -> r.row().rowId()).distinct().count());
    }

    @Test
    void aChangeOnTheFirstDateCorrectsAndReplacesAllDetails() {
        item(RefKind.JOB_TITLE, "staff", true);
        item(RefKind.OCCUPATION, "nurse", true);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", "nurse", null,
                true, true, new BigDecimal("50"), 4, Map.of("k", "v"), null, null)).assignmentId();
        String rowId = store.rows(id).get(0).rowId();

        AssignmentView view = service.changeDetails(id, details("2026-04-01", null, null));

        assertEquals(1, view.rows().size());
        Assignment row = view.rows().get(0).row();
        assertEquals(rowId, row.rowId());
        assertNull(row.jobTitleId());
        assertNull(row.occupationId());
        assertFalse(row.head());
        assertFalse(row.primary());
        assertNull(row.allocationPercent());
        assertEquals(0, row.displayOrder());
        assertEquals(Map.of(), row.attributes());
    }

    @Test
    void aChangeMayKeepADeactivatedItemButNotTakeANewOne() {
        RefItem old = item(RefKind.JOB_TITLE, "old-title", true);
        item(RefKind.OCCUPATION, "retired", false);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "old-title", null, null,
                null, null, null, null, null, null, null)).assignmentId();
        refItems.update(new RefItem(old.id(), RefKind.JOB_TITLE, "old-title", "old", Map.of(), 0, Map.of(), false));

        assertEquals("old-title", service.changeDetails(id, details("2026-10-01", "old-title", true)).rows().get(1)
                .jobTitle());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.changeDetails(id,
                new AssignmentValues(null, null, "2027-04-01", null, null, "retired", null, null, null, null, null,
                        null, null, null))).code());
        assertEquals(2, store.rows(id).size());
    }

    @Test
    void rejectsABadChange() {
        String id = service.add(to(ward, "2026-04-01")).assignmentId();

        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.changeDetails(id, null)).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.changeDetails(id, details(null, null, null))).code());
        assertEquals("assignment.not_found", assertThrows(FuhitoException.class,
                () -> service.changeDetails("nope", details("2026-10-01", null, null))).code());
        FuhitoException e = assertThrows(FuhitoException.class,
                () -> service.changeDetails(id, details("2026-03-31", null, null)));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("assignment.not_valid_on", e.code());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class,
                () -> service.changeDetails(id, details("2026-10-01", "nope", null))).code());
        assertEquals(1, store.rows(id).size());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), store.rows(id).get(0).period());
    }

    @Test
    void endsAnAssignmentAndCanMoveTheEndEarlier() {
        String id = service.add(to(ward, "2026-04-01")).assignmentId();

        assertEquals(LocalDate.of(2027, 4, 1), service.end(id, "2027-04-01").period().to());
        assertEquals(LocalDate.of(2026, 10, 1), service.end(id, "2026-10-01").period().to());

        FuhitoException e = assertThrows(FuhitoException.class, () -> service.end(id, "2027-04-01"));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("assignment.already_ended", e.code());
        assertEquals(Map.of("id", id, "validTo", "2026-10-01"), e.params());
    }

    @Test
    void rejectsABadEnd() {
        String id = service.add(to(ward, "2026-04-01")).assignmentId();
        service.changeDetails(id, details("2027-04-01", null, true));

        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.end(id, null)).code());
        assertEquals("assignment.not_found",
                assertThrows(FuhitoException.class, () -> service.end("nope", "2028-04-01")).code());
        assertEquals("assignment.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.end(id, "2026-04-01")).code());
        assertEquals("assignment.later_changes_exist",
                assertThrows(FuhitoException.class, () -> service.end(id, "2026-10-01")).code());
        assertEquals("assignment.later_changes_exist",
                assertThrows(FuhitoException.class, () -> service.end(id, "2027-04-01")).code());
        assertEquals(LocalDate.of(2027, 4, 2), service.end(id, "2027-04-02").period().to());
    }

    @Test
    void readsAnAssignmentWhoseEmploymentOrItemIsGone() {
        RefItem title = item(RefKind.JOB_TITLE, "staff", true);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", null, null, null,
                null, null, null, null, null, null)).assignmentId();
        refItems.delete(title.id());
        employments.employments.clear();

        AssignmentView view = service.get(id);

        assertNull(view.userId());
        assertNull(view.rows().get(0).jobTitle());
        assertEquals("assignment.not_found",
                assertThrows(FuhitoException.class, () -> service.get("nope")).code());
    }
}
