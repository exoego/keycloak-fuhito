package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.service.SyncReport.Unmatched;
import net.exoego.keycloak.fuhito.store.UserAttributeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeSyncServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    private Clock clock = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), TOKYO);
    private boolean enabled = true;
    private final Map<String, UserAttributeSource.Values> attributes = new LinkedHashMap<>();
    // Users that a user storage provider holds. The API of Keycloak finds them, and its tables do not.
    private final Set<String> federated = new HashSet<>();
    private final List<Collection<String>> usersAsked = new ArrayList<>();
    private final UserAttributeSource source = new UserAttributeSource() {
        @Override
        public Set<String> usersAmong(Collection<String> userIds) {
            usersAsked.add(List.copyOf(userIds));
            return userIds.stream().filter(attributes::containsKey).collect(Collectors.toSet());
        }

        @Override
        public Settings settings() {
            return new Settings(enabled, "department", "title", "manager", "employeeNumber");
        }

        @Override
        public List<Values> usersWithDepartment(Settings settings) {
            return attributes.values().stream().filter(v -> v.department() != null).toList();
        }
    };
    private final MemoryReportingLineStore lineStore = new MemoryReportingLineStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();

    private String medicine;
    private String surgery;

    @BeforeEach
    void createUnitsAndTitles() {
        OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIds, assignmentStore, refItems, clock);
        String hospital = orgUnits.create(unit("hospital", "Hospital", null)).unit().id();
        medicine = orgUnits.create(unit("med", "Internal Medicine", hospital)).unit().id();
        surgery = orgUnits.create(unit(null, "Surgery", hospital)).unit().id();
        orgUnits.create(unit(null, "Office", hospital));
        orgUnits.create(unit(null, "Office", medicine));
        refItems.insert(new RefItem("t1", RefKind.JOB_TITLE, "director", "Director", Map.of(), 0, Map.of(), true));
        refItems.insert(new RefItem("t2", RefKind.JOB_TITLE, "staff", "Staff", Map.of(), 0, Map.of(), true));
    }

    private static NewOrgUnit unit(String code, String name, String parentId) {
        return new NewOrgUnit(code, name, null, null, null, null, null, parentId, "2020-04-01", null, null, null,
                null);
    }

    private void set(String userId, String department, String title, String manager, String employeeNumber) {
        attributes.put(userId, new UserAttributeSource.Values(userId, department, title, manager, employeeNumber));
    }

    private final MemoryPersonStore people = MemoryPersonStore.anyUser();

    private EmploymentService employments() {
        return new EmploymentService(employmentStore, externalIds, assignmentStore, refItems, people, lineStore, new MemoryUnitRoleStore(), new AnyEmployer());
    }

    private AssignmentService assignments() {
        return new AssignmentService(assignmentStore, externalIds, employmentStore, orgUnitStore, refItems, lineStore);
    }

    private BusinessDay.Rule rule;

    private SyncReport run() {
        return new AttributeSyncService(sources(), today()).run();
    }

    private Today today() {
        return rule == null ? Today.of(clock) : new UnitDays(orgUnitStore, rule, clock);
    }

    private boolean known(String userId) {
        return attributes.containsKey(userId) || federated.contains(userId);
    }

    private AttributeSyncService.Sources sources() {
        ReportingLineService lineService = new ReportingLineService(lineStore, new ReportingLineService.Sources(
                assignmentStore, employmentStore, orgUnitStore, refItems, this::known, people), clock);
        return new AttributeSyncService.Sources(source, this::known,
                orgUnitStore, refItems, employmentStore, employments(), assignmentStore, assignments(), lineStore,
                lineService, new PersonService(people, externalIds, this::known, id -> false));
    }

    @Test
    void aRunWorksInPiecesOfUsersAndEachPieceRecordsItsOwnWrites() {
        for (int i = 0; i < 250; i++) {
            set("user-" + i, "med", null, null, null);
        }
        List<Integer> writesOfPieces = new ArrayList<>();
        AttributeSyncService.Work work = new AttributeSyncService.Work() {
            @Override
            public <T> T inNewTransaction(java.util.function.Function<AttributeSyncService.Parts, T> piece) {
                List<ImportReport.Write> writes = new ArrayList<>();
                T result = piece.apply(new AttributeSyncService.Parts(sources(), today(), writes::add));
                writesOfPieces.add(writes.size());
                return result;
            }
        };

        SyncReport report = new AttributeSyncService(work).run();

        assertEquals(250, report.assignmentsAdded());
        assertEquals(List.of(), report.writes());
        // One piece reads. Then 100, 100, and 50 users each write a person, an employment, and an assignment.
        assertEquals(List.of(0, 300, 300, 150), writesOfPieces);

        // The users lose their department. The assignments end in pieces too.
        attributes.clear();
        writesOfPieces.clear();
        nextDay();
        assertEquals(250, new AttributeSyncService(work).run().assignmentsEnded());
        assertEquals(List.of(0, 100, 100, 50), writesOfPieces);
    }

    private void nextDay() {
        clock = Clock.offset(clock, Duration.ofDays(1));
    }

    private List<String> assignmentsOf(String userId) {
        List<String> result = new ArrayList<>();
        employmentStore.findByUser(userId).forEach(e -> assignmentStore.rowsOfEmployment(e.id()).forEach(row ->
                result.add(unitName(row) + " " + row.period().from() + " " + row.period().to() + " "
                        + (row.jobTitleId() == null ? "-" : refItems.find(row.jobTitleId()).orElseThrow().code())
                        + (row.primary() ? " primary" : ""))));
        return result;
    }

    private String unitName(Assignment row) {
        return orgUnitStore.versions(row.orgUnitId()).getFirst().name();
    }

    private List<String> managersOf(String userId) {
        List<String> result = new ArrayList<>();
        employmentStore.findByUser(userId).forEach(e -> assignmentStore.rowsOfEmployment(e.id()).forEach(row ->
                lineStore.findByAssignment(row.assignmentId()).forEach(line -> {
                    String entry = line.managerUserId() + " " + line.period().from() + " " + line.period().to();
                    if (!result.contains(entry)) {
                        result.add(entry);
                    }
                })));
        return result;
    }

    @Test
    void buildsAnEmploymentAnAssignmentAndAReportingLine() {
        set("boss", "med", "Director", null, "E-001");
        set("alice", "Internal Medicine", "staff", "boss", "E-002");

        SyncReport report = run();

        assertEquals(2, report.users());
        assertEquals(2, report.employmentsStarted());
        assertEquals(2, report.assignmentsAdded());
        assertEquals(1, report.reportingLinesChanged());
        assertEquals(List.of(), report.unmatched());
        assertEquals(List.of("Internal Medicine 2026-06-15 null director primary"), assignmentsOf("boss"));
        assertEquals(List.of("Internal Medicine 2026-06-15 null staff primary"), assignmentsOf("alice"));
        assertEquals(List.of("boss 2026-06-15 null"), managersOf("alice"));
        var employment = employmentStore.findByUser("alice").getFirst();
        assertEquals("E-002", employment.staffNumber());
        assertEquals("scim", employment.source());
        assertEquals(List.of(), externalIds.ofResource(ExternalId.Kind.EMPLOYMENT, employment.id()));
        assertEquals(List.of("FUHITO_PERSON", "FUHITO_EMPLOYMENT", "FUHITO_ASSIGNMENT", "FUHITO_PERSON",
                        "FUHITO_EMPLOYMENT", "FUHITO_ASSIGNMENT", "FUHITO_REPORTING_LINE"),
                report.writes().stream().map(ImportReport.Write::resourceType).toList());
    }

    @Test
    void aPersonWithNoUserKeepsTheirAssignmentAndStopsNoOne() {
        set("boss", "med", "director", null, null);
        set("alice", "med", null, "boss", null);
        run();
        var employment = employmentStore.findByUser("alice").getFirst();
        // The person of alice lost the link to the Keycloak user.
        employmentStore.employments.put(employment.id(), new Employment(employment.id(), employment.personId(), null,
                employment.employerId(), employment.period(), employment.staffNumber(), employment.endReasonId(),
                employment.source(), employment.continuesEmploymentId()));

        SyncReport again = run();

        assertEquals(2, again.users());
        assertEquals(1, assignmentStore.rowsOfEmployment(employment.id()).size());
    }

    @Test
    void aUserWhoseEmploymentEndedGetsANewOneWhenTheyComeBack() {
        set("alice", "med", null, null, "E-002");
        run();
        Employment first = employmentStore.findByUser("alice").getFirst();
        // Alice leaves on June 16 and comes back on June 17.
        nextDay();
        employments().end(first.id(), "2026-06-16", null);
        nextDay();

        SyncReport back = run();

        assertEquals(1, back.employmentsStarted());
        assertEquals(1, back.assignmentsAdded());
        List<Employment> both = employmentStore.findByUser("alice");
        assertEquals(List.of(new ValidPeriod(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16)),
                ValidPeriod.startingAt(LocalDate.of(2026, 6, 17))), both.stream().map(Employment::period).toList());
        Employment second = both.getLast();
        assertEquals("E-002", second.staffNumber());
        assertEquals("scim", second.source());
        assertNull(second.continuesEmploymentId());
        assertEquals(List.of("Internal Medicine 2026-06-15 2026-06-16 - primary",
                "Internal Medicine 2026-06-17 null - primary"), assignmentsOf("alice"));
    }

    @Test
    void aUserWhosePersonLostTheLinkGetsANewPersonWhenTheyComeBack() {
        set("alice", "med", null, null, null);
        run();
        Employment first = employmentStore.findByUser("alice").getFirst();
        // The person of alice loses the link to the Keycloak user.
        Person person = people.find(first.personId()).orElseThrow();
        people.update(new Person(person.id(), null, person.name(), person.names(), person.phoneticName(),
                person.attributes(), person.source()));
        employmentStore.employments.put(first.id(), new Employment(first.id(), first.personId(), null,
                first.employerId(), first.period(), first.staffNumber(), first.endReasonId(), first.source(),
                first.continuesEmploymentId()));
        nextDay();

        SyncReport back = run();

        assertEquals(1, back.employmentsStarted());
        Employment second = employmentStore.findByUser("alice").getFirst();
        assertNotEquals(first.personId(), second.personId());
        assertEquals(List.of("Internal Medicine 2026-06-16 null - primary"), assignmentsOf("alice"));
        // The old person keeps what the sync gave them.
        assertEquals(List.of(new ValidPeriod(LocalDate.of(2026, 6, 15), null)),
                assignmentStore.rowsOfEmployment(first.id()).stream().map(Assignment::period).toList());
    }

    @Test
    void aSecondRunWithNoChangeDoesNothing() {
        set("boss", "med", "director", null, null);
        set("alice", "med", null, "boss", null);
        run();

        SyncReport again = run();

        assertEquals(new SyncReport(2, 0, 0, 0, 0, 0, 0, List.of(), List.of()), again);
    }

    @Test
    void followsANewDepartmentANewTitleAndANewManagerOnALaterDay() {
        set("boss", "med", null, null, null);
        set("chief", "Surgery", null, null, null);
        set("alice", "med", "staff", "boss", null);
        run();
        nextDay();
        set("alice", "Surgery", "staff", "chief", null);
        set("boss", "med", "director", null, null);

        SyncReport report = run();

        assertEquals(1, report.assignmentsMoved());
        assertEquals(1, report.assignmentsChanged());
        assertEquals(1, report.reportingLinesChanged());
        assertEquals(0, report.employmentsStarted());
        assertEquals(List.of("Internal Medicine 2026-06-15 2026-06-16 staff primary",
                "Surgery 2026-06-16 null staff primary"), assignmentsOf("alice"));
        assertEquals(List.of("Internal Medicine 2026-06-15 2026-06-16 - primary",
                "Internal Medicine 2026-06-16 null director primary"), assignmentsOf("boss"));
        // The line to the old manager ended with the old assignment.
        assertEquals(List.of("boss 2026-06-15 2026-06-16", "chief 2026-06-16 null"), managersOf("alice"));
    }

    @Test
    void aNewTitleKeepsTheWorkLocationAndTheCostCenterOfTheAssignment() {
        refItems.insert(new RefItem("home", RefKind.WORK_LOCATION, "home", "Home", Map.of(), 0, Map.of(), true));
        refItems.insert(new RefItem("cc", RefKind.COST_CENTER, "cc", "CC", Map.of(), 0, Map.of(), true));
        set("boss", "med", null, null, null);
        run();
        Assignment row = assignmentStore.rowsOfEmployment(employmentStore.findByUser("boss").getFirst().id())
                .getFirst();
        assignments().changeDetails(row.assignmentId(), new AssignmentValues(null, null,
                row.period().from().toString(), null, null, null, null, "home", "cc", row.head(), row.primary(), false,
                null, null, null, null, null));
        nextDay();
        set("boss", "med", "director", null, null);

        run();

        Assignment last = assignmentStore.rows(row.assignmentId()).getLast();
        assertEquals("t1", last.jobTitleId());
        assertEquals("home", last.workLocationId());
        assertEquals("cc", last.costCenterId());
    }

    @Test
    void aChangeOnTheSameDayLeavesNoTrace() {
        set("boss", "med", null, null, null);
        set("chief", "Surgery", null, null, null);
        set("alice", "med", null, "boss", null);
        run();
        set("alice", "Surgery", null, "chief", null);

        SyncReport moved = run();

        assertEquals(List.of("Surgery 2026-06-15 null - primary"), assignmentsOf("alice"));
        assertEquals(List.of("chief 2026-06-15 null"), managersOf("alice"));
        // Every write is in the change log, the removals too.
        assertEquals(List.of("DELETE FUHITO_REPORTING_LINE", "DELETE FUHITO_ASSIGNMENT", "CREATE FUHITO_ASSIGNMENT",
                "CREATE FUHITO_REPORTING_LINE"), moved.writes().stream()
                .map(w -> (w.deleted() ? "DELETE" : w.created() ? "CREATE" : "UPDATE") + " " + w.resourceType())
                .toList());

        set("alice", "Surgery", null, "boss", null);
        run();
        assertEquals(List.of("boss 2026-06-15 null"), managersOf("alice"));
        set("alice", "Surgery", null, null, null);
        assertEquals(1, run().reportingLinesChanged());
        assertEquals(List.of(), managersOf("alice"));
    }

    @Test
    void aRemovedDepartmentEndsTheAssignmentAndARemovedManagerEndsTheLine() {
        set("boss", "med", null, null, null);
        set("alice", "med", null, "boss", null);
        set("bob", "med", null, "boss", null);
        run();
        nextDay();
        set("alice", null, null, "boss", null);
        set("bob", "med", null, null, null);

        SyncReport report = run();

        assertEquals(1, report.assignmentsEnded());
        assertEquals(1, report.reportingLinesChanged());
        assertEquals(List.of("Internal Medicine 2026-06-15 2026-06-16 - primary"), assignmentsOf("alice"));
        assertEquals(List.of("boss 2026-06-15 2026-06-16"), managersOf("alice"));
        assertEquals(List.of("boss 2026-06-15 2026-06-16"), managersOf("bob"));
        assertEquals(List.of("Internal Medicine 2026-06-15 null - primary"), assignmentsOf("bob"));
    }

    @Test
    void asksForTheManagersOfAPieceAtOnceAndFindsOneThatOnlyTheApiKnows() {
        federated.add("ldap-boss");
        set("boss", "med", null, null, null);
        set("alice", "med", null, "boss", null);
        set("bob", "med", null, "ldap-boss", null);

        SyncReport report = run();

        assertEquals(List.of(), report.unmatched());
        assertEquals(2, report.reportingLinesChanged());
        assertEquals(List.of("ldap-boss 2026-06-15 null"), managersOf("bob"));
        assertEquals(1, usersAsked.size());
        assertEquals(Set.of("boss", "ldap-boss"), Set.copyOf(usersAsked.getFirst()));
    }

    @Test
    void reportsWhatMatchesNothingAndSkipsOnlyThatPart() {
        set("alice", "Radiology", null, null, null);
        set("bob", "Office", null, null, null);
        set("carol", "med", "Wizard", "nobody", null);
        set("dave", "med", null, "dave", null);

        SyncReport report = run();

        assertEquals(List.of(
                new Unmatched("alice", "department", "Radiology", "unknown"),
                new Unmatched("bob", "department", "Office", "ambiguous"),
                new Unmatched("carol", "title", "Wizard", "unknown"),
                new Unmatched("carol", "manager", "nobody", "unknown"),
                new Unmatched("dave", "manager", "dave", "unknown")), report.unmatched());
        assertEquals(List.of(), assignmentsOf("alice"));
        assertEquals(List.of(), assignmentsOf("bob"));
        assertEquals(List.of("Internal Medicine 2026-06-15 null - primary"), assignmentsOf("carol"));
        assertEquals(List.of(), managersOf("carol"));
        assertEquals(2, report.assignmentsAdded());
    }

    @Test
    void usesAnEmploymentThatExistsAndLeavesOtherAssignmentsAlone() {
        set("alice", "med", null, null, null);
        String employment = employments().start(new NewEmployment("alice", "2020-04-01", null, "OLD-1", null, null,
                null, null, null, null, null)).employment().id();
        assignments().add(new AssignmentValues(employment, surgery, "2020-04-01", null, null, null, null, null,
                true, null, null, null, null, null, null));

        SyncReport report = run();

        assertEquals(0, report.employmentsStarted());
        assertEquals(1, report.assignmentsAdded());
        // The assignment made by hand stays primary, so the one of the sync is not.
        assertEquals(List.of("Surgery 2020-04-01 null - primary", "Internal Medicine 2026-06-15 null -"),
                assignmentsOf("alice"));

        nextDay();
        set("alice", null, null, null, null);
        run();
        assertEquals(List.of("Surgery 2020-04-01 null - primary", "Internal Medicine 2026-06-15 2026-06-16 -"),
                assignmentsOf("alice"));
    }

    @Test
    void aStaffNumberThatCannotBeUsedDoesNotStopTheUser() {
        set("alice", "med", null, null, "E-1");
        set("bob", "med", null, null, "E-1");
        set("carol", "med", null, null, "not a code");

        SyncReport report = run();

        assertEquals(3, report.employmentsStarted());
        assertEquals("E-1", employmentStore.findByUser("alice").getFirst().staffNumber());
        assertEquals(null, employmentStore.findByUser("bob").getFirst().staffNumber());
        assertEquals(null, employmentStore.findByUser("carol").getFirst().staffNumber());
    }

    @Test
    void doesNothingUnlessTheRealmTurnedItOn() {
        enabled = false;
        set("alice", "med", null, null, null);

        FuhitoException e = assertThrows(FuhitoException.class, this::run);

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("sync.disabled", e.code());
        assertTrue(employmentStore.employments.isEmpty());
        assertEquals(LocalDate.of(2026, 6, 15), LocalDate.now(clock));
    }

    // 23:00 on June 14 in New York, where the realm is. Surgery is in Tokyo, where it is already June 15.
    private void putSurgeryOnTheNextDate() {
        rule = BusinessDay.rule("America/New_York", null);
        new OrgUnitService(orgUnitStore, externalIds, assignmentStore, refItems, clock).setTime(surgery, "Asia/Tokyo", null);
    }

    @Test
    void aChangeTakesEffectOnTheDateOfTheMatchedOrgUnit() {
        putSurgeryOnTheNextDate();
        set("boss", "med", null, null, null);
        set("alice", "Surgery", "staff", "boss", null);

        SyncReport report = run();

        assertEquals(2, report.assignmentsAdded());
        assertEquals(List.of("Internal Medicine 2026-06-14 null - primary"), assignmentsOf("boss"));
        assertEquals(List.of("Surgery 2026-06-15 null staff primary"), assignmentsOf("alice"));
        assertEquals(List.of("boss 2026-06-15 null"), managersOf("alice"));
        assertEquals(LocalDate.of(2026, 6, 15), employmentStore.findByUser("alice").getFirst().period().from());
        assertEquals(new SyncReport(2, 0, 0, 0, 0, 0, 0, List.of(), List.of()), run());
    }

    @Test
    void aMoveBetweenUnitsOnDifferentDatesKeepsOneEmploymentAndOneAssignment() {
        putSurgeryOnTheNextDate();
        set("alice", "Surgery", null, null, null);
        set("bob", "med", null, null, null);
        run();

        // Alice goes to a unit where it is still the day before her employment starts.
        set("alice", "med", "staff", null, null);
        set("bob", "Surgery", null, null, null);
        SyncReport moved = run();

        assertEquals(2, moved.assignmentsMoved());
        assertEquals(0, moved.employmentsStarted());
        assertEquals(1, employmentStore.findByUser("alice").size());
        assertEquals(List.of("Internal Medicine 2026-06-15 null staff primary"), assignmentsOf("alice"));
        assertEquals(List.of("Internal Medicine 2026-06-14 2026-06-15 - primary", "Surgery 2026-06-15 null - primary"),
                assignmentsOf("bob"));
        assertEquals(new SyncReport(2, 0, 0, 0, 0, 0, 0, List.of(), List.of()), run());

        set("alice", "med", "director", null, null);
        assertEquals(1, run().assignmentsChanged());
        assertEquals(List.of("Internal Medicine 2026-06-15 null director primary"), assignmentsOf("alice"));

        attributes.remove("alice");
        attributes.remove("bob");
        assertEquals(2, run().assignmentsEnded());
        assertEquals(List.of(), assignmentsOf("alice"));
        assertEquals(List.of("Internal Medicine 2026-06-14 2026-06-15 - primary"), assignmentsOf("bob"));
    }

    @Test
    void aTitleWithANameThatTwoJobTitlesShareMatchesTheFirst() {
        refItems.insert(new RefItem("t3", RefKind.JOB_TITLE, "staff-2", "Staff", Map.of(), 0, Map.of(), true));
        set("alice", "med", "Staff", null, null);

        run();

        assertEquals(List.of("Internal Medicine 2026-06-15 null staff primary"), assignmentsOf("alice"));
    }

    @Test
    void anAssignmentThatEndedForItsUnitIsNotFoundAgainFromTheDateOfTheRealm() {
        putSurgeryOnTheNextDate();
        clock = Clock.offset(clock, Duration.ofDays(-2));
        set("alice", "Surgery", "staff", null, null);
        run();
        nextDay();
        nextDay();

        // Surgery is on June 15, so the assignment ends there. The realm is still on June 14.
        attributes.remove("alice");
        assertEquals(1, run().assignmentsEnded());
        assertEquals(List.of("Surgery 2026-06-13 2026-06-15 staff primary"), assignmentsOf("alice"));
        assertEquals(new SyncReport(0, 0, 0, 0, 0, 0, 0, List.of(), List.of()), run());
    }

    @Test
    void anAssignmentWithANewTitleFromTodayIsReadOnTheDateOfItsUnit() {
        putSurgeryOnTheNextDate();
        clock = Clock.offset(clock, Duration.ofDays(-2));
        set("alice", "Surgery", "staff", null, null);
        run();
        nextDay();
        nextDay();

        // The old title still holds on the date of the realm. The new one holds on the date of Surgery.
        set("alice", "Surgery", "director", null, null);
        assertEquals(1, run().assignmentsChanged());
        assertEquals(List.of("Surgery 2026-06-13 2026-06-15 staff primary",
                "Surgery 2026-06-15 null director primary"), assignmentsOf("alice"));
        assertEquals(new SyncReport(1, 0, 0, 0, 0, 0, 0, List.of(), List.of()), run());
    }

    @Test
    void aChangeThatIsPlannedForLaterIsNotTakenForTheStateOfToday() {
        putSurgeryOnTheNextDate();
        set("boss", "med", "staff", null, null);
        run();
        String assignment = assignmentStore.rowsBySourceOn("scim", LocalDate.of(2026, 6, 14)).getFirst()
                .assignmentId();
        // Someone plans a new title from the next day. That day already started for Surgery, not for this unit.
        assignments().changeDetails(assignment, new AssignmentValues(null, null, "2026-06-15", null, "director",
                null, null, null, true, false, null, null, null, null, null));

        assertEquals(new SyncReport(1, 0, 0, 0, 0, 0, 0, List.of(), List.of()), run());
    }

    @Test
    void theAssignmentFollowsTheUserToAnotherEmployment() {
        set("alice", "med", null, null, null);
        run();
        nextDay();
        // An older employment that was entered later. It is the first one of the user now.
        employments().start(new NewEmployment("alice", "2020-04-01", null, "OLD-1", null, null, null, null, null,
                null, null));

        SyncReport report = run();

        assertEquals(1, report.assignmentsMoved());
        assertEquals(0, report.employmentsStarted());
        assertEquals(List.of("Internal Medicine 2026-06-16 null - primary",
                "Internal Medicine 2026-06-15 2026-06-16 - primary"), assignmentsOf("alice"));
    }

    @Test
    void aPrimaryAssignmentThatEndedDoesNotKeepTheFlag() {
        set("alice", "med", null, null, null);
        String employment = employments().start(new NewEmployment("alice", "2020-04-01", null, null, null, null,
                null, null, null, null, null)).employment().id();
        assignments().add(new AssignmentValues(employment, surgery, "2020-04-01", "2026-01-01", null, null, null,
                null, true, null, null, null, null, null, null));

        run();

        assertEquals(List.of("Surgery 2020-04-01 2026-01-01 - primary",
                "Internal Medicine 2026-06-15 null - primary"), assignmentsOf("alice"));
    }

    @Test
    void aDottedLineAndAnEndedLineAreLeftAlone() {
        set("boss", "med", null, null, null);
        set("alice", "med", null, "boss", null);
        run();
        String assignment = assignmentStore.rowsBySourceOn("scim", LocalDate.of(2026, 6, 15)).stream()
                .filter(row -> unitName(row).equals("Internal Medicine"))
                .map(Assignment::assignmentId)
                .filter(id -> !lineStore.findByAssignment(id).isEmpty())
                .findFirst().orElseThrow();
        lineStore.insert(new ReportingLine("dotted", assignment, "boss", "boss", ReportingLine.Kind.DOTTED,
                ValidPeriod.startingAt(LocalDate.of(2026, 6, 15))));
        nextDay();
        set("alice", "med", null, null, null);
        assertEquals(1, run().reportingLinesChanged());
        nextDay();

        // The solid line ended the day before, and the dotted one is not from the sync.
        assertEquals(0, run().reportingLinesChanged());
        assertEquals(List.of("boss 2026-06-15 2026-06-16", "boss 2026-06-15 null"), managersOf("alice"));
    }

    @Test
    void anAssignmentByHandThatIsNotPrimaryLeavesTheFlagToTheSync() {
        set("alice", "med", null, null, null);
        String employment = employments().start(new NewEmployment("alice", "2020-04-01", null, null, null, null,
                null, null, null, null, null)).employment().id();
        assignments().add(new AssignmentValues(employment, surgery, "2020-04-01", null, null, null, null, null,
                false, null, null, null, null, null, null));

        run();

        assertEquals(List.of("Surgery 2020-04-01 null -", "Internal Medicine 2026-06-15 null - primary"),
                assignmentsOf("alice"));
    }
}
