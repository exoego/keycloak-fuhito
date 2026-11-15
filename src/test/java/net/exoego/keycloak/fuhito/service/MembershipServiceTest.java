package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final Set<String> knownUsers = Set.of("doctor", "nurse", "clerk", "idle");
    private final EmploymentService employments =
            new EmploymentService(employmentStore, assignmentStore, refItems, MemoryPersonStore.linked(knownUsers),
            new MemoryReportingLineStore(), new MemoryUnitRoleStore(), new AnyEmployer());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, employmentStore, orgUnitStore, refItems,
            new MemoryReportingLineStore());
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems, CLOCK);
    private final MembershipService service = new MembershipService(assignmentStore, employmentStore, orgUnitStore,
            refItems, knownUsers::contains, CLOCK);

    private String hospital;
    private String nursing;
    private String ward;
    private String otherHospital;
    private String doctorEmployment;
    private String nurseEmployment;
    private String clerkEmployment;

    @BeforeEach
    void createTheHospital() {
        assignmentStore.orgUnits = orgUnitStore;
        assignmentStore.employments = employmentStore;
        for (String[] item : new String[][] {{"OCCUPATION", "physician"}, {"OCCUPATION", "nurse"},
                {"JOB_TITLE", "director"}, {"EMPLOYMENT_TYPE", "part-time"}, {"GRADE", "g2"},
                {"ASSIGNMENT_KIND", "regular"}}) {
            refItems.insert(new RefItem(item[0] + "-" + item[1], RefKind.valueOf(item[0]), item[1], item[1], Map.of(),
                    0, Map.of(), true));
        }
        hospital = unit("Hospital", null);
        nursing = unit("Nursing", hospital);
        ward = unit("Ward", nursing);
        otherHospital = unit("Other Hospital", null);
        doctorEmployment = employ("doctor", "D-1", null, null);
        nurseEmployment = employ("nurse", "N-1", "part-time", "g2");
        clerkEmployment = employ("clerk", null, null, null);
    }

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2026-04-01", null,
                null, null, null)).unit().id();
    }

    private String employ(String userId, String staffNumber, String employmentType, String grade) {
        return employments.start(new NewEmployment(userId, "2026-04-01", null, staffNumber, employmentType, grade,
                null, employmentType == null ? null : new BigDecimal("50"), null, null, null)).employment().id();
    }

    private String assign(String employmentId, String orgUnitId, String validFrom, String occupation, String jobTitle,
            boolean primary, Integer displayOrder) {
        return assignments.add(new AssignmentValues(employmentId, orgUnitId, validFrom, null, jobTitle, occupation,
                "regular", jobTitle != null, primary, null, null, displayOrder, null, null, null)).assignmentId();
    }

    @Test
    void aPersonWhoWorksOutsideTheEmployerIsSeconded() {
        // The hospital employs the nurse, and the other hospital employs the doctor. Both work in the ward.
        String ownStaff = employments.start(new NewEmployment("nurse", "2026-04-01", null, null, null, null, null, null,
                null, null, null, null, hospital)).employment().id();
        String guest = employments.start(new NewEmployment("doctor", "2026-04-01", null, null, null, null, null, null,
                null, null, null, null, otherHospital)).employment().id();
        assign(ownStaff, ward, "2026-04-01", null, null, true, 1);
        assign(guest, ward, "2026-04-01", null, null, false, 2);
        assign(guest, otherHospital, "2026-04-01", null, null, true, 1);

        List<Member> inWard = service.members(ward, on("2026-04-01"));
        assertEquals(List.of("nurse", "doctor"), userIds(inWard));
        assertEquals(List.of(false, true), inWard.stream().map(Member::seconded).toList());
        assertEquals(List.of(hospital, otherHospital),
                inWard.stream().map(member -> member.employment().employerId()).toList());

        // The list can keep one side only.
        assertEquals(List.of("doctor"), userIds(service.members(ward, seconded(true, false))));
        assertEquals(List.of("nurse"), userIds(service.members(ward, seconded(false, false))));
        assertEquals(List.of("doctor"), userIds(service.members(hospital, seconded(true, true))));
        assertEquals(List.of("nurse"), userIds(service.members(hospital, seconded(false, true))));
        assertEquals(List.of("doctor"), userIds(service.members(otherHospital, seconded(false, true))));
        assertEquals(List.of(), userIds(service.members(otherHospital, seconded(true, true))));

        // The doctor is seconded in the ward, and not in the hospital that employs the doctor.
        List<Member> ofDoctor = service.assignmentsOf("doctor", "2026-04-01", false);
        assertEquals(List.of(otherHospital, ward), ofDoctor.stream().map(m -> m.orgUnit().unit().id()).toList());
        assertEquals(List.of(false, true), ofDoctor.stream().map(Member::seconded).toList());
    }

    private static MemberQuery seconded(boolean wanted, boolean includeDescendants) {
        return new MemberQuery("2026-04-01", includeDescendants, null, null, null, null, null, null, null, wanted);
    }

    @Test
    void aCursorGoesOnAfterTheRowThatItNames() {
        assign(doctorEmployment, hospital, "2026-04-01", null, null, true, 1);
        assign(nurseEmployment, ward, "2026-04-01", null, null, true, 2);
        assign(clerkEmployment, ward, "2026-04-01", null, null, true, 1);
        List<Member> all = service.members(hospital, below("2026-04-01"));
        assertEquals(List.of("doctor", "clerk", "nurse"), userIds(all));

        Assignment first = all.get(0).assignment();
        Assignment second = all.get(1).assignment();
        String afterFirst = Cursor.encode(Cursor.MEMBERS, first.orgUnitId(), Integer.toString(first.displayOrder()),
                first.period().from().toString(), first.assignmentId());
        String afterSecond = Cursor.encode(Cursor.MEMBERS, second.orgUnitId(), Integer.toString(second.displayOrder()),
                second.period().from().toString(), second.assignmentId());

        assertEquals(List.of("clerk", "nurse"), userIds(service.members(hospital,
                new MemberQuery("2026-04-01", true, null, null, null, null, null, null, afterFirst, null))));
        assertEquals(List.of("nurse"), userIds(service.members(hospital,
                new MemberQuery("2026-04-01", true, null, null, null, null, 5, null, afterSecond, null))));
        // In one org unit, the cursor uses the display order and what comes after it.
        assertEquals(List.of("nurse"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, null, null, null, null, afterSecond, null))));

        assertEquals("invalid.cursor_with_first", assertThrows(FuhitoException.class, () -> service.members(hospital,
                new MemberQuery("2026-04-01", true, null, null, null, 1, null, null, afterFirst, null))).code());
        for (String bad : List.of(Cursor.encode(Cursor.MEMBERS, "one"),
                Cursor.encode(Cursor.MEMBERS, "u", "x", "2026-04-01", "a"),
                Cursor.encode(Cursor.MEMBERS, "u", "1", "soon", "a"),
                // A cursor of the list of people does not fit this list.
                Cursor.encode(Cursor.PEOPLE, "p"))) {
            assertEquals("invalid.cursor", assertThrows(FuhitoException.class, () -> service.members(hospital,
                    new MemberQuery("2026-04-01", true, null, null, null, null, null, null, bad, null))).code());
        }
    }

    private static MemberQuery on(String asOf) {
        return new MemberQuery(asOf, false, null, null, null, null, null);
    }

    private static MemberQuery below(String asOf) {
        return new MemberQuery(asOf, true, null, null, null, null, null);
    }

    private static List<String> userIds(List<Member> members) {
        return members.stream().map(m -> m.employment().userId()).toList();
    }

    @Test
    void listsTheMembersOfAUnitOnADate() {
        assign(nurseEmployment, ward, "2026-04-01", "nurse", null, true, 2);
        assign(doctorEmployment, ward, "2026-04-01", "physician", "director", false, 1);
        assign(clerkEmployment, ward, "2026-10-01", null, null, true, 0);

        List<Member> spring = service.members(ward, on("2026-04-01"));
        assertEquals(List.of("doctor", "nurse"), userIds(spring));
        assertEquals(List.of("clerk", "doctor", "nurse"), userIds(service.members(ward, on("2026-10-01"))));
        assertEquals(List.of(), userIds(service.members(nursing, on("2026-10-01"))));

        Member doctor = spring.getFirst();
        assertEquals("physician", doctor.occupation());
        assertEquals("director", doctor.jobTitle());
        assertEquals("regular", doctor.kind());
        assertTrue(doctor.assignment().head());
        assertEquals("D-1", doctor.employment().staffNumber());
        assertNull(doctor.employmentType());
        assertNull(doctor.grade());
        assertEquals("Ward", doctor.orgUnit().version().name());
        assertNull(doctor.orgUnitPath());
        Member nurse = spring.get(1);
        assertEquals("part-time", nurse.employmentType());
        assertEquals("g2", nurse.grade());
        assertEquals(new BigDecimal("50"), nurse.terms().workPercent());
    }

    @Test
    void listsDescendantsInTreeOrder() {
        assign(clerkEmployment, ward, "2026-04-01", null, null, true, 0);
        assign(nurseEmployment, nursing, "2026-04-01", "nurse", null, true, 5);
        assign(doctorEmployment, hospital, "2026-04-01", "physician", "director", true, 9);
        assign(doctorEmployment, otherHospital, "2026-04-01", "physician", null, false, 0);

        assertEquals(List.of("doctor", "nurse", "clerk"), userIds(service.members(hospital, below("2026-04-01"))));
        assertEquals(List.of("nurse", "clerk"), userIds(service.members(nursing, below("2026-04-01"))));
        assertEquals(List.of("doctor"), userIds(service.members(hospital, on("2026-04-01"))));
        assertEquals(List.of("doctor"), userIds(service.members(otherHospital, below("2026-04-01"))));
    }

    @Test
    void filtersByOccupationJobTitleAndStatus() {
        assign(nurseEmployment, ward, "2026-04-01", "nurse", null, true, 2);
        assign(doctorEmployment, ward, "2026-04-01", "physician", "director", true, 1);
        assign(clerkEmployment, ward, "2026-04-01", null, null, true, 3);
        employments.changeTerms(nurseEmployment, new TermsChange("2026-10-01", "part-time", "g2", "ON_LEAVE", null,
                null));

        assertEquals(List.of("nurse"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, "nurse", null, null, null, null))));
        assertEquals(List.of("doctor"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, "director", null, null, null))));
        assertEquals(List.of(), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, "nurse", "director", null, null, null))));
        assertEquals(List.of("doctor", "nurse", "clerk"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, "ACTIVE", null, null))));
        assertEquals(List.of("doctor", "clerk"), userIds(service.members(ward,
                new MemberQuery("2026-10-01", false, null, null, "ACTIVE", null, null))));
        assertEquals(List.of("nurse"), userIds(service.members(ward,
                new MemberQuery("2026-10-01", false, null, null, "ON_LEAVE", null, null))));
        // The member on leave is still listed without a filter.
        assertEquals(3, service.members(ward, on("2026-10-01")).size());
    }

    @Test
    void listsTheAssignmentsOfADateAcrossTheRealm() {
        String director = assign(doctorEmployment, hospital, "2026-04-01", "physician", "director", true, null);
        String nurse = assign(nurseEmployment, ward, "2026-04-01", "nurse", null, true, null);
        assign(clerkEmployment, otherHospital, "2026-05-01", null, null, true, null);

        assertEquals(List.of(director), service.search(new MembershipService.Search(null, "director", null, null,
                "2026-04-15"), Page.of(null, null), null).stream().map(member -> member.assignment().assignmentId()).toList());
        assertEquals(List.of(nurse), service.search(new MembershipService.Search(ward, null, null, "regular",
                "2026-04-15"), Page.of(null, null), null).stream().map(member -> member.assignment().assignmentId()).toList());
        assertEquals(2, service.count(new MembershipService.Search(null, null, null, null, "2026-04-15")));
        assertEquals(3, service.count(new MembershipService.Search(null, null, null, null, "2026-05-01")));
        List<String> all = java.util.stream.Stream.of(director, nurse).sorted().toList();
        assertEquals(List.of(all.get(1)), service.search(new MembershipService.Search(null, null, null, null,
                "2026-04-15"), Page.of(null, null), Cursor.encode(Cursor.ASSIGNMENTS, all.get(0))).stream()
                .map(member -> member.assignment().assignmentId()).toList());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.count(
                new MembershipService.Search(null, "chief", null, null, null))).code());
    }

    @Test
    void pagesTheMembers() {
        assign(nurseEmployment, ward, "2026-04-01", null, null, true, 2);
        assign(doctorEmployment, ward, "2026-04-01", null, null, true, 1);
        assign(clerkEmployment, ward, "2026-04-01", null, null, true, 3);

        assertEquals(List.of("doctor", "nurse"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, null, null, 2))));
        assertEquals(List.of("nurse", "clerk"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, null, 1, null))));
        assertEquals(List.of("clerk"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, null, 2, 5))));
        assertEquals(List.of(), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, null, -1, 0))));
        assertEquals(List.of("doctor"), userIds(service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, null, -1, 1))));
        // The count is the same for every page.
        assertEquals(3, service.countMembers(ward, new MemberQuery("2026-04-01", false, null, null, null, 2, 1)));
    }

    @Test
    void rejectsABadMemberQuery() {
        assertEquals("org_unit.not_found",
                assertThrows(FuhitoException.class, () -> service.members("nope", on("2026-04-01"))).code());
        assertEquals("org_unit.not_valid_on",
                assertThrows(FuhitoException.class, () -> service.members(ward, on("2026-03-31"))).code());
        assertEquals("invalid.date",
                assertThrows(FuhitoException.class, () -> service.members(ward, on("x"))).code());
        assertEquals("invalid.choice", assertThrows(FuhitoException.class, () -> service.members(ward,
                new MemberQuery("2026-04-01", false, null, null, "GONE", null, null))).code());
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.members(ward,
                new MemberQuery("2026-04-01", false, "director", null, null, null, null)));
        assertEquals("ref_item.unknown", e.code());
        assertEquals(Map.of("kind", "OCCUPATION", "code", "director"), e.params());
        assertEquals("ref_item.unknown", assertThrows(FuhitoException.class, () -> service.members(ward,
                new MemberQuery("2026-04-01", false, null, "nope", null, null, null))).code());
    }

    @Test
    void withAPrimaryAssignmentInEachEmploymentTheOldestEmploymentComesFirst() {
        // The doctor works for the hospital from April, and also for the other hospital from May.
        String second = employments.start(new NewEmployment("doctor", "2026-05-01", null, null, null, null, null, null,
                null, null, null, null, otherHospital)).employment().id();
        assign(second, otherHospital, "2026-05-01", null, null, true, 0);
        assign(second, hospital, "2026-05-01", null, null, false, 0);
        // The primary assignment of the first employment starts later than the one of the second.
        assign(doctorEmployment, ward, "2026-06-01", null, null, true, 0);
        assign(doctorEmployment, nursing, "2026-06-01", null, null, false, 0);

        List<Member> july = service.assignmentsOf("doctor", "2026-07-01", false);

        assertEquals(List.of(ward, otherHospital, nursing, hospital),
                july.stream().map(m -> m.assignment().orgUnitId()).toList());
        assertEquals(List.of(true, true, false, false), july.stream().map(m -> m.assignment().primary()).toList());
    }

    @Test
    void listsTheAssignmentsOfAUserWithThePrimaryFirst() {
        assign(doctorEmployment, otherHospital, "2026-04-01", "physician", null, false, 0);
        String primary = assign(doctorEmployment, ward, "2026-05-01", "physician", "director", true, 0);
        assign(doctorEmployment, hospital, "2027-04-01", null, null, false, 0);

        List<Member> today = service.assignmentsOf("doctor", null, true);

        assertEquals(List.of(ward, otherHospital), today.stream().map(m -> m.assignment().orgUnitId()).toList());
        assertEquals(primary, today.getFirst().assignment().assignmentId());
        assertEquals(List.of("Hospital", "Nursing", "Ward"),
                today.getFirst().orgUnitPath().stream().map(s -> s.version().name()).toList());
        assertEquals(List.of("Other Hospital"),
                today.get(1).orgUnitPath().stream().map(s -> s.version().name()).toList());
        assertNull(service.assignmentsOf("doctor", "2026-06-15", false).getFirst().orgUnitPath());
        assertEquals(1, service.assignmentsOf("doctor", "2026-04-15", false).size());
        assertEquals(3, service.assignmentsOf("doctor", "2027-04-01", false).size());
        assertEquals(List.of(), service.assignmentsOf("idle", null, false));
        FuhitoException e = assertThrows(FuhitoException.class, () -> service.assignmentsOf("nope", null, false));
        assertEquals(FuhitoException.Reason.NOT_FOUND, e.reason());
        assertEquals("user.not_found", e.code());
    }

    @Test
    void readsTheHistoryOfAUserAcrossARehire() {
        String first = assign(clerkEmployment, ward, "2026-04-01", null, null, true, 0);
        assignments.changeDetails(first, new AssignmentValues(null, null, "2026-10-01", null, "director", null, null,
                true, true, null, null, null, null, null, null));
        String second = assign(clerkEmployment, hospital, "2026-07-01", null, null, false, 0);
        employments.end(clerkEmployment, "2027-04-01", null);
        String rehire = employments.start(new NewEmployment("clerk", "2028-04-01", null, null, null, null, null, null,
                null, null, null)).employment().id();
        String third = assign(rehire, ward, "2028-04-01", null, null, true, 0);

        UserHistory history = service.historyOf("clerk", employments, assignments);

        assertEquals("clerk", history.userId());
        assertEquals("clerk", history.personId());
        // The same history is read by person.
        Person clerk = new Person("clerk", "clerk", null, Map.of(), null, Map.of(), null, null);
        assertEquals(history, service.historyOfPerson(clerk, employments, assignments));
        assertEquals(service.assignmentsOf("clerk", "2026-06-01", true),
                service.assignmentsOfPerson("clerk", "2026-06-01", true));
        assertEquals(List.of(), service.assignmentsOfPerson("nope", null, false));
        assertEquals(List.of(clerkEmployment, rehire),
                history.employments().stream().map(v -> v.employment().id()).toList());
        assertEquals(List.of(first, second, third),
                history.assignments().stream().map(AssignmentView::assignmentId).toList());
        assertEquals(2, history.assignments().getFirst().rows().size());
        assertEquals(List.of(), service.historyOf("idle", employments, assignments).employments());
        assertEquals("user.not_found", assertThrows(FuhitoException.class,
                () -> service.historyOf("nope", employments, assignments)).code());
    }

    @Test
    void readsAMemberWhoseReferenceItemWasRemoved() {
        assign(doctorEmployment, ward, "2026-04-01", "physician", null, true, 0);
        refItems.delete("OCCUPATION-physician");

        assertNull(service.members(ward, on("2026-04-01")).getFirst().occupation());
    }

    @Test
    void withNoDateEachAssignmentIsJudgedOnTheDateOfItsOwnUnit() {
        // 01:00 on July 1 in Tokyo. It is still June 30 in New York.
        Clock base = Clock.fixed(Instant.parse("2026-06-30T16:00:00Z"), ZoneId.of("UTC"));
        orgUnits.setTime(otherHospital, "America/New_York", null);
        String endsInJune = assign(doctorEmployment, otherHospital, "2026-04-01", "physician", null, false, 0);
        assignments.end(endsInJune, "2026-07-01");
        String startsInJuly = assign(doctorEmployment, hospital, "2026-07-01", "physician", null, true, 0);
        assign(nurseEmployment, otherHospital, "2026-07-01", "nurse", null, false, 0);
        MembershipService zoned = new MembershipService(assignmentStore, employmentStore, orgUnitStore, refItems,
                knownUsers::contains, new UnitDays(orgUnitStore, BusinessDay.rule("Asia/Tokyo", null), base));

        List<Member> today = zoned.assignmentsOf("doctor", null, true);

        // The one in New York holds until its June 30 ends. The one in Tokyo started on its July 1.
        assertEquals(List.of(startsInJuly, endsInJune),
                today.stream().map(m -> m.assignment().assignmentId()).toList());
        assertEquals(List.of("Hospital"), today.getFirst().orgUnitPath().stream().map(s -> s.version().name()).toList());
        assertEquals(List.of(startsInJuly),
                zoned.assignmentsOf("doctor", "2026-07-01", false).stream()
                        .map(m -> m.assignment().assignmentId()).toList());
        assertEquals(List.of("doctor"), userIds(zoned.members(otherHospital, on(null))));
        assertEquals(List.of("nurse"), userIds(zoned.members(otherHospital, on("2026-07-01"))));
        assertEquals(List.of("doctor"), userIds(zoned.members(hospital, on(null))));
    }
}
