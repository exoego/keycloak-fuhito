package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.service.UnitNames.ja;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRetirementTest {

    // Today is 2026-06-15 in Tokyo.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryReportingLineStore lines = new MemoryReportingLineStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryPersonStore people = MemoryPersonStore.anyUser();
    private final MemoryUnitRoleStore unitRoles = new MemoryUnitRoleStore();
    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private final EmploymentService employments =
            new EmploymentService(employmentStore, externalIds, assignmentStore, refItems, people, lines, unitRoles, new AnyEmployer());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, externalIds, employmentStore, orgUnitStore, refItems, lines);
    private final ReportingLineService lineService = new ReportingLineService(lines,
            new ReportingLineService.Sources(assignmentStore, employmentStore, orgUnitStore, refItems, id -> true,
                    people),
            CLOCK);
    private final UserRetirement retirement =
            new UserRetirement(employmentStore, employments, assignmentStore, assignments, lines, people, unitRoles,
                    externalIds, CLOCK);

    private String unit;

    @BeforeEach
    void createAUnit() {
        unit = new OrgUnitService(orgUnitStore, externalIds, assignmentStore, refItems, CLOCK).create(new NewOrgUnit(null, "ja", ja("Unit"), null, null, "2010-04-01", null, null, null, null)).unit().id();
    }

    private String employ(String userId, String from, String to) {
        return employments.start(new NewEmployment(userId, from, to, null, null, null, null, null, null, null, null))
                .employment().id();
    }

    private String assign(String employmentId, String from) {
        return assignments.add(new AssignmentValues(employmentId, unit, from, null, null, null, null, null, null,
                null, null, null, null, null, null)).view().assignmentId();
    }

    private static List<String> describe(List<Write> written) {
        return written.stream()
                .map(w -> (w.deleted() ? "DELETE " : w.created() ? "CREATE " : "UPDATE ") + w.resourceType() + " "
                        + w.resourceId())
                .toList();
    }

    private List<String> employmentsOf(String userId) {
        return employmentStore.findByUser(userId).stream()
                .map(e -> e.period().from() + " " + e.period().to()).toList();
    }

    private List<String> rowsOf(String employmentId) {
        return assignmentStore.rowsOfEmployment(employmentId).stream()
                .map(r -> r.period().from() + " " + r.period().to()).toList();
    }

    private List<String> allLines() {
        return lines.lines.stream()
                .map(l -> l.managerUserId() + " " + l.period().from() + " " + l.period().to()).sorted().toList();
    }

    @Test
    void endsWhatIsOpenTodayAndKeepsThePast() {
        String past = employ("alice", "2015-04-01", "2020-04-01");
        String pastAssignment = assign(past, "2015-04-01");
        String open = employ("alice", "2022-04-01", null);
        String openAssignment = assign(open, "2022-04-01");
        employments.changeTerms(open, new TermsChange("2024-04-01", null, null, "ON_LEAVE", null, null));
        String openLine = lineService.add(openAssignment, "boss", null, "2022-04-01", null).id();
        lineService.add(pastAssignment, "boss", null, "2015-04-01", null);
        String other = employ("bob", "2022-04-01", null);
        assign(other, "2022-04-01");

        List<Write> written = retirement.retire("alice");

        // The end of the employment ends the assignment and its line too.
        assertEquals(List.of("UPDATE FUHITO_EMPLOYMENT " + open, "UPDATE FUHITO_REPORTING_LINE " + openLine,
                "UPDATE FUHITO_ASSIGNMENT " + openAssignment), describe(written));
        assertEquals(List.of("2015-04-01 2020-04-01", "2022-04-01 2026-06-15"), employmentsOf("alice"));
        assertEquals(List.of("2015-04-01 2020-04-01"), rowsOf(past));
        assertEquals(List.of("2022-04-01 2026-06-15"), rowsOf(open));
        assertEquals(2, employmentStore.terms(open).size());
        assertEquals("2026-06-15", employmentStore.terms(open).get(1).period().to().toString());
        assertEquals(List.of("boss 2015-04-01 2020-04-01", "boss 2022-04-01 2026-06-15"), allLines());
        assertEquals(List.of("2022-04-01 null"), employmentsOf("bob"));
        assertEquals(List.of("2022-04-01 null"), rowsOf(other));
    }

    @Test
    void anEmploymentThatEndsLaterEndsTodayAndOneThatNeverHeldIsRemoved() {
        String fixed = employ("alice", "2026-04-01", "2027-04-01");
        String fixedAssignment = assign(fixed, "2026-04-01");
        String startsToday = employ("alice", "2026-06-15", null);
        String todayAssignment = assign(startsToday, "2026-06-15");
        lineService.add(todayAssignment, "boss", null, "2026-06-15", null);
        String future = employ("alice", "2027-04-01", null);
        assign(future, "2027-04-01");

        List<Write> written = retirement.retire("alice");

        // Every write is for the change log, the removals too. The later employment goes first.
        assertEquals(List.of("DELETE FUHITO_ASSIGNMENT", "DELETE FUHITO_EMPLOYMENT " + future,
                "DELETE FUHITO_REPORTING_LINE", "DELETE FUHITO_ASSIGNMENT " + todayAssignment,
                "DELETE FUHITO_EMPLOYMENT " + startsToday, "UPDATE FUHITO_EMPLOYMENT " + fixed,
                "UPDATE FUHITO_ASSIGNMENT " + fixedAssignment),
                describe(written).stream().map(w -> w.startsWith("DELETE FUHITO_ASSIGNMENT ")
                        && !w.endsWith(todayAssignment) ? "DELETE FUHITO_ASSIGNMENT" : w)
                        .map(w -> w.startsWith("DELETE FUHITO_REPORTING_LINE") ? "DELETE FUHITO_REPORTING_LINE" : w)
                        .toList());
        assertEquals(List.of("2026-04-01 2026-06-15"), employmentsOf("alice"));
        assertEquals(1, assignmentStore.rows.size());
        assertEquals(1, employmentStore.terms.size());
        assertEquals(List.of(), allLines());
    }

    @Test
    void theEndOfAnOpenEmploymentGivesAWriteOfEachRecordThatItEndsOrRemoves() {
        String open = employ("alice", "2022-04-01", null);
        String assignment = assign(open, "2022-04-01");
        String later = assign(open, "2026-07-01");
        String line = lineService.add(assignment, "boss", null, "2022-04-01", null).id();
        String laterLine = lineService.add(later, "boss", null, "2026-07-01", null).id();
        unitRoles.insert(new UnitRole("role-1", open, null, "alice", unit, "hr-contact", false,
                ValidPeriod.startingAt(LocalDate.parse("2022-04-01")), 1));
        unitRoles.insert(new UnitRole("role-2", open, null, "alice", unit, "hr-contact", false,
                ValidPeriod.startingAt(LocalDate.parse("2026-07-01")), 1));

        List<Write> written = retirement.retire("alice");

        // A removed record comes before the records that it refers to.
        assertEquals(List.of("UPDATE FUHITO_EMPLOYMENT " + open, "DELETE FUHITO_UNIT_ROLE role-2",
                "UPDATE FUHITO_UNIT_ROLE role-1", "DELETE FUHITO_REPORTING_LINE " + laterLine,
                "UPDATE FUHITO_REPORTING_LINE " + line, "DELETE FUHITO_ASSIGNMENT " + later,
                "UPDATE FUHITO_ASSIGNMENT " + assignment), describe(written));
        assertEquals(List.of("fuhito/employments/" + open + "/end", "fuhito/unit-roles/role-2",
                "fuhito/unit-roles/role-1/end", "fuhito/reporting-lines/" + laterLine,
                "fuhito/reporting-lines/" + line + "/end", "fuhito/assignments/" + later,
                "fuhito/assignments/" + assignment + "/end"), written.stream().map(Write::path).toList());
        assertEquals(List.of("2026-06-15"), unitRoles.findByEmployment(open).stream()
                .map(role -> role.period().to().toString()).toList());
        assertEquals(List.of("2022-04-01 2026-06-15"), rowsOf(open));
    }

    @Test
    void anEmploymentThatNeverHeldGoesWithItsRolesItsIdsAndWhatContinuesIt() {
        String joins = employ("alice", "2026-07-01", null);
        String assignment = assign(joins, "2026-07-01");
        unitRoles.insert(new UnitRole("role-1", joins, null, "alice", unit, "hr-contact", false,
                ValidPeriod.startingAt(LocalDate.parse("2026-07-01")), 1));
        externalIds.insert(new ExternalId("x-1", ExternalId.Kind.EMPLOYMENT, joins, "payroll", "P-1", 1));
        externalIds.insert(new ExternalId("x-2", ExternalId.Kind.ASSIGNMENT, assignment, "payroll", "A-1", 1));
        String next = employments.transfer(joins, new TransferRequest("other", "2026-10-01", null, null))
                .started().employment().id();

        retirement.retire("alice");

        assertEquals(List.of(), employmentsOf("alice"));
        assertEquals(List.of(), unitRoles.findByEmployment(joins));
        assertEquals(List.of(), unitRoles.findByEmployment(next));
        assertEquals(List.of(), externalIds.rows);
        assertEquals(0, assignmentStore.rows.size());
    }

    @Test
    void aReasonThatWasStoredWithALaterEndStays() {
        refItems.insert(new RefItem("resignation", RefKind.END_REASON, "resignation", ItemNames.of("Resignation"), 0,
                Map.of(), true));
        String employment = employ("alice", "2020-04-01", null);
        employments.end(employment, "2026-12-01", "resignation");

        retirement.retire("alice");

        EmploymentView ended = employments.get(employment);
        assertEquals("2026-06-15", ended.employment().period().to().toString());
        assertEquals("resignation", ended.endReason());
    }

    @Test
    void theLinesToARemovedManagerEndToday() {
        String employment = employ("alice", "2022-04-01", null);
        String assignment = assign(employment, "2022-04-01");
        lineService.add(assignment, "boss", "SOLID", "2022-04-01", null);
        lineService.add(assignment, "boss", "DOTTED", "2023-04-01", "2024-04-01");
        ReportingLine future = lineService.add(assignment, "boss", "DOTTED", "2027-04-01", null);
        lineService.add(assignment, "other", "DOTTED", "2022-04-01", null);

        assertEquals(List.of("UPDATE FUHITO_REPORTING_LINE", "DELETE FUHITO_REPORTING_LINE " + future.id()),
                describe(retirement.retire("boss")).stream()
                        .map(w -> w.startsWith("UPDATE FUHITO_REPORTING_LINE") ? "UPDATE FUHITO_REPORTING_LINE" : w)
                        .toList());

        assertEquals(List.of("boss 2022-04-01 2026-06-15", "boss 2023-04-01 2024-04-01", "other 2022-04-01 null"),
                allLines());
        assertEquals(List.of(), lines.findByManager("boss").stream().filter(l -> l.id().equals(future.id())).toList());
        assertEquals(List.of("2022-04-01 null"), employmentsOf("alice"));
    }

    @Test
    void aUserWithNoDataChangesNothing() {
        employ("alice", "2022-04-01", null);

        assertEquals(List.of(), retirement.retire("nobody"));
        assertEquals(List.of("2022-04-01 null"), employmentsOf("alice"));
    }
}
