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
import java.util.Optional;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.store.OrganizationGroups;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A hospital that is linked to a Keycloak organization, with a nursing department and a ward below it.
 */
class ProjectionServiceTest {

    private final MemoryExternalIdStore externalIds = new MemoryExternalIdStore();
    private static final class MemoryGroups implements OrganizationGroups {
        private boolean enabled = true;
        private final Map<String, Group> groups = new LinkedHashMap<>();
        private final Map<String, String> organizationOfGroup = new HashMap<>();
        private final Map<String, Set<String>> members = new HashMap<>();
        private final Map<String, Set<String>> organizationMembers = new HashMap<>();
        // Users that Keycloak no longer has.
        private final Set<String> gone = new HashSet<>();
        // Users that only a user storage provider holds. The table of members does not show them.
        private final Set<String> outsideTable = new HashSet<>();
        private int nextId = 1;
        // How often a rebuild asked to put off the writes.
        private int flushes;
        // The users that each piece wrote memberships of, for the pieces that wrote any.
        private final List<List<String>> writesOfPieces = new ArrayList<>();
        private List<String> writesOfPiece = new ArrayList<>();

        @Override
        public <T> T inOneFlush(java.util.function.Supplier<T> work) {
            flushes++;
            writesOfPiece = new ArrayList<>();
            T result = work.get();
            if (!writesOfPiece.isEmpty()) {
                writesOfPieces.add(writesOfPiece);
            }
            return result;
        }

        @Override
        public boolean projectionEnabled() {
            return enabled;
        }

        @Override
        public List<String> organizations() {
            Set<String> result = new java.util.TreeSet<>(organizationMembers.keySet());
            organizationOfGroup.values().stream().filter(id -> !id.startsWith("realm:")).forEach(result::add);
            return List.copyOf(result);
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

        // Keycloak allows one name among the groups of one parent.
        private void requireFree(String organizationId, String name, String parentId, String groupId) {
            for (Group other : groups.values()) {
                if (!other.id().equals(groupId) && organizationId.equals(organizationOfGroup.get(other.id()))
                        && Objects.equals(other.parentId(), parentId) && other.name().equals(name)) {
                    throw new IllegalStateException("name taken: " + name);
                }
            }
        }

        @Override
        public String createGroup(String organizationId, String name, String parentId, String orgUnitId) {
            requireFree(organizationId, name, parentId, null);
            String id = "g" + nextId++;
            groups.put(id, new Group(id, name, parentId, orgUnitId));
            organizationOfGroup.put(id, organizationId);
            return id;
        }

        @Override
        public void renameGroup(String groupId, String name) {
            Group group = groups.get(groupId);
            requireFree(organizationOfGroup.get(groupId), name, group.parentId(), groupId);
            groups.put(groupId, new Group(groupId, name, group.parentId(), group.orgUnitId()));
        }

        @Override
        public void moveGroup(String organizationId, String groupId, String parentId) {
            Group group = groups.get(groupId);
            requireFree(organizationId, group.name(), parentId, groupId);
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
        public Map<String, Set<String>> members(java.util.Collection<String> groupIds) {
            Map<String, Set<String>> result = new HashMap<>();
            groupIds.forEach(id -> result.put(id, members(id)));
            return result;
        }

        private Set<String> members(String groupId) {
            return Set.copyOf(members.getOrDefault(groupId, Set.of()));
        }

        @Override
        public Set<String> organizationMembers(String organizationId, java.util.Collection<String> userIds) {
            Set<String> result = new HashSet<>(organizationMembers.getOrDefault(organizationId, Set.of()));
            result.retainAll(userIds);
            result.removeAll(outsideTable);
            return result;
        }

        @Override
        public boolean join(String groupId, String userId) {
            writesOfPiece.add(userId);
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

        // The realm group that the tree goes below, or null for none.
        private String realmGroup;
        private boolean realmGroupMissing;

        @Override
        public Optional<String> realmGroupRoot() {
            if (realmGroupMissing) {
                throw FuhitoException.conflict("projection.realm_group_unknown", Map.of("path", "/nowhere"));
            }
            return Optional.ofNullable(realmGroup);
        }

        @Override
        public List<Group> realmGroups(String rootGroupId) {
            return groups("realm:" + rootGroupId);
        }

        @Override
        public String createRealmGroup(String rootGroupId, String name, String parentId, String orgUnitId) {
            return createGroup("realm:" + rootGroupId, name, parentId, orgUnitId);
        }

        @Override
        public void moveRealmGroup(String rootGroupId, String groupId, String parentId) {
            moveGroup("realm:" + rootGroupId, groupId, parentId);
        }

        @Override
        public boolean addOrganizationMember(String organizationId, String userId) {
            writesOfPiece.add(userId);
            return organizationMembers.computeIfAbsent(organizationId, id -> new HashSet<>()).add(userId);
        }

        @Override
        public boolean removeOrganizationMember(String organizationId, String userId) {
            boolean removed = organizationMembers.getOrDefault(organizationId, new HashSet<>()).remove(userId);
            if (removed) {
                groups(organizationId).forEach(group -> members.getOrDefault(group.id(), new HashSet<>())
                        .remove(userId));
            }
            return removed;
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
    private final MemoryProjectedMemberStore projectedMembers = new MemoryProjectedMemberStore();
    private final MemoryOrgUnitStore orgUnitStore = new MemoryOrgUnitStore();
    private final MemoryAssignmentStore assignmentStore = new MemoryAssignmentStore();
    private final MemoryEmploymentStore employmentStore = new MemoryEmploymentStore();
    private final MemoryRefItemStore refItems = new MemoryRefItemStore();
    private final MemoryReportingLineStore lines = new MemoryReportingLineStore();
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIds, assignmentStore, refItems, CLOCK);
    private final EmploymentService employments =
            new EmploymentService(employmentStore, externalIds, assignmentStore, refItems, MemoryPersonStore.anyUser(), lines, new MemoryUnitRoleStore(), new AnyEmployer());
    private final AssignmentService assignments =
            new AssignmentService(assignmentStore, externalIds, employmentStore, orgUnitStore, refItems, lines);
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
        return new ProjectionService(orgUnitStore, assignmentStore, employmentStore, refItems, keycloak,
                projectedMembers, CLOCK).rebuild();
    }

    @Test
    void mirrorsTheUnitsBelowALinkedUnitAndTheAssignmentsOfToday() {
        ProjectionReport report = rebuild();

        assertEquals(new ProjectionReport(1, 2, 0, 0, 0, 2, 0, 3, 0, 0), report);
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]"), keycloak.describe("org-x"));
        assertEquals(Set.of("director", "head-nurse", "nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(nursing, keycloak.groups("org-x").getFirst().orgUnitId());
    }

    @Test
    void mirrorsEveryRootIntoTheRealmGroupToo() {
        keycloak.realmGroup = "org";
        String clinic = unit("Clinic", null);
        assign("doctor", clinic, "2026-04-01");

        ProjectionReport report = rebuild();

        assertEquals(1, report.realmGroupTrees());
        // The roots follow the order of siblings.
        assertEquals(List.of("Clinic [doctor]", "Hospital [director]", "Nursing < Hospital [head-nurse]",
                "Ward < Nursing [nurse]"), keycloak.describe("realm:org"));
        // The organization still gets its own groups, and the realm group adds no members to it.
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]"), keycloak.describe("org-x"));
        assertEquals(Set.of("director", "head-nurse", "nurse"), keycloak.organizationMembers.get("org-x"));

        // A unit that moves keeps its group. One that ends loses it.
        orgUnits.move(ward, clinic, "2026-06-01");
        String office = unit("Office", hospital);
        rebuild();
        orgUnits.end(office, "2026-06-10");
        rebuild();
        assertEquals(List.of("Clinic [doctor]", "Ward < Clinic [nurse]", "Hospital [director]",
                "Nursing < Hospital [head-nurse]"), keycloak.describe("realm:org"));
        // The ward is no longer below the hospital, so the nurse left its organization.
        assertEquals(Set.of("director", "head-nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(new ProjectionReport(1, 0, 0, 0, 0, 0, 0, 0, 0, 1), rebuild());
    }

    @Test
    void twoRootsWithOneNameCannotBeRealmGroups() {
        keycloak.realmGroup = "org";
        unit("Hospital", null);

        assertEquals("projection.duplicate_name",
                assertThrows(FuhitoException.class, this::rebuild).code());
    }

    @Test
    void aSecondRebuildWithNoChangeDoesNothing() {
        rebuild();

        assertEquals(new ProjectionReport(1, 0, 0, 0, 0, 0, 0, 0, 0, 0), rebuild());
    }

    @Test
    void followsARenameAMoveANewUnitAndAnEndedAssignment() {
        rebuild();
        orgUnits.changeDetails(nursing, new OrgUnitChange("2026-06-01", null, "Nursing Division", null, null, null, null,
                null, null, null));
        orgUnits.move(ward, hospital, "2026-06-01");
        unit("Pharmacy", hospital);
        assignments.end(nurseAssignment, "2026-06-01");
        assign("pharmacist", ward, "2026-05-01");

        ProjectionReport report = rebuild();

        assertEquals(new ProjectionReport(1, 1, 1, 1, 0, 1, 1, 1, 1, 0), report);
        assertEquals(List.of("Nursing Division [head-nurse]", "Ward [pharmacist]", "Pharmacy []"),
                keycloak.describe("org-x"));
        // The nurse has no assignment in the hospital any more, so the nurse left the organization too.
        assertEquals(Set.of("director", "head-nurse", "pharmacist"), keycloak.organizationMembers.get("org-x"));
        assertEquals(Set.of("director", "head-nurse", "pharmacist"), projectedMembers.users("org-x"));
    }

    @Test
    void aNewUnitTakesTheNameOfOneThatEnds() {
        String lab = unit("Lab", nursing);
        rebuild();
        String labGroup = groupOf(lab);
        orgUnits.end(lab, "2026-06-01");
        String next = orgUnits.create(new NewOrgUnit(null, "Lab", null, null, null, null, null, nursing,
                "2026-06-01", null, null, null, null)).unit().id();

        ProjectionReport report = rebuild();

        assertEquals(new ProjectionReport(1, 1, 0, 0, 1, 0, 0, 0, 0, 0), report);
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]", "Lab < Nursing []"),
                keycloak.describe("org-x"));
        assertTrue(keycloak.groups.containsKey(groupOf(next)));
        assertFalse(keycloak.groups.containsKey(labGroup));
    }

    @Test
    void twoUnitsSwapTheirNamesAndTheirParents() {
        keycloak.realmGroup = "org";
        String east = unit("East", hospital);
        String west = unit("West", hospital);
        String team = unit("Team", east);
        String other = unit("Team", west);
        rebuild();
        String eastGroup = groupOf(east);
        String teamGroup = groupOf(team);
        orgUnits.changeDetails(east, new OrgUnitChange("2026-06-01", null, "West", null, null, null, null, null,
                null, null));
        orgUnits.changeDetails(west, new OrgUnitChange("2026-06-01", null, "East", null, null, null, null, null,
                null, null));
        orgUnits.move(team, west, "2026-06-01");
        orgUnits.move(other, east, "2026-06-01");

        ProjectionReport report = rebuild();

        assertEquals(new ProjectionReport(1, 0, 4, 4, 0, 0, 0, 0, 0, 1), report);
        assertEquals(List.of("East []", "Nursing [head-nurse]", "Team < East []", "Team < West []",
                "Ward < Nursing [nurse]", "West []"), keycloak.describe("org-x").stream().sorted().toList());
        assertEquals(eastGroup, groupOf(east));
        assertEquals(teamGroup, groupOf(team));
        assertEquals(new ProjectionReport(1, 0, 0, 0, 0, 0, 0, 0, 0, 1), rebuild());
    }

    @Test
    void aUnitMovesBelowANewUnit() {
        rebuild();
        String annex = orgUnits.create(new NewOrgUnit(null, "Annex", null, null, null, null, null, hospital,
                "2026-06-01", null, null, null, null)).unit().id();
        orgUnits.move(ward, annex, "2026-06-01");

        assertEquals(new ProjectionReport(1, 1, 0, 1, 0, 0, 0, 0, 0, 0), rebuild());
        assertEquals(List.of("Nursing [head-nurse]", "Annex []", "Ward < Annex [nurse]"), keycloak.describe("org-x"));
    }

    private String groupOf(String orgUnitId) {
        return keycloak.groups("org-x").stream().filter(group -> orgUnitId.equals(group.orgUnitId())).findFirst()
                .orElseThrow().id();
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
        orgUnitStore.insertUnit(new OrgUnit("ghost", null, "org-ghost"));

        assertEquals(1, rebuild().organizations());
    }

    @Test
    void anOrganizationThatNoUnitLinksTodayLosesItsGroupsAndTheMembersThatTheProjectionAdded() {
        String clinic = unit("Clinic", null);
        String desk = unit("Desk", clinic);
        assign("receptionist", desk, "2026-04-01");
        links.link(clinic, "org-y");
        rebuild();
        String handMade = keycloak.createGroup("org-y", "Made by hand", null, null);
        assertEquals(List.of("Desk [receptionist]", "Made by hand []"), keycloak.describe("org-y"));
        keycloak.addOrganizationMember("org-y", "guest");

        // The clinic closes. The hospital is no longer linked.
        String nextDay = "2026-06-01";
        assignments.end(assignmentOf("receptionist"), nextDay);
        orgUnits.end(desk, nextDay);
        orgUnits.end(clinic, nextDay);
        links.unlink(hospital);
        ProjectionReport report = rebuild();

        assertEquals(0, report.organizations());
        assertEquals(3, report.groupsRemoved());
        assertEquals(List.of("Made by hand []"), keycloak.describe("org-y"));
        assertEquals(List.of(), keycloak.describe("org-x"));
        assertTrue(keycloak.groups.containsKey(handMade));
        assertEquals(4, report.organizationMembersRemoved());
        assertEquals(Set.of(), keycloak.organizationMembers.get("org-x"));
        // Someone else added the guest.
        assertEquals(Set.of("guest"), keycloak.organizationMembers.get("org-y"));
        assertEquals(List.of(), projectedMembers.organizations());
    }

    private String assignmentOf(String userId) {
        return assignmentStore.rows.stream()
                .filter(row -> employmentStore.find(row.employmentId()).orElseThrow().userId().equals(userId))
                .findFirst().orElseThrow().assignmentId();
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
    void aCallerMustBeAllowedToChangeEachPlaceBeforeAnythingIsWritten() {
        var service = new ProjectionService(orgUnitStore, assignmentStore, employmentStore, refItems, keycloak,
                projectedMembers, CLOCK);
        List<String> asked = new ArrayList<>();
        IllegalStateException denied = new IllegalStateException("denied");

        assertSame(denied, assertThrows(IllegalStateException.class, () -> service.rebuild(id -> {
            throw denied;
        }, () -> { })));
        assertEquals(0, keycloak.groups.size());

        // The clinic was linked to org-y before. Its groups go, so the caller must be allowed to change org-y too.
        String clinic = unit("Clinic", null);
        unit("Desk", clinic);
        links.link(clinic, "org-y");
        rebuild();
        links.unlink(clinic);
        keycloak.organizationMembers.put("org-z", new HashSet<>(Set.of("guest")));
        // A member that a rebuild added to org-w leaves it. Keycloak no longer has org-gone, so only its record goes.
        keycloak.organizationMembers.put("org-w", new HashSet<>(Set.of("visitor")));
        projectedMembers.record("org-w", "visitor");
        projectedMembers.record("org-gone", "visitor");
        keycloak.realmGroup = "org";

        service.rebuild(asked::add, () -> asked.add("realm groups"));

        assertEquals(List.of("org-w", "org-x", "org-y", "realm groups"), asked);
        assertEquals(List.of(), keycloak.describe("org-y"));
        assertEquals(Set.of(), keycloak.organizationMembers.get("org-w"));
        assertEquals(List.of("org-x"), projectedMembers.organizations());

        // A realm group that does not exist fails the run itself, after the check.
        keycloak.realmGroupMissing = true;
        asked.clear();
        assertEquals("projection.realm_group_unknown", assertThrows(FuhitoException.class,
                () -> service.rebuild(id -> { }, () -> asked.add("realm groups"))).code());
        assertEquals(List.of("realm groups"), asked);
    }

    @Test
    void doesNothingUnlessTheRealmTurnedItOn() {
        keycloak.enabled = false;

        FuhitoException e = assertThrows(FuhitoException.class, this::rebuild);

        assertEquals(FuhitoException.Reason.CONFLICT, e.reason());
        assertEquals("projection.disabled", e.code());
        assertEquals(0, keycloak.groups.size());
        var service = new ProjectionService(orgUnitStore, assignmentStore, employmentStore, refItems, keycloak,
                projectedMembers, CLOCK);
        assertEquals("projection.disabled", assertThrows(FuhitoException.class,
                () -> service.rebuild(id -> fail(id), () -> fail("realm groups"))).code());
    }

    @Test
    void writesTheMembershipsOfOneUserInOnePiece() {
        for (int i = 0; i < 30; i++) {
            assign("staff-" + i, ward, "2026-04-01");
        }

        rebuild();

        // Each new user joins the organization and the group of the ward, so 33 users make 66 writes.
        assertEquals(2, keycloak.writesOfPieces.size());
        assertTrue(keycloak.writesOfPieces.stream().allMatch(piece -> piece.size() <= 50), "pieces too large");
        Set<String> inFirst = new HashSet<>(keycloak.writesOfPieces.getFirst());
        assertTrue(keycloak.writesOfPieces.getLast().stream().noneMatch(inFirst::contains), "a user in two pieces");
    }

    @Test
    void everyPieceOfARebuildPutsOffItsWrites() {
        rebuild();

        // The check of the realm, the list of linked units, and then the pieces of each organization.
        assertTrue(keycloak.flushes > 2, "pieces: " + keycloak.flushes);
        assertFalse(keycloak.groups.isEmpty());
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

        new ProjectionService(orgUnitStore, assignmentStore, employmentStore, refItems, keycloak, projectedMembers,
                today).rebuild();

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

    @Test
    void aUserWhoMovesToAUnitOfAnotherOrganizationLeavesTheOldOne() {
        String clinic = unit("Clinic", null);
        links.link(clinic, "org-y");
        rebuild();
        assertEquals(Set.of("director", "head-nurse", "nurse"), projectedMembers.users("org-x"));

        assignments.end(nurseAssignment, "2026-06-01");
        assign("nurse", clinic, "2026-06-01");
        ProjectionReport report = rebuild();

        assertEquals(1, report.organizationMembersAdded());
        assertEquals(1, report.organizationMembersRemoved());
        assertEquals(Set.of("director", "head-nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(Set.of("nurse"), keycloak.organizationMembers.get("org-y"));
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing []"), keycloak.describe("org-x"));
        assertEquals(Set.of("director", "head-nurse"), projectedMembers.users("org-x"));
        assertEquals(Set.of("nurse"), projectedMembers.users("org-y"));
    }

    @Test
    void membersThatTheProjectionDidNotAddStay() {
        // Someone added the head nurse before the first rebuild, and a guest with no assignment.
        keycloak.organizationMembers.put("org-x", new HashSet<>(Set.of("head-nurse", "guest")));

        assertEquals(2, rebuild().organizationMembersAdded());
        assertEquals(Set.of("director", "nurse"), projectedMembers.users("org-x"));

        assignmentStore.rows.removeIf(row -> !row.orgUnitId().equals(hospital));
        ProjectionReport report = rebuild();

        assertEquals(1, report.organizationMembersRemoved());
        assertEquals(Set.of("director", "guest", "head-nurse"), keycloak.organizationMembers.get("org-x"));
    }

    @Test
    void aMemberThatSomeoneTookOutLosesItsRecordAndComesBackWithANewOne() {
        rebuild();
        Integer before = projectedMembers.records.get("org-x").get("nurse");
        keycloak.organizationMembers.get("org-x").removeAll(Set.of("nurse", "head-nurse"));
        assignmentStore.rows.removeIf(row -> row.orgUnitId().equals(nursing));

        ProjectionReport report = rebuild();

        assertEquals(1, report.organizationMembersAdded());
        assertEquals(0, report.organizationMembersRemoved());
        assertEquals(Set.of("director", "nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(Set.of("director", "nurse"), projectedMembers.users("org-x"));
        assertNotEquals(before, projectedMembers.records.get("org-x").get("nurse"));

        // The head nurse has no record any more, so a member that someone adds again stays.
        keycloak.addOrganizationMember("org-x", "head-nurse");
        rebuild();
        assertEquals(Set.of("director", "head-nurse", "nurse"), keycloak.organizationMembers.get("org-x"));
    }

    @Test
    void aUserThatOnlyAUserStorageProviderHoldsIsRecordedOnceAndLeavesLikeOthers() {
        keycloak.outsideTable.add("nurse");
        rebuild();
        Integer first = projectedMembers.records.get("org-x").get("nurse");

        // Keycloak tells that the nurse is a member already, so nothing is written.
        assertEquals(0, rebuild().organizationMembersAdded());
        assertEquals(first, projectedMembers.records.get("org-x").get("nurse"));

        assignments.end(nurseAssignment, "2026-06-01");
        assertEquals(1, rebuild().organizationMembersRemoved());
        assertEquals(Set.of("director", "head-nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(Set.of("director", "head-nurse"), projectedMembers.users("org-x"));
    }

    @Test
    void readsTheRecordedMembersPageByPage() {
        rebuild();
        for (int i = 0; i < 1001; i++) {
            keycloak.addOrganizationMember("org-x", "former-" + i);
            projectedMembers.record("org-x", "former-" + i);
        }

        assertEquals(1001, rebuild().organizationMembersRemoved());
        assertEquals(Set.of("director", "head-nurse", "nurse"), keycloak.organizationMembers.get("org-x"));
    }
}
