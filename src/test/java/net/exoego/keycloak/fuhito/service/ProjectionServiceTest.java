package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.store.OrganizationGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hospital that is linked to a Keycloak organization, with a nursing department and a ward below it.
 */
class ProjectionServiceTest {

    private static final class MemoryGroups implements OrganizationGroups {
        private boolean enabled = true;
        private final Map<String, Group> groups = new LinkedHashMap<>();
        private final Map<String, String> organizationOfGroup = new HashMap<>();
        private final Map<String, Set<String>> members = new HashMap<>();
        private final Map<String, Set<String>> organizationMembers = new HashMap<>();
        // Users that Keycloak no longer has.
        private final Set<String> gone = new HashSet<>();
        private int nextId = 1;

        @Override
        public boolean projectionEnabled() {
            return enabled;
        }

        @Override
        public List<Group> groups(String organizationId) {
            List<Group> result = new ArrayList<>();
            collect(organizationId, null, result);
            return result;
        }

        private void collect(String organizationId, String parentId, List<Group> result) {
            for (Group group : List.copyOf(groups.values())) {
                if (organizationId.equals(organizationOfGroup.get(group.id()))
                        && Objects.equals(group.parentId(), parentId)) {
                    result.add(group);
                    collect(organizationId, group.id(), result);
                }
            }
        }

        @Override
        public String createGroup(String organizationId, String name, String parentId, String orgUnitId) {
            String id = "g" + nextId++;
            groups.put(id, new Group(id, name, parentId, orgUnitId));
            organizationOfGroup.put(id, organizationId);
            return id;
        }

        @Override
        public void renameGroup(String groupId, String name) {
            Group group = groups.get(groupId);
            groups.put(groupId, new Group(groupId, name, group.parentId(), group.orgUnitId()));
        }

        @Override
        public void moveGroup(String organizationId, String groupId, String parentId) {
            Group group = groups.get(groupId);
            groups.put(groupId, new Group(groupId, group.name(), parentId, group.orgUnitId()));
        }

        @Override
        public void removeGroup(String groupId) {
            if (groups.remove(groupId) == null) {
                throw new IllegalStateException("removed twice: " + groupId);
            }
            members.remove(groupId);
            for (Group group : List.copyOf(groups.values())) {
                if (groupId.equals(group.parentId())) {
                    removeGroup(group.id());
                }
            }
        }

        @Override
        public Set<String> members(String groupId) {
            return Set.copyOf(members.getOrDefault(groupId, Set.of()));
        }

        @Override
        public boolean join(String groupId, String userId) {
            if (gone.contains(userId)) {
                return false;
            }
            return members.computeIfAbsent(groupId, id -> new HashSet<>()).add(userId);
        }

        @Override
        public boolean leave(String groupId, String userId) {
            if (gone.contains(userId)) {
                return false;
            }
            return members.get(groupId).remove(userId);
        }

        @Override
        public boolean addOrganizationMember(String organizationId, String userId) {
            return organizationMembers.computeIfAbsent(organizationId, id -> new HashSet<>()).add(userId);
        }

        List<String> describe(String organizationId) {
            return groups(organizationId).stream()
                    .map(g -> g.name() + (g.parentId() == null ? "" : " < " + groups.get(g.parentId()).name())
                            + " " + members(g.id()).stream().sorted().toList())
                    .toList();
        }
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T03:00:00Z"), ZoneId.of("Asia/Tokyo"));

    private final MemoryGroups keycloak = new MemoryGroups();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryReportingLineStore lines = new MemoryReportingLineStore();
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, assignmentStore, refItems, CLOCK);
    private final EmploymentService employments =
            new EmploymentService(employmentStore, assignmentStore, refItems, MemoryPersonStore.anyUser(), lines);
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, employmentStore, orgUnitStore, refItems, lines);
    private final OrganizationLinkService links = new OrganizationLinkService(orgUnitStore, id -> true);

    private String hospital;
    private String nursing;
    private String ward;
    private String nurseAssignment;

    @BeforeEach
    void createTheHospital() {
        hospital = unit("Hospital", null);
        nursing = unit("Nursing", hospital);
        ward = unit("Ward", nursing);
        links.link(hospital, "org-x");
        assign("director", hospital, "2026-04-01");
        assign("head-nurse", nursing, "2026-04-01");
        nurseAssignment = assign("nurse", ward, "2026-04-01");
        assign("next-year", ward, "2027-04-01");
    }

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2026-04-01", null,
                null, null, null)).unit().id();
    }

    private String assign(String userId, String orgUnitId, String validFrom) {
        String employment = employments.start(new NewEmployment(userId, "2026-04-01", null, null, null, null, null,
                null, null, null, null)).employment().id();
        return assignments.add(new AssignmentValues(employment, orgUnitId, validFrom, null, null, null, null, null,
                null, null, null, null, null, null, null)).assignmentId();
    }

    private ProjectionReport rebuild() {
        return new ProjectionService(orgUnitStore, assignmentStore, employmentStore, refItems, keycloak, CLOCK)
                .rebuild();
    }

    @Test
    void mirrorsTheUnitsBelowALinkedUnitAndTheAssignmentsOfToday() {
        ProjectionReport report = rebuild();

        assertEquals(new ProjectionReport(1, 2, 0, 0, 0, 2, 0, 3), report);
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]"), keycloak.describe("org-x"));
        assertEquals(Set.of("director", "head-nurse", "nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(nursing, keycloak.groups("org-x").getFirst().orgUnitId());
    }

    @Test
    void aSecondRebuildWithNoChangeDoesNothing() {
        rebuild();

        assertEquals(new ProjectionReport(1, 0, 0, 0, 0, 0, 0, 0), rebuild());
    }

    @Test
    void followsARenameAMoveANewUnitAndAnEndedAssignment() {
        rebuild();
        orgUnits.changeDetails(nursing, new OrgUnitChange("2026-06-01", "Nursing Division", null, null, null, null,
                null, null, null));
        orgUnits.move(ward, hospital, "2026-06-01");
        unit("Pharmacy", hospital);
        assignments.end(nurseAssignment, "2026-06-01");
        assign("pharmacist", ward, "2026-05-01");

        ProjectionReport report = rebuild();

        assertEquals(new ProjectionReport(1, 1, 1, 1, 0, 1, 1, 1), report);
        assertEquals(List.of("Nursing Division [head-nurse]", "Ward [pharmacist]", "Pharmacy []"),
                keycloak.describe("org-x"));
        // The nurse left the ward. The projection never takes a user out of the organization.
        assertEquals(Set.of("director", "head-nurse", "nurse", "pharmacist"),
                keycloak.organizationMembers.get("org-x"));
    }

    @Test
    void removesTheGroupsOfUnitsThatEndedAndKeepsGroupsMadeByHand() {
        rebuild();
        String handMade = keycloak.createGroup("org-x", "Made by hand", null, null);
        keycloak.join(handMade, "someone");
        assignmentStore.rows.removeIf(row -> row.orgUnitId().equals(ward) || row.orgUnitId().equals(nursing));
        orgUnits.end(ward, "2026-06-01");
        orgUnits.end(nursing, "2026-06-01");

        ProjectionReport report = rebuild();

        assertEquals(2, report.groupsRemoved());
        assertEquals(List.of("Made by hand [someone]"), keycloak.describe("org-x"));
    }

    @Test
    void aMemberAddedByHandToAMirroredGroupIsTakenOut() {
        rebuild();
        String wardGroup = keycloak.groups("org-x").get(1).id();
        keycloak.join(wardGroup, "intruder");

        ProjectionReport report = rebuild();

        assertEquals(1, report.membersLeft());
        assertEquals(Set.of("nurse"), keycloak.members(wardGroup));
    }

    @Test
    void eachLinkedUnitGoesToItsOwnOrganizationAndOthersAreLeftAlone() {
        String otherHospital = unit("Other Hospital", null);
        unit("Other Ward", otherHospital);
        String notLinked = unit("Not Linked", null);
        unit("Below Not Linked", notLinked);
        links.link(otherHospital, "org-y");
        assign("nurse", otherHospital, "2026-04-01");

        ProjectionReport report = rebuild();

        assertEquals(2, report.organizations());
        assertEquals(List.of("Other Ward []"), keycloak.describe("org-y"));
        assertEquals(Set.of("nurse"), keycloak.organizationMembers.get("org-y"));
        assertEquals(2, keycloak.groups("org-x").size());
        assertEquals(3, keycloak.groups.size());
    }

    @Test
    void aLinkedUnitThatDoesNotExistTodayIsSkipped() {
        orgUnitStore.insertUnit(new OrgUnit("ghost", null, null, null, "org-ghost"));

        assertEquals(1, rebuild().organizations());
    }

    @Test
    void rejectsTwoSiblingsWithOneName() {
        String twin = unit("Nursing", hospital);

        FuhitoException e = assertThrows(FuhitoException.class, this::rebuild);

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("projection.duplicate_name", e.code());
        assertEquals("Nursing", e.params().get("name"));
        // Siblings with one name are ordered by ID, so either of the two can be the one that is named.
        assertTrue(Set.of(twin, nursing).contains(e.params().get("orgUnitId")));
    }

    @Test
    void doesNothingUnlessTheRealmTurnedItOn() {
        keycloak.enabled = false;

        FuhitoException e = assertThrows(FuhitoException.class, this::rebuild);

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("projection.disabled", e.code());
        assertEquals(0, keycloak.groups.size());
    }

    @Test
    void mirrorsEachLinkedUnitAsItIsOnItsOwnDate() {
        String clinic = unit("Clinic", null);
        String lab = orgUnits.create(new NewOrgUnit(null, "Lab", null, null, null, null, null, clinic, "2026-06-15",
                null, null, null, null)).unit().id();
        links.link(clinic, "org-y");
        assign("technician", lab, "2026-06-15");
        // 23:00 on June 14 in New York, where the realm is. The clinic is in Tokyo, where it is already June 15.
        orgUnits.setTime(clinic, "Asia/Tokyo", null);
        Today today = new UnitDays(orgUnitStore, BusinessDay.rule("America/New_York", null), CLOCK);

        new ProjectionService(orgUnitStore, assignmentStore, employmentStore, refItems, keycloak, today).rebuild();

        assertEquals(List.of("Lab [technician]"), keycloak.describe("org-y"));
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]"), keycloak.describe("org-x"));
    }

    @Test
    void removesAGroupBelowAGroupThatStays() {
        rebuild();
        assignmentStore.rows.removeIf(row -> row.orgUnitId().equals(ward));
        orgUnits.end(ward, "2026-06-01");

        ProjectionReport report = rebuild();

        assertEquals(1, report.groupsRemoved());
        assertEquals(List.of("Nursing [head-nurse]"), keycloak.describe("org-x"));
    }

    @Test
    void whenTwoGroupsNameOneUnitTheFirstMirrorsItAndTheOtherGoes() {
        rebuild();
        keycloak.createGroup("org-x", "Copy of nursing", null, nursing);

        ProjectionReport report = rebuild();

        assertEquals(1, report.groupsRemoved());
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]"), keycloak.describe("org-x"));
    }

    @Test
    void aUserThatKeycloakNoLongerHasIsNotCounted() {
        rebuild();
        // One user has an assignment and one sits in a group by hand. Keycloak has neither of them.
        keycloak.gone.add("pharmacist");
        keycloak.gone.add("intruder");
        assign("pharmacist", ward, "2026-05-01");
        String wardGroup = keycloak.groups("org-x").get(1).id();
        keycloak.members.get(wardGroup).add("intruder");

        ProjectionReport report = rebuild();

        assertEquals(0, report.membersJoined());
        assertEquals(0, report.membersLeft());
    }
}
