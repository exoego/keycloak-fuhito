package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import net.exoego.keycloak.fuhito.domain.ReportingLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRetirementTest {

    // Today is 2026-06-15 in Tokyo.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryReportingLineStore lines = new MemoryReportingLineStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final EmploymentService employments =
            new EmploymentService(employmentStore, assignmentStore, refItems, MemoryPersonStore.anyUser(), lines);
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, employmentStore, orgUnitStore, refItems, lines);
    private final ReportingLineService lineService = new ReportingLineService(lines,
            new ReportingLineService.Sources(assignmentStore, employmentStore, orgUnitStore, refItems, id -> true),
            CLOCK);
    private final UserRetirement retirement =
            new UserRetirement(employmentStore, employments, assignmentStore, lines, CLOCK);

    private String unit;

    @BeforeEach
    void createAUnit() {
        unit = new OrgUnitService(orgUnitStore, assignmentStore, refItems, CLOCK).create(new NewOrgUnit(null, "Unit",
                null, null, null, null, null, null, "2010-04-01", null, null, null, null)).unit().id();
    }

    private String employ(String userId, String from, String to) {
        return employments.start(new NewEmployment(userId, from, to, null, null, null, null, null, null, null, null))
                .employment().id();
    }

    private String assign(String employmentId, String from) {
        return assignments.add(new AssignmentValues(employmentId, unit, from, null, null, null, null, null, null,
                null, null, null, null, null, null)).assignmentId();
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
        lineService.add(openAssignment, "boss", null, "2022-04-01", null);
        lineService.add(pastAssignment, "boss", null, "2015-04-01", null);
        String other = employ("bob", "2022-04-01", null);
        assign(other, "2022-04-01");

        List<EmploymentView> ended = retirement.retire("alice");

        assertEquals(List.of(open), ended.stream().map(v -> v.employment().id()).toList());
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
        assign(fixed, "2026-04-01");
        String startsToday = employ("alice", "2026-06-15", null);
        String todayAssignment = assign(startsToday, "2026-06-15");
        lineService.add(todayAssignment, "boss", null, "2026-06-15", null);
        String future = employ("alice", "2027-04-01", null);
        assign(future, "2027-04-01");

        List<EmploymentView> ended = retirement.retire("alice");

        assertEquals(List.of(fixed), ended.stream().map(v -> v.employment().id()).toList());
        assertEquals(List.of("2026-04-01 2026-06-15"), employmentsOf("alice"));
        assertEquals(1, assignmentStore.rows.size());
        assertEquals(1, employmentStore.terms.size());
        assertEquals(List.of(), allLines());
    }

    @Test
    void theLinesToARemovedManagerEndToday() {
        String employment = employ("alice", "2022-04-01", null);
        String assignment = assign(employment, "2022-04-01");
        lineService.add(assignment, "boss", "SOLID", "2022-04-01", null);
        lineService.add(assignment, "boss", "DOTTED", "2023-04-01", "2024-04-01");
        ReportingLine future = lineService.add(assignment, "boss", "DOTTED", "2027-04-01", null);
        lineService.add(assignment, "other", "DOTTED", "2022-04-01", null);

        assertEquals(List.of(), retirement.retire("boss"));

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
