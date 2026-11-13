package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hospital with a director, a nursing department with a head nurse, and a ward with two nurses and no head.
 */
class ReportingLineServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryReportingLineStore store = new MemoryReportingLineStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final Set<String> users = Set.of("director", "head-nurse", "nurse-a", "nurse-b", "project-lead", "idle");
    private final EmploymentService employments =
            new EmploymentService(employmentStore, assignmentStore, refItems, users::contains, store);
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, employmentStore, orgUnitStore, refItems, store);
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems, CLOCK);
    private final ReportingLineService service = new ReportingLineService(store, new ReportingLineService.Sources(
            assignmentStore, employmentStore, orgUnitStore, refItems, users::contains), CLOCK);

    private String hospital;
    private String nursing;
    private String ward;
    private String nurseA;
    private String nurseB;
    private String nurseAEmployment;

    @BeforeEach
    void createTheHospital() {
        hospital = unit("Hospital", null);
        nursing = unit("Nursing", hospital);
        ward = unit("Ward", nursing);
        assign(employ("director"), hospital, true);
        assign(employ("head-nurse"), nursing, true);
        nurseAEmployment = employ("nurse-a");
        nurseA = assign(nurseAEmployment, ward, false);
        nurseB = assign(employ("nurse-b"), ward, false);
        employ("project-lead");
    }

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2026-04-01", null,
                null, null, null)).unit().id();
    }

    private String employ(String userId) {
        return employments.start(new NewEmployment(userId, "2026-04-01", null, null, null, null, null, null, null,
                null, null)).employment().id();
    }

    private String assign(String employmentId, String orgUnitId, boolean head) {
        return assignments.add(new AssignmentValues(employmentId, orgUnitId, "2026-04-01", null, null, null, null,
                head, null, null, null, null, null, null, null)).assignmentId();
    }

    private static List<String> summary(List<Reporting> list) {
        return list.stream()
                .map(r -> r.userId() + " " + r.kind() + (r.derived() ? " derived" : " stored"))
                .sorted()
                .toList();
    }

    private FuhitoException addFailure(String assignmentId, String manager, String kind, String from, String to) {
        return assertThrows(FuhitoException.class, () -> service.add(assignmentId, manager, kind, from, to));
    }

    @Test
    void theHeadOfTheNearestUnitAboveIsTheManager() {
        assertEquals(List.of("head-nurse SOLID derived"), summary(service.managersOf("nurse-a", null)));
        assertEquals(List.of("director SOLID derived"), summary(service.managersOf("head-nurse", null)));
        assertEquals(List.of(), summary(service.managersOf("director", null)));
        assertEquals(List.of(), summary(service.managersOf("idle", null)));
        Reporting derived = service.managersOf("nurse-a", null).get(0);
        assertNull(derived.lineId());
        assertEquals(nurseA, derived.assignmentId());
        assertEquals(ward, derived.orgUnitId());
    }

    @Test
    void theReportsFollowTheSameRule() {
        assertEquals(List.of("nurse-a SOLID derived", "nurse-b SOLID derived"),
                summary(service.reportsOf("head-nurse", null)));
        assertEquals(List.of("head-nurse SOLID derived"), summary(service.reportsOf("director", null)));
        assertEquals(List.of(), summary(service.reportsOf("nurse-a", null)));
        assertEquals(List.of(), summary(service.reportsOf("idle", null)));
    }

    @Test
    void aStoredSolidLineReplacesTheDerivedManagerFromItsDate() {
        ReportingLine line = service.add(nurseA, "director", null, "2026-10-01", null);

        assertEquals(36, line.id().length());
        assertEquals(ReportingLine.Kind.SOLID, line.kind());
        assertEquals(ValidPeriod.startingAt(LocalDate.of(2026, 10, 1)), line.period());
        assertEquals(line, service.get(line.id()));
        assertEquals(List.of("head-nurse SOLID derived"), summary(service.managersOf("nurse-a", "2026-09-30")));
        assertEquals(List.of("director SOLID stored"), summary(service.managersOf("nurse-a", "2026-10-01")));
        assertEquals(List.of("nurse-b SOLID derived"), summary(service.reportsOf("head-nurse", "2026-10-01")));
        assertEquals(List.of("head-nurse SOLID derived", "nurse-a SOLID stored"),
                summary(service.reportsOf("director", "2026-10-01")));
        assertEquals(line.id(), service.managersOf("nurse-a", "2026-10-01").get(0).lineId());
    }

    @Test
    void aDottedLineAddsAManagerAndKeepsTheDerivedOne() {
        service.add(nurseA, "project-lead", "DOTTED", "2026-04-01", "2027-04-01");
        service.add(nurseA, "director", "DOTTED", "2026-04-01", null);

        assertEquals(List.of("director DOTTED stored", "head-nurse SOLID derived", "project-lead DOTTED stored"),
                summary(service.managersOf("nurse-a", null)));
        assertEquals(List.of("director DOTTED stored", "head-nurse SOLID derived"),
                summary(service.managersOf("nurse-a", "2027-04-01")));
        assertEquals(List.of("nurse-a DOTTED stored"), summary(service.reportsOf("project-lead", null)));
        assertEquals(List.of("nurse-a SOLID derived", "nurse-b SOLID derived"),
                summary(service.reportsOf("head-nurse", null)));
    }

    @Test
    void aUnitWithItsOwnHeadReportsThroughThatHead() {
        String chargeEmployment = employ("idle");
        assign(chargeEmployment, ward, true);

        assertEquals(List.of("idle SOLID derived"), summary(service.managersOf("nurse-a", null)));
        assertEquals(List.of("head-nurse SOLID derived"), summary(service.managersOf("idle", null)));
        assertEquals(List.of("idle SOLID derived"), summary(service.reportsOf("head-nurse", null)));
        assertEquals(List.of("nurse-a SOLID derived", "nurse-b SOLID derived"),
                summary(service.reportsOf("idle", null)));
    }

    @Test
    void twoHeadsOfOneUnitAreNotEachOthersManagers() {
        assign(employ("idle"), nursing, true);

        assertEquals(List.of("director SOLID derived"), summary(service.managersOf("idle", null)));
        assertEquals(List.of("head-nurse SOLID derived", "idle SOLID derived"),
                summary(service.managersOf("nurse-a", null)));
        assertEquals(List.of("nurse-a SOLID derived", "nurse-b SOLID derived"),
                summary(service.reportsOf("idle", null)));
        assertEquals(List.of("head-nurse SOLID derived", "idle SOLID derived"),
                summary(service.reportsOf("director", null)));
    }

    @Test
    void anAssignmentHasOneSolidLineAtATime() {
        ReportingLine first = service.add(nurseA, "director", "SOLID", "2026-04-01", "2027-04-01");

        FuhitoException e = addFailure(nurseA, "project-lead", "SOLID", "2026-10-01", null);

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("reporting_line.solid_exists", e.code());
        assertEquals(Map.of("assignmentId", nurseA, "lineId", first.id()), e.params());
        service.add(nurseA, "project-lead", "SOLID", "2027-04-01", null);
        service.add(nurseB, "project-lead", "SOLID", "2026-10-01", null);
        assertEquals(3, store.lines.size());
    }

    @Test
    void rejectsBadLines() {
        assertEquals("invalid.required", addFailure(null, "director", null, "2026-04-01", null).code());
        assertEquals("invalid.required", addFailure(nurseA, null, null, "2026-04-01", null).code());
        assertEquals("invalid.required", addFailure(nurseA, "director", null, null, null).code());
        assertEquals("invalid.choice", addFailure(nurseA, "director", "THICK", "2026-04-01", null).code());
        assertEquals("invalid.period", addFailure(nurseA, "director", null, "2026-04-01", "2026-04-01").code());
        assertEquals("reporting_line.assignment_unknown",
                addFailure("nope", "director", null, "2026-04-01", null).code());
        FuhitoException early = addFailure(nurseA, "director", null, "2026-03-31", null);
        assertEquals(FuhitoException.Reason.CONFLICT, early.reason());
        assertEquals("reporting_line.outside_assignment", early.code());
        assertEquals("reporting_line.manager_unknown", addFailure(nurseA, "nobody", null, "2026-04-01", null).code());
        assertEquals("reporting_line.self", addFailure(nurseA, "nurse-a", null, "2026-04-01", null).code());
        assertTrue(store.lines.isEmpty());
        assertEquals("user.not_found",
                assertThrows(FuhitoException.class, () -> service.managersOf("nobody", null)).code());
        assertEquals("user.not_found",
                assertThrows(FuhitoException.class, () -> service.reportsOf("nobody", null)).code());
        assertEquals("invalid.date",
                assertThrows(FuhitoException.class, () -> service.managersOf("nurse-a", "x")).code());
    }

    @Test
    void endsAndDeletesALine() {
        ReportingLine line = service.add(nurseA, "director", null, "2026-10-01", null);

        assertEquals(LocalDate.of(2027, 4, 1), service.end(line.id(), "2027-04-01").period().to());
        assertEquals(LocalDate.of(2027, 1, 1), service.end(line.id(), "2027-01-01").period().to());
        for (String date : new String[] {"2026-10-01", "2027-01-02"}) {
            assertEquals("invalid.period",
                    assertThrows(FuhitoException.class, () -> service.end(line.id(), date)).code());
        }
        assertEquals("invalid.required",
                assertThrows(FuhitoException.class, () -> service.end(line.id(), null)).code());

        service.delete(line.id());
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.get(line.id()));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("reporting_line.not_found", e.code());
        assertEquals("reporting_line.not_found",
                assertThrows(FuhitoException.class, () -> service.delete(line.id())).code());
        assertEquals("reporting_line.not_found",
                assertThrows(FuhitoException.class, () -> service.end(line.id(), "2027-01-01")).code());
    }

    @Test
    void aLineInsideAFixedAssignmentEndsWithIt() {
        assignments.end(nurseA, "2027-04-01");

        ReportingLine line = service.add(nurseA, "director", null, "2026-10-01", null);

        assertEquals(LocalDate.of(2027, 4, 1), line.period().to());
        assertEquals("reporting_line.outside_assignment",
                addFailure(nurseA, "project-lead", "DOTTED", "2026-10-01", "2027-04-02").code());
    }

    @Test
    void endingAnAssignmentOrAnEmploymentEndsItsLines() {
        ReportingLine open = service.add(nurseA, "director", null, "2026-10-01", null);
        ReportingLine future = service.add(nurseA, "project-lead", "DOTTED", "2028-04-01", null);
        ReportingLine past = service.add(nurseA, "project-lead", "DOTTED", "2026-04-01", "2026-10-01");
        ReportingLine other = service.add(nurseB, "director", null, "2026-10-01", null);

        assignments.end(nurseA, "2028-01-01");
        assertEquals(LocalDate.of(2028, 1, 1), service.get(open.id()).period().to());
        assertEquals("reporting_line.not_found",
                assertThrows(FuhitoException.class, () -> service.get(future.id())).code());

        employments.end(nurseAEmployment, "2027-04-01", null);
        assertEquals(LocalDate.of(2027, 4, 1), service.get(open.id()).period().to());
        assertEquals(LocalDate.of(2026, 10, 1), service.get(past.id()).period().to());
        assertNull(service.get(other.id()).period().to());
        assertEquals(List.of(), summary(service.managersOf("nurse-a", "2027-04-01")));
    }
}
