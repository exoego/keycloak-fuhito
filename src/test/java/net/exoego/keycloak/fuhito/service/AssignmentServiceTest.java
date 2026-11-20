package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssignmentServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private final MemoryAssignmentStore store = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employments = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnits = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryReportingLineStore lines = new MemoryReportingLineStore();
    private final AssignmentService service = new AssignmentService(store, externalIds, employments, orgUnits, refItems, lines);
    private final EmploymentService employmentService = new EmploymentService(employments, externalIds, store, refItems,
            MemoryPersonStore.anyUser(),
            new MemoryReportingLineStore(), new MemoryUnitRoleStore(), new AnyEmployer());
    private final OrgUnitService orgUnitService = new OrgUnitService(orgUnits, externalIds, store, refItems, Clock.systemUTC());

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
        return orgUnitService.create(new NewOrgUnit(null, "ja", ja(name), null, parentId, "2026-04-01",
                null, null, null, null)).unit().id();
    }

    private RefItem item(RefKind kind, String code, boolean active) {
        RefItem item = new RefItem(kind + "-" + code, kind, code, ItemNames.of(code), 0, Map.of(), active);
        refItems.insert(item);
        return item;
    }

    private AssignmentValues to(String orgUnitId, String validFrom) {
        return new AssignmentValues(employment, orgUnitId, validFrom, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    private static AssignmentValues details(String validFrom, String jobTitle, Boolean head) {
        return new AssignmentValues(null, null, validFrom, null, jobTitle, null, null, head, null, null, null, null, null,
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
        Assignment row = view.rows().getFirst().row();
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
        assertNull(view.rows().getFirst().jobTitle());
        assertEquals(view, service.get(view.assignmentId()));
    }

    @Test
    void aMoveEndsOneAssignmentAndStartsTheNextInAnotherOrgUnit() {
        item(RefKind.JOB_TITLE, "head-nurse", true);
        item(RefKind.JOB_TITLE, "staff", true);
        RefItem occupation = item(RefKind.OCCUPATION, "nurse", true);
        String old = service.add(new AssignmentValues(employment, ward, "2026-04-01", "2030-04-01", "head-nurse",
                "nurse", null, true, true, null, new BigDecimal("60"), 3, Map.of("shift", "night"), "A-1", "hr"))
                .assignmentId();
        lines.insert(new ReportingLine("line", old, "boss", "boss", ReportingLine.Kind.SOLID,
                ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), 1));

        AssignmentService.Move move = service.move(old, new MoveRequest(yHospital, "2027-04-01", null, null, null));

        assertEquals(old, move.ended().assignmentId());
        assertEquals(LocalDate.of(2027, 4, 1), move.ended().period().to());
        AssignmentView started = move.started();
        Assignment row = started.rows().getFirst().row();
        assertEquals(old, started.continuesAssignmentId());
        assertEquals(employment, row.employmentId());
        assertEquals(yHospital, row.orgUnitId());
        // The new assignment lasts as long as the old one would have.
        assertEquals(new ValidPeriod(LocalDate.of(2027, 4, 1), LocalDate.of(2030, 4, 1)), row.period());
        // What belongs to the person is kept. What belongs to the old org unit is not.
        assertEquals("head-nurse", started.rows().getFirst().jobTitle());
        assertEquals(occupation.id(), row.occupationId());
        assertTrue(row.primary());
        assertEquals(new BigDecimal("60"), row.allocationPercent());
        assertEquals(Map.of("shift", "night"), row.attributes());
        assertFalse(row.head());
        assertEquals(0, row.displayOrder());
        assertNull(row.source());
        assertEquals(List.of(), externalIds.ofResource(ExternalId.Kind.ASSIGNMENT, started.assignmentId()));
        // The line to the old manager ends with the old assignment.
        assertEquals(LocalDate.of(2027, 4, 1), lines.find("line").orElseThrow().period().to());
        assertEquals(List.of(), lines.findByAssignment(started.assignmentId()));

        // A second move gives a new job title, and the person leads the new org unit.
        AssignmentService.Move back = service.move(started.assignmentId(),
                new MoveRequest(ward, "2028-04-01", "staff", true, 7));
        Assignment again = back.started().rows().getFirst().row();
        assertEquals("staff", back.started().rows().getFirst().jobTitle());
        assertTrue(again.head());
        assertEquals(7, again.displayOrder());
        assertEquals(started.assignmentId(), back.started().continuesAssignmentId());
    }

    @Test
    void aMoveIsRejectedWhenItCannotHappen() {
        String old = service.add(new AssignmentValues(employment, ward, "2026-04-01", "2030-04-01", null, null, null,
                null, null, null, null, null, null, null, null)).assignmentId();
        String late = orgUnitService.create(new NewOrgUnit(null, "ja", ja("Late"), null, null,
                "2029-04-01", null, null, null, null)).unit().id();

        assertEquals("assignment.same_org_unit", assertThrows(FuhitoException.class,
                () -> service.move(old, new MoveRequest(ward, "2027-04-01", null, null, null))).code());
        for (String date : List.of("2026-04-01", "2025-04-01", "2030-04-01")) {
            assertEquals("assignment.not_valid_on", assertThrows(FuhitoException.class,
                    () -> service.move(old, new MoveRequest(yHospital, date, null, null, null))).code(), date);
        }
        assertEquals("assignment.org_unit_not_valid", assertThrows(FuhitoException.class,
                () -> service.move(old, new MoveRequest(late, "2027-04-01", null, null, null))).code());
        assertEquals("assignment.org_unit_unknown", assertThrows(FuhitoException.class,
                () -> service.move(old, new MoveRequest("nope", "2027-04-01", null, null, null))).code());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class,
                () -> service.move(old, new MoveRequest(yHospital, "2027-04-01", "nope", null, null))).code());
        assertEquals("invalid.required", assertThrows(FuhitoException.class,
                () -> service.move(old, new MoveRequest(null, "2027-04-01", null, null, null))).code());
        assertEquals("assignment.not_found", assertThrows(FuhitoException.class,
                () -> service.move("nope", new MoveRequest(yHospital, "2027-04-01", null, null, null))).code());
        // A change that is stored for a later date would be cut off.
        service.changeDetails(old, details("2028-04-01", null, true));
        assertEquals("assignment.later_changes_exist", assertThrows(FuhitoException.class,
                () -> service.move(old, new MoveRequest(yHospital, "2027-04-01", null, null, null))).code());
        assertEquals(1, store.rows.stream().map(Assignment::assignmentId).distinct().count());
    }

    @Test
    void addsAnAssignmentWithEveryValue() {
        RefItem title = item(RefKind.JOB_TITLE, "head-nurse", true);
        RefItem occupation = item(RefKind.OCCUPATION, "nurse", true);
        RefItem kind = item(RefKind.ASSIGNMENT_KIND, "acting", true);

        AssignmentView view = service.add(new AssignmentValues(employment, ward, "2026-04-01", "2027-04-01",
                "head-nurse", "nurse", "acting", true, true, null, new BigDecimal("60"), 3, Map.of("shift", "night"),
                "A-1", "hr"));

        AssignmentView.Row row = view.rows().getFirst();
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
        assertEquals(Optional.of(view.assignmentId()), externalIds.idOf(ExternalId.Kind.ASSIGNMENT, "hr", "A-1"));
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
                null, null, null, null, null, null, null, null));

        assertEquals(new ValidPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 4, 1)), view.period());
        assertEquals("bob", view.userId());
    }

    @Test
    void rejectsAnAssignmentOutsideItsEmployment() {
        String fixed = employmentService.start(new NewEmployment("bob", "2026-04-01", "2027-04-01", null, null, null,
                null, null, null, null, null)).employment().id();

        for (String[] dates : new String[][] {{"2026-03-31", null}, {"2026-10-01", "2027-04-02"}, {"2027-04-01", null}}) {
            FuhitoException e = addFailure(new AssignmentValues(fixed, ward, dates[0], dates[1], null, null, null,
                    null, null, null, null, null, null, null, null));
            assertEquals(dates[0].equals("2027-04-01") ? "invalid.period" : "assignment.outside_employment", e.code());
        }
        FuhitoException e = addFailure(to(ward, "2026-03-31"));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals(Map.of("employmentId", employment, "validFrom", "2026-03-31"), e.params());
    }

    @Test
    void rejectsAnOrgUnitThatDoesNotCoverThePeriod() {
        String later = orgUnitService.create(new NewOrgUnit(null, "ja", ja("Later"), null, null,
                "2027-04-01", null, null, null, null)).unit().id();
        String ending = unit("Ending", null);
        orgUnitService.end(ending, "2027-04-01");

        assertEquals("assignment.org_unit_not_valid", addFailure(to(later, "2026-10-01")).code());
        FuhitoException e = addFailure(to(ending, "2026-10-01"));
        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("assignment.org_unit_not_valid", e.code());
        assertEquals(Map.of("orgUnitId", ending, "validFrom", "2026-10-01"), e.params());
        // An assignment that ends with the unit is fine.
        service.add(new AssignmentValues(employment, ending, "2026-10-01", "2027-04-01", null, null, null, null, null, null,
                null, null, null, null, null));
        assertEquals("assignment.org_unit_unknown", addFailure(to("nope", "2026-10-01")).code());
    }

    @Test
    void rejectsBadValues() {
        item(RefKind.JOB_TITLE, "retired", false);
        item(RefKind.OCCUPATION, "nurse", true);

        assertEquals("invalid.required", assertThrows(FuhitoException.class, () -> service.add(null)).code());
        assertEquals("invalid.required", addFailure(new AssignmentValues(null, ward, "2026-04-01", null, null, null,
                null, null, null, null, null, null, null, null, null)).code());
        assertEquals("invalid.required", addFailure(to(null, "2026-04-01")).code());
        assertEquals("invalid.required", addFailure(to(ward, null)).code());
        assertEquals("assignment.employment_unknown", addFailure(new AssignmentValues("nope", ward, "2026-04-01",
                null, null, null, null, null, null, null, null, null, null, null, null)).code());
        for (String[] codes : new String[][] {{"retired", null, null}, {"nurse", null, null}, {null, "nope", null},
                {null, null, "nope"}}) {
            assertEquals("ref_item.unknown", addFailure(new AssignmentValues(employment, ward, "2026-04-01", null,
                    codes[0], codes[1], codes[2], null, null, null, null, null, null, null, null)).code());
        }
        assertEquals("invalid.percent", addFailure(new AssignmentValues(employment, ward, "2026-04-01", null, null,
                null, null, null, null, null, new BigDecimal("101"), null, null, null, null)).code());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void aChangeSplitsTheDetailsAndKeepsTheId() {
        item(RefKind.JOB_TITLE, "staff", true);
        item(RefKind.JOB_TITLE, "chief", true);
        item(RefKind.JOB_TITLE, "director", true);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", null, null, null,
                null, null, null, null, null, "A-1", "hr")).assignmentId();
        service.changeDetails(id, details("2028-04-01", "director", true));

        AssignmentView view = service.changeDetails(id, details("2027-04-01", "chief", null));

        assertEquals(id, view.assignmentId());
        assertEquals(List.of("2026-04-01 2027-04-01 staff", "2027-04-01 2028-04-01 chief", "2028-04-01 null director"),
                periods(view));
        assertEquals(List.of(false, false, true), view.rows().stream().map(r -> r.row().head()).toList());
        assertTrue(view.rows().stream().allMatch(r -> r.row().orgUnitId().equals(ward)
                && r.row().employmentId().equals(employment) && "hr".equals(r.row().source())));
        assertEquals(3, view.rows().stream().map(r -> r.row().rowId()).distinct().count());
    }

    @Test
    void aChangeOnTheFirstDateCorrectsAndReplacesAllDetails() {
        item(RefKind.JOB_TITLE, "staff", true);
        item(RefKind.OCCUPATION, "nurse", true);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", "nurse", null,
                true, true, null, new BigDecimal("50"), 4, Map.of("k", "v"), null, null)).assignmentId();
        String rowId = store.rows(id).getFirst().rowId();

        AssignmentView view = service.changeDetails(id, details("2026-04-01", null, null));

        assertEquals(1, view.rows().size());
        Assignment row = view.rows().getFirst().row();
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
                null, null, null, null, null, null, null, null)).assignmentId();
        refItems.update(new RefItem(old.id(), RefKind.JOB_TITLE, "old-title", ItemNames.of("old"), 0, Map.of(), false));

        assertEquals("old-title", service.changeDetails(id, details("2026-10-01", "old-title", true)).rows().get(1)
                .jobTitle());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.changeDetails(id,
                new AssignmentValues(null, null, "2027-04-01", null, null, "retired", null, null, null, null, null, null,
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
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 4, 1)), store.rows(id).getFirst().period());
    }

    @Test
    void readsTheVersionOfAnAssignment() {
        String id = service.add(to(ward, "2026-04-01")).assignmentId();

        assertEquals(service.get(id).rows().getFirst().row().version(), service.version(id));
        assertEquals("assignment.not_found",
                assertThrows(FuhitoException.class, () -> service.version("nope")).code());
    }

    @Test
    void movesTheEndOfAnAssignmentEarlierAndLaterAndRemovesIt() {
        String id = service.add(to(ward, "2026-04-01")).assignmentId();

        assertEquals(LocalDate.of(2027, 4, 1), service.end(id, "2027-04-01").period().to());
        assertEquals(LocalDate.of(2026, 10, 1), service.end(id, "2026-10-01").period().to());
        assertEquals(LocalDate.of(2028, 4, 1), service.end(id, "2028-04-01").period().to());
        assertNull(service.removeEnd(id).period().to());
        assertEquals("assignment.not_found",
                assertThrows(FuhitoException.class, () -> service.removeEnd("nope")).code());
    }

    @Test
    void anEndDoesNotPassTheStartOfTheAssignmentThatContinuesIt() {
        String old = service.add(to(ward, "2026-04-01")).assignmentId();
        String next = service.move(old, new MoveRequest(yHospital, "2027-04-01", null, null, null)).started()
                .assignmentId();

        for (String date : List.of("2027-04-02", "2028-04-01")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> service.end(old, date));
            assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
            assertEquals("assignment.continues_holds", e.code());
            assertEquals(Map.of("id", old, "date", "2027-04-01"), e.params());
        }
        assertEquals("assignment.continues_holds",
                assertThrows(FuhitoException.class, () -> service.removeEnd(old)).code());
        assertEquals(LocalDate.of(2027, 4, 1), store.rows(old).getLast().period().to());

        // An end before the start of the next one leaves a gap, which is fine. So is an end on that start.
        assertEquals(LocalDate.of(2027, 3, 1), service.end(old, "2027-03-01").period().to());
        assertEquals(LocalDate.of(2027, 4, 1), service.end(old, "2027-04-01").period().to());
        assertEquals(LocalDate.of(2030, 4, 1), service.end(next, "2030-04-01").period().to());
        assertNull(service.removeEnd(next).period().to());
    }

    @Test
    void anAssignmentThatEndedWithItsEmploymentKeepsThatEndAfterATransfer() {
        String old = service.add(to(ward, "2026-04-01")).assignmentId();
        employmentService.transfer(employment, new TransferRequest("company-x", "2027-04-01", null, null));

        assertEquals(LocalDate.of(2027, 4, 1), service.removeEnd(old).period().to());
    }

    @Test
    void aLaterEndMustStayInsideTheEmploymentAndTheOrgUnit() {
        String fixed = employmentService.start(new NewEmployment("bob", "2026-04-01", "2027-04-01", null, null, null,
                null, null, null, null, null)).employment().id();
        String ending = unit("Ending", null);
        orgUnitService.end(ending, "2027-10-01");
        String inFixed = service.add(new AssignmentValues(fixed, ward, "2026-04-01", "2026-10-01", null, null, null,
                null, null, null, null, null, null, null, null)).assignmentId();
        String inEnding = service.add(new AssignmentValues(employment, ending, "2026-04-01", "2026-10-01", null, null,
                null, null, null, null, null, null, null, null, null)).assignmentId();

        assertEquals("assignment.outside_employment",
                assertThrows(FuhitoException.class, () -> service.end(inFixed, "2027-04-02")).code());
        assertEquals(LocalDate.of(2027, 4, 1), service.end(inFixed, "2027-04-01").period().to());
        assertEquals(LocalDate.of(2027, 4, 1), service.removeEnd(inFixed).period().to());

        assertEquals("assignment.org_unit_not_valid",
                assertThrows(FuhitoException.class, () -> service.end(inEnding, "2027-10-02")).code());
        assertEquals("assignment.org_unit_not_valid",
                assertThrows(FuhitoException.class, () -> service.removeEnd(inEnding)).code());
        assertEquals(LocalDate.of(2027, 10, 1), service.end(inEnding, "2027-10-01").period().to());
    }

    @Test
    void aLaterEndOfAPrimaryAssignmentMustNotMeetAnotherPrimary() {
        String first = service.add(primary(ward, "2026-04-01", "2027-04-01", null)).assignmentId();
        service.add(primary(xHospital, "2027-04-01", null, null));

        assertEquals("assignment.primary_exists",
                assertThrows(FuhitoException.class, () -> service.end(first, "2027-10-01")).code());
        assertEquals(LocalDate.of(2027, 4, 1), store.rows(first).getFirst().period().to());
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

    private AssignmentValues primary(String orgUnitId, String validFrom, String validTo, Boolean switchPrimary) {
        return new AssignmentValues(employment, orgUnitId, validFrom, validTo, null, null, null, null, true,
                switchPrimary, null, null, null, null, null);
    }

    private List<String> primaryPeriods(String assignmentId) {
        return store.rows(assignmentId).stream()
                .map(r -> r.period().from() + " " + r.period().to() + " " + r.primary())
                .toList();
    }

    @Test
    void rejectsASecondPrimaryAssignmentOnTheSameDates() {
        String first = service.add(primary(ward, "2026-04-01", null, null)).assignmentId();

        FuhitoException e = addFailure(primary(xHospital, "2027-04-01", null, false));

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("assignment.primary_exists", e.code());
        assertEquals(Map.of("employmentId", employment, "assignmentId", first, "validFrom", "2027-04-01"), e.params());
        assertEquals(1, store.rows.size());
    }

    @Test
    void primaryAssignmentsMayFollowEachOtherAndBelongToOtherEmployments() {
        service.add(primary(ward, "2026-04-01", "2027-04-01", null));
        service.add(primary(xHospital, "2027-04-01", null, null));
        String other = employmentService.start(new NewEmployment("bob", "2026-04-01", null, null, null, null, null,
                null, null, null, null)).employment().id();

        service.add(new AssignmentValues(other, ward, "2026-04-01", null, null, null, null, null, true, null, null,
                null, null, null, null));
        // An assignment that is not primary never conflicts.
        service.add(to(yHospital, "2026-04-01"));

        assertEquals(4, store.rows.size());
    }

    @Test
    void switchingTakesThePrimaryFlagFromTheDate() {
        String old = service.add(primary(ward, "2026-04-01", null, null)).assignmentId();

        String now = service.add(primary(xHospital, "2027-04-01", null, true)).assignmentId();

        assertEquals(List.of("2026-04-01 2027-04-01 true", "2027-04-01 null false"), primaryPeriods(old));
        assertEquals(List.of("2027-04-01 null true"), primaryPeriods(now));
    }

    @Test
    void switchingForABoundedPeriodGivesTheFlagBackAfterIt() {
        String old = service.add(primary(ward, "2026-04-01", null, null)).assignmentId();

        service.add(primary(xHospital, "2027-04-01", "2028-04-01", true));

        assertEquals(List.of("2026-04-01 2027-04-01 true", "2027-04-01 2028-04-01 false", "2028-04-01 null true"),
                primaryPeriods(old));
    }

    @Test
    void switchingFromTheFirstDateOfTheHolderChangesItInPlace() {
        String old = service.add(primary(ward, "2026-04-01", null, null)).assignmentId();
        String rowId = store.rows(old).getFirst().rowId();

        service.add(primary(xHospital, "2026-04-01", "2027-04-01", true));

        assertEquals(List.of("2026-04-01 2027-04-01 false", "2027-04-01 null true"), primaryPeriods(old));
        assertEquals(rowId, store.rows(old).getFirst().rowId());
    }

    @Test
    void switchingTakesTheFlagFromEveryHolderInThePeriod() {
        String first = service.add(primary(ward, "2026-04-01", "2027-04-01", null)).assignmentId();
        String second = service.add(primary(xHospital, "2027-04-01", "2028-04-01", null)).assignmentId();

        service.add(primary(yHospital, "2026-10-01", "2027-10-01", true));

        assertEquals(List.of("2026-04-01 2026-10-01 true", "2026-10-01 2027-04-01 false"), primaryPeriods(first));
        assertEquals(List.of("2027-04-01 2027-10-01 false", "2027-10-01 2028-04-01 true"), primaryPeriods(second));
    }

    @Test
    void aChangeOfDetailsCanClaimAndKeepThePrimaryFlag() {
        String old = service.add(primary(ward, "2026-04-01", null, null)).assignmentId();
        String other = service.add(to(xHospital, "2026-04-01")).assignmentId();
        AssignmentValues claim = new AssignmentValues(null, null, "2027-04-01", null, null, null, null, null, true,
                null, null, null, null, null, null);
        AssignmentValues claimAndSwitch = new AssignmentValues(null, null, "2027-04-01", null, null, null, null, null,
                true, true, null, null, null, null, null);

        assertEquals("assignment.primary_exists",
                assertThrows(FuhitoException.class, () -> service.changeDetails(other, claim)).code());
        assertEquals(1, store.rows(other).size());

        service.changeDetails(other, claimAndSwitch);
        assertEquals(List.of("2026-04-01 2027-04-01 false", "2027-04-01 null true"), primaryPeriods(other));
        assertEquals(List.of("2026-04-01 2027-04-01 true", "2027-04-01 null false"), primaryPeriods(old));

        // The holder may change its own details and stay primary. A correction on the first date works too.
        service.changeDetails(other, new AssignmentValues(null, null, "2028-04-01", null, null, null, null, true,
                true, null, null, null, null, null, null));
        service.changeDetails(other, claim);
        assertEquals(List.of("2026-04-01 2027-04-01 false", "2027-04-01 2028-04-01 true", "2028-04-01 null true"),
                primaryPeriods(other));
    }

    @Test
    void aSwitchOfAnAssignmentThatIsNotPrimaryFailsAndWritesNothing() {
        String old = service.add(primary(ward, "2026-04-01", null, null)).assignmentId();
        String other = service.add(to(xHospital, "2026-04-01")).assignmentId();

        for (Boolean flag : new Boolean[] {null, false}) {
            FuhitoException added = addFailure(new AssignmentValues(employment, yHospital, "2027-04-01", null, null,
                    null, null, null, flag, true, null, null, null, null, null));
            assertEquals(FuhitoException.Reason.INVALID, added.reason());
            assertEquals("assignment.switch_primary_unused", added.code());
            assertEquals(Map.of(), added.params());
            FuhitoException changed = assertThrows(FuhitoException.class, () -> service.changeDetails(other,
                    new AssignmentValues(null, null, "2027-04-01", null, null, null, null, null, flag, true, null,
                            null, null, null, null)));
            assertEquals("assignment.switch_primary_unused", changed.code());
        }

        assertEquals(2, store.rows.size());
        assertEquals(List.of("2026-04-01 null true"), primaryPeriods(old));
        assertEquals(List.of("2026-04-01 null false"), primaryPeriods(other));
    }

    @Test
    void readsAnAssignmentWhoseEmploymentOrItemIsGone() {
        RefItem title = item(RefKind.JOB_TITLE, "staff", true);
        String id = service.add(new AssignmentValues(employment, ward, "2026-04-01", null, "staff", null, null, null,
                null, null, null, null, null, null, null)).assignmentId();
        refItems.delete(title.id());
        employments.employments.clear();

        AssignmentView view = service.get(id);

        assertNull(view.userId());
        assertNull(view.rows().getFirst().jobTitle());
        assertEquals("assignment.not_found",
                assertThrows(FuhitoException.class, () -> service.get("nope")).code());
    }

    // 2027-06-15 in Tokyo.
    private static final Clock JUNE_2027 = Clock.fixed(Instant.parse("2027-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private void placeItems() {
        item(RefKind.WORK_LOCATION, "tokyo", true);
        item(RefKind.WORK_LOCATION, "osaka", true);
        item(RefKind.WORK_LOCATION, "nagoya", true);
        item(RefKind.WORK_LOCATION, "home", true);
        item(RefKind.COST_CENTER, "cc-hq", true);
        item(RefKind.COST_CENTER, "cc-x", true);
    }

    private void giveUnit(String id, String name, String validFrom, String workLocation, String costCenter) {
        orgUnitService.changeDetails(id, new OrgUnitChange(validFrom, null, "ja", ja(name), null,
                workLocation, costCenter, null, null));
    }

    private AssignmentValues placedIn(String orgUnitId, String workLocation, String costCenter) {
        return new AssignmentValues(employment, orgUnitId, "2026-04-01", null, null, null, null, workLocation,
                costCenter, null, null, null, null, null, null, null, null);
    }

    private static List<String> places(AssignmentView view) {
        return view.rows().stream().map(r -> r.places().workLocation() + " " + r.places().costCenter() + " "
                + r.places().effectiveWorkLocation() + " " + r.places().effectiveCostCenter()).toList();
    }

    @Test
    void anAssignmentTakesWhatItsUnitOrAUnitAboveGivesUnlessItHasItsOwn() {
        placeItems();
        giveUnit(xHospital, "X Hospital", "2026-04-01", "tokyo", "cc-hq");
        giveUnit(ward, "Ward", "2026-04-01", null, "cc-x");

        assertEquals(List.of("null null tokyo cc-hq"), places(service.add(placedIn(xHospital, null, null))));
        assertEquals(List.of("null null tokyo cc-x"), places(service.add(placedIn(ward, null, null))));
        AssignmentView home = service.add(placedIn(ward, "home", null));
        assertEquals(List.of("home null home cc-x"), places(home));
        assertEquals("WORK_LOCATION-home", home.rows().getFirst().row().workLocationId());
        assertEquals(List.of("null null null null"), places(service.add(placedIn(yHospital, null, null))));
        // A change of details replaces the values, so one that leaves them out takes the ones of the unit again.
        assertEquals(List.of("null null tokyo cc-x"), places(service.changeDetails(home.assignmentId(),
                details("2026-04-01", null, null))));
        // A read of a page of many assignments gives only the values of the records.
        assertEquals(List.of("null null null null"), places(service.listOn(LocalDate.of(2026, 4, 1), null, 1)
                .getFirst()));
    }

    @Test
    void eachPeriodOfAnAssignmentTakesTheValuesOnItsDateNearestToToday() {
        placeItems();
        AssignmentService inJune = new AssignmentService(store, externalIds, employments, orgUnits, refItems, lines,
                Today.of(JUNE_2027), AttributeRules.NONE);
        giveUnit(xHospital, "X Hospital", "2026-04-01", "tokyo", null);
        giveUnit(xHospital, "X Hospital", "2027-04-01", "osaka", null);
        giveUnit(xHospital, "X Hospital", "2028-04-01", "nagoya", null);
        String id = inJune.add(to(ward, "2026-04-01")).assignmentId();
        inJune.changeDetails(id, details("2027-01-01", null, true));
        inJune.changeDetails(id, details("2028-06-01", null, false));
        int reads = orgUnits.defaultsReads;

        AssignmentView view = inJune.get(id);

        assertEquals(List.of("null null tokyo null", "null null osaka null", "null null nagoya null"), places(view));
        // One read for each date.
        assertEquals(3, orgUnits.defaultsReads - reads);
    }

    @Test
    void aMoveDropsTheValuesOfTheOldAssignmentAndCanGiveNewOnes() {
        placeItems();
        giveUnit(yHospital, "Y Hospital", "2026-04-01", "osaka", null);
        String id = service.add(placedIn(ward, "home", "cc-x")).assignmentId();

        AssignmentService.Move move = service.move(id, new MoveRequest(yHospital, "2027-04-01", null, null, null));
        assertEquals(List.of("null null osaka null"), places(move.started()));

        String other = service.add(placedIn(ward, "home", "cc-x")).assignmentId();
        AssignmentService.Move kept = service.move(other, new MoveRequest(yHospital, "2027-04-01", null, null, null,
                "home", "cc-hq"));
        assertEquals(List.of("home cc-hq home cc-hq"), places(kept.started()));
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.move(kept.started()
                .assignmentId(), new MoveRequest(ward, "2028-04-01", null, null, null, "cc-hq", null))).code());
    }

    @Test
    void anAssignmentNamesOnlyActiveItemsOfTheRightKind() {
        placeItems();
        item(RefKind.COST_CENTER, "closed", false);

        FuhitoException wrongKind = addFailure(placedIn(ward, "cc-hq", null));
        assertEquals("ref_item.unknown", wrongKind.code());
        assertEquals(Map.of("kind", "WORK_LOCATION", "code", "cc-hq"), wrongKind.params());
        assertEquals(Map.of("kind", "COST_CENTER", "code", "closed"), addFailure(placedIn(ward, null, "closed"))
                .params());
    }
}
