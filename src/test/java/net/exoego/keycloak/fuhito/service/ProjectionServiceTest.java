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
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.Employment;
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
        // The server runs with the organization feature off, or the realm turned organizations off.
        private boolean noOrganizations;
        // The number of links that each warning named.
        private final List<Integer> skippedLinks = new ArrayList<>();
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
        public boolean organizationsAvailable() {
            return !noOrganizations;
        }

        @Override
        public void warnLinksSkipped(int links) {
            skippedLinks.add(links);
        }

        @Override
        public List<String> organizations() {
            requireOrganizations("every organization");
            Set<String> result = new java.util.TreeSet<>(organizationMembers.keySet());
            organizationOfGroup.values().stream().filter(id -> !id.startsWith("realm:")).forEach(result::add);
            return List.copyOf(result);
        }

        @Override
        public List<Group> groups(String organizationId) {
            requireOrganizations(organizationId);
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

        private void requireOrganizations(String organizationId) {
            if (noOrganizations && !organizationId.startsWith("realm:")) {
                fail("The server has no organizations: " + organizationId);
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
        public List<OwnedGroup> ownedGroups() {
            List<OwnedGroup> result = new ArrayList<>();
            for (Group group : groups.values()) {
                String organizationId = organizationOfGroup.get(group.id());
                if (group.orgUnitId() == null) {
                    continue;
                }
                if (organizationId.startsWith("realm:")) {
                    // A realm group has its real parent, which is the root for a group right below it.
                    String parentId = group.parentId() == null ? organizationId.substring(6) : group.parentId();
                    result.add(new OwnedGroup(new Group(group.id(), group.name(), parentId, group.orgUnitId()), null));
                } else {
                    result.add(new OwnedGroup(group, organizationId));
                }
            }
            return result;
        }

        @Override
        public Map<String, Set<String>> groupsOf(java.util.Collection<String> userIds) {
            Map<String, Set<String>> result = new HashMap<>();
            members.forEach((groupId, users) -> users.stream().filter(userIds::contains)
                    .forEach(userId -> result.computeIfAbsent(userId, id -> new HashSet<>()).add(groupId)));
            return result;
        }

        @Override
        public String createGroup(String organizationId, String name, String parentId, String orgUnitId) {
            requireOrganizations(organizationId);
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
            requireOrganizations(organizationId);
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
            requireOrganizations(organizationId);
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
            requireOrganizations(organizationId);
            writesOfPiece.add(userId);
            return organizationMembers.computeIfAbsent(organizationId, id -> new HashSet<>()).add(userId);
        }

        @Override
        public boolean removeOrganizationMember(String organizationId, String userId) {
            requireOrganizations(organizationId);
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
    private final MemoryPersonStore people = MemoryPersonStore.anyUser();
    private final MemoryChangeLogStore changeLog = new MemoryChangeLogStore();
    private final MemoryJobRunStore runs = new MemoryJobRunStore();
    private final OrgUnitService orgUnits = new OrgUnitService(orgUnitStore, externalIds, assignmentStore, refItems, CLOCK);
    private final EmploymentService employments =
            new EmploymentService(employmentStore, externalIds, assignmentStore, refItems, people, lines, new MemoryUnitRoleStore(), new AnyEmployer());
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

    private ProjectionService service(Today today) {
        return new ProjectionService(new ProjectionService.Parts(orgUnitStore, assignmentStore, employmentStore,
                people, refItems, keycloak, projectedMembers, changeLog, runs, today));
    }

    private ProjectionService service() {
        return service(Today.of(CLOCK));
    }

    private ProjectionReport rebuild() {
        return service().rebuild();
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
    void aLinkedUnitIsSkippedWhileOrganizationsAreOffAndTheRealmGroupIsStillMirrored() {
        keycloak.realmGroup = "org";
        keycloak.noOrganizations = true;
        List<String> asked = new ArrayList<>();

        ProjectionReport report = service().rebuild(asked::add, () -> asked.add("realm groups"));

        assertEquals(List.of("realm groups"), asked);
        assertEquals(new ProjectionReport(0, 3, 0, 0, 0, 3, 0, 0, 0, 1), report);
        assertEquals(List.of("Hospital [director]", "Nursing < Hospital [head-nurse]", "Ward < Nursing [nurse]"),
                keycloak.describe("realm:org"));
        assertEquals(List.of(1), keycloak.skippedLinks);
    }

    @Test
    void whileOrganizationsAreOffTheRecordsStayForTheNextRebuild() {
        rebuild();
        projectedMembers.record("org-gone", "visitor");
        assignments.end(nurseAssignment, "2026-06-01");
        keycloak.noOrganizations = true;
        String records = projectedMembers.records.toString();

        assertEquals(new ProjectionReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), rebuild());
        assertEquals(records, projectedMembers.records.toString());

        keycloak.noOrganizations = false;
        assertEquals(1, rebuild().organizationMembersRemoved());
        assertEquals(Set.of("director", "head-nurse"), keycloak.organizationMembers.get("org-x"));
        assertEquals(List.of("org-x"), projectedMembers.organizations());
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
        var service = service();
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
        var service = service();
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

        service(today).rebuild();

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

    // An entry of the change log, as the API writes it with each write.
    private void logged(String resourceType, String resourceId, String operation, String path, String state) {
        changeLog.append(new ChangeLogEntry("entry-" + changeLog.entries.size(), 0, Instant.EPOCH, "admin",
                resourceType, resourceId, operation, path, null, state));
    }

    private void loggedUnit(String unitId) {
        logged("FUHITO_ORG_UNIT", unitId, "UPDATE", "fuhito/org-units/" + unitId, "{}");
    }

    private void loggedAssignment(String assignmentId, String operation) {
        String employmentId = assignmentStore.rows.stream().filter(row -> row.assignmentId().equals(assignmentId))
                .findFirst().map(Assignment::employmentId).orElse(null);
        logged("FUHITO_ASSIGNMENT", assignmentId, operation, "fuhito/assignments/" + assignmentId,
                operation.equals("DELETE") ? null : "{\"employmentId\": \"" + employmentId + "\"}");
    }

    private String employmentOf(String assignmentId) {
        return assignmentStore.rows.stream().filter(row -> row.assignmentId().equals(assignmentId)).findFirst()
                .orElseThrow().employmentId();
    }

    private ProjectionReport changes() {
        return service().rebuildChanges(id -> { }, () -> { });
    }

    // After a run of the changes, a full rebuild finds nothing more to do.
    private void assertSettled() {
        ProjectionReport full = rebuild();
        assertEquals(List.of(0, 0, 0, 0, 0, 0, 0, 0), List.of(full.groupsCreated(), full.groupsRenamed(),
                full.groupsMoved(), full.groupsRemoved(), full.membersJoined(), full.membersLeft(),
                full.organizationMembersAdded(), full.organizationMembersRemoved()), full.toString());
    }

    @Test
    void aRunOfTheChangesWithNoRunBeforeIsAFullRebuild() {
        ProjectionReport first = changes();

        assertEquals(true, first.fellBack());
        assertEquals("no_earlier_run", first.fallbackReason());
        assertEquals(2, first.groupsCreated());
        assertEquals(null, first.usersLookedAt());

        assertEquals(new ProjectionReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, null), changes());
        // A full rebuild is no run of the changes.
        assertEquals(null, rebuild().fellBack());
    }

    @Test
    void aMoveInsideTheOrganizationChangesTheGroupsOfOneUser() {
        rebuild();
        AssignmentService.Move move = assignments.move(nurseAssignment,
                new MoveRequest(nursing, "2026-06-01", null, null, null));
        loggedAssignment(nurseAssignment, "UPDATE");
        loggedAssignment(move.started().assignmentId(), "CREATE");
        // Writes that the projection does not read.
        logged("FUHITO_REF_ITEM", "item", "CREATE", "fuhito/ref-items/item", "{}");
        logged("FUHITO_PERSON_DATA", "nurse", "UPDATE", "fuhito/people/nurse/mask", "{}");

        ProjectionReport report = changes();

        assertEquals(new ProjectionReport(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 4, 1, 1, false, null), report);
        assertEquals(List.of("Nursing [head-nurse, nurse]", "Ward < Nursing []"), keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void aMoveToAnotherOrganizationMovesTheMembershipToo() {
        String clinic = unit("Clinic", null);
        String desk = unit("Desk", clinic);
        links.link(clinic, "org-y");
        rebuild();
        AssignmentService.Move move = assignments.move(nurseAssignment,
                new MoveRequest(desk, "2026-06-01", null, null, null));
        loggedAssignment(nurseAssignment, "UPDATE");
        loggedAssignment(move.started().assignmentId(), "CREATE");
        List<String> asked = new ArrayList<>();

        ProjectionReport report = service().rebuildChanges(asked::add, () -> asked.add("realm groups"));

        assertEquals(List.of("org-x", "org-y"), asked);
        assertEquals(new ProjectionReport(0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 2, 1, 2, false, null), report);
        assertEquals(Set.of("nurse"), keycloak.organizationMembers.get("org-y"));
        assertEquals(Set.of("director", "head-nurse"), projectedMembers.users("org-x"));
        assertSettled();
    }

    @Test
    void aNewPersonWithAUserJoinsAndAnEndedEmploymentLeaves() {
        rebuild();
        String pharmacist = assign("pharmacist", ward, "2026-05-01");
        String employment = employmentOf(pharmacist);
        logged("FUHITO_PERSON", "pharmacist", "CREATE", "fuhito/people/pharmacist", "{\"userId\": \"pharmacist\"}");
        logged("FUHITO_EMPLOYMENT", employment, "CREATE", "fuhito/employments/" + employment, "{}");
        loggedAssignment(pharmacist, "CREATE");
        String nurseEmployment = employmentOf(nurseAssignment);
        employments.end(nurseEmployment, "2026-06-01", null);
        logged("FUHITO_EMPLOYMENT", nurseEmployment, "UPDATE", "fuhito/employments/" + nurseEmployment + "/end",
                "{}");

        ProjectionReport report = changes();

        assertEquals(2, report.usersLookedAt());
        assertEquals(List.of(1, 1, 1, 1), List.of(report.membersJoined(), report.membersLeft(),
                report.organizationMembersAdded(), report.organizationMembersRemoved()));
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [pharmacist]"), keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void aRenamedUnitAndAMovedSubtreeChangeOnlyTheirGroups() {
        keycloak.realmGroup = "org";
        String annex = unit("Annex", hospital);
        rebuild();
        orgUnits.changeDetails(ward, new OrgUnitChange("2026-06-01", null, "East Ward", null, null, null, null, null,
                null, null));
        orgUnits.move(nursing, annex, "2026-06-01");
        loggedUnit(ward);
        loggedUnit(nursing);
        List<String> asked = new ArrayList<>();

        ProjectionReport report = service().rebuildChanges(asked::add, () -> asked.add("realm groups"));

        assertEquals(List.of("org-x", "realm groups"), asked);
        // The rename and the move each happen in the organization and in the realm groups.
        assertEquals(new ProjectionReport(1, 0, 2, 2, 0, 0, 0, 0, 0, 1, 2, 0, 1, false, null), report);
        assertEquals(List.of("Annex []", "Nursing < Annex [head-nurse]", "East Ward < Nursing [nurse]"),
                keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void aUnitThatMovesToAnotherOrganizationTakesItsPeopleAlong() {
        keycloak.realmGroup = "org";
        String clinic = unit("Clinic", null);
        links.link(clinic, "org-y");
        rebuild();
        orgUnits.move(nursing, clinic, "2026-06-01");
        loggedUnit(nursing);

        ProjectionReport report = changes();

        assertEquals(2, report.organizations());
        assertEquals(List.of(2, 2, 1, 2, 2, 2), List.of(report.groupsCreated(), report.groupsRemoved(),
                report.groupsMoved(), report.usersLookedAt(), report.organizationMembersAdded(),
                report.organizationMembersRemoved()));
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse]"), keycloak.describe("org-y"));
        assertEquals(List.of(), keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void anEndedUnitLosesItsGroup() {
        keycloak.realmGroup = "org";
        rebuild();
        assignments.end(nurseAssignment, "2026-06-01");
        assignmentStore.rows.removeIf(row -> row.orgUnitId().equals(ward) && row.period().from().getYear() == 2027);
        orgUnits.end(ward, "2026-06-01");
        loggedAssignment(nurseAssignment, "UPDATE");
        loggedUnit(ward);

        ProjectionReport report = changes();

        assertEquals(2, report.groupsRemoved());
        assertEquals(1, report.organizationMembersRemoved());
        assertEquals(List.of("Nursing [head-nurse]"), keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void aNewLinkMirrorsTheWholeOrganizationAndARemovedLinkTakesItAway() {
        String clinic = unit("Clinic", null);
        String desk = unit("Desk", clinic);
        assign("receptionist", desk, "2026-04-01");
        rebuild();
        links.link(clinic, "org-y");
        links.unlink(hospital);
        logged("FUHITO_ORG_UNIT", clinic, "UPDATE", "fuhito/org-units/" + clinic + "/organization", "{}");
        logged("FUHITO_ORG_UNIT", hospital, "UPDATE", "fuhito/org-units/" + hospital + "/organization", "{}");
        List<String> asked = new ArrayList<>();

        ProjectionReport report = service().rebuildChanges(asked::add, () -> asked.add("realm groups"));

        assertEquals(List.of("org-x", "org-y"), asked);
        assertEquals(List.of("Desk [receptionist]"), keycloak.describe("org-y"));
        assertEquals(List.of(), keycloak.describe("org-x"));
        assertEquals(List.of("org-y"), projectedMembers.organizations());
        assertEquals(1, report.organizations());
        assertSettled();
    }

    @Test
    void aNewTimeSettingMirrorsTheOrganizationsBelowItAgain() {
        keycloak.realmGroup = "org";
        String group = unit("Group", null);
        String clinic = unit("Clinic", group);
        links.link(clinic, "org-y");
        rebuild();
        orgUnits.setTime(group, "Asia/Tokyo", null);
        logged("FUHITO_ORG_UNIT", group, "UPDATE", "fuhito/org-units/" + group + "/time-settings", "{}");
        logged("FUHITO_ORG_UNIT", nursing, "UPDATE", "fuhito/org-units/" + nursing + "/time-settings", "{}");

        ProjectionReport report = changes();

        // The clinic takes the date of the group. A setting below the hospital changes only the groups below it.
        assertEquals(2, report.organizations());
        assertEquals(false, report.fellBack());
        assertSettled();
    }

    @Test
    void theRealmGroupsOfRootsOnTwoDatesAreMirroredWhole() {
        keycloak.realmGroup = "org";
        String clinic = unit("Clinic", null);
        // 23:00 on June 14 in New York, where the realm is. The clinic is in Tokyo, where it is already June 15.
        orgUnits.setTime(clinic, "Asia/Tokyo", null);
        Today today = new UnitDays(orgUnitStore, BusinessDay.rule("America/New_York", null), CLOCK);
        service(today).rebuild();
        String lab = orgUnits.create(new NewOrgUnit(null, "Lab", null, null, null, null, null, clinic, "2026-06-15",
                null, null, null, null)).unit().id();
        assign("technician", lab, "2026-06-15");
        loggedUnit(lab);
        logged("FUHITO_PERSON", "technician", "CREATE", "fuhito/people/technician", "{\"userId\": \"technician\"}");

        ProjectionReport report = service(today).rebuildChanges(id -> { }, () -> { });

        assertEquals(List.of(1, 1), List.of(report.groupsCreated(), report.membersJoined()));
        assertTrue(keycloak.describe("realm:org").contains("Lab < Clinic [technician]"),
                keycloak.describe("realm:org").toString());
        assertEquals(new ProjectionReport(1, 0, 0, 0, 0, 0, 0, 0, 0, 1), service(today).rebuild());
    }

    @Test
    void aGroupThatSomeoneRemovedByHandComesBackWithTheWholeOrganization() {
        rebuild();
        keycloak.removeGroup(groupOf(nursing));
        orgUnits.changeDetails(ward, new OrgUnitChange("2026-06-01", null, "East Ward", null, null, null, null, null,
                null, null));
        loggedUnit(ward);

        ProjectionReport report = changes();

        assertEquals(2, report.groupsCreated());
        assertEquals(List.of("Nursing [head-nurse]", "East Ward < Nursing [nurse]"), keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void aNewUnitWithTheNameOfASiblingFailsTheRun() {
        rebuild();
        loggedUnit(unit("Ward", nursing));

        assertEquals("projection.duplicate_name", assertThrows(FuhitoException.class, this::changes).code());
    }

    @Test
    void aPersonLinkedToAnotherUserMovesTheMembershipsToThatUser() {
        logged("FUHITO_PERSON", "nurse", "CREATE", "fuhito/people/nurse", "{\"userId\": \"nurse\"}");
        rebuild();
        Employment linked = employmentStore.find(employmentOf(nurseAssignment)).orElseThrow();
        employmentStore.update(new Employment(linked.id(), linked.personId(), "nurse-2", linked.employerId(),
                linked.period(), linked.staffNumber(), linked.endReasonId(), linked.source(),
                linked.continuesEmploymentId(), linked.version()));
        logged("FUHITO_PERSON", "nurse", "UPDATE", "fuhito/people/nurse", "{\"userId\": \"nurse-2\"}");

        ProjectionReport report = changes();

        assertEquals(2, report.usersLookedAt());
        assertEquals(List.of("Nursing [head-nurse]", "Ward < Nursing [nurse-2]"), keycloak.describe("org-x"));
        assertSettled();
    }

    @Test
    void aRemovedAssignmentOrEmploymentIsFoundThroughTheChangeLog() {
        String pharmacist = assign("pharmacist", ward, "2026-05-01");
        String employment = employmentOf(pharmacist);
        loggedAssignment(pharmacist, "CREATE");
        logged("FUHITO_EMPLOYMENT", employment, "CREATE", "fuhito/employments/" + employment,
                "{\"personId\": \"pharmacist\"}");
        rebuild();
        assignmentStore.rows.removeIf(row -> row.assignmentId().equals(pharmacist));
        employmentStore.delete(employment);
        loggedAssignment(pharmacist, "DELETE");
        logged("FUHITO_EMPLOYMENT", employment, "DELETE", "fuhito/employments/" + employment, null);

        ProjectionReport report = changes();

        assertEquals(1, report.membersLeft());
        assertEquals(1, report.organizationMembersRemoved());
        assertSettled();
    }

    @Test
    void aRunOfTheChangesFallsBackWhenTheChangeLogDoesNotTellEnough() {
        rebuild();
        // The person had no entry before, so the user that it was linked to is not known.
        logged("FUHITO_PERSON", "nurse", "UPDATE", "fuhito/people/nurse", "{\"userId\": \"nurse\"}");
        assertEquals("earlier_state", changes().fallbackReason());
        logged("FUHITO_ASSIGNMENT", "gone", "DELETE", "fuhito/assignments/gone", null);
        assertEquals("earlier_state", changes().fallbackReason());
        logged("FUHITO_ASSIGNMENT", "known", "CREATE", "fuhito/assignments/known", "{\"employmentId\": \"gone\"}");
        logged("FUHITO_ASSIGNMENT", "known", "DELETE", "fuhito/assignments/known", null);
        assertEquals("earlier_state", changes().fallbackReason());

        for (List<String> entry : List.of(List.of("FUHITO_RESTORE", "UPDATE", "restore"),
                List.of("FUHITO_HIERARCHY", "UPDATE", "hierarchy"),
                List.of("FUHITO_PERSON_DATA", "DELETE", "erasure"),
                List.of("FUHITO_SOMETHING_NEW", "CREATE", "unknown_entry"))) {
            logged(entry.get(0), "x", entry.get(1), "fuhito/x", null);
            loggedUnit(ward);
            ProjectionReport report = changes();
            assertEquals(entry.get(2), report.fallbackReason());
            // The entries after the one that the run cannot map are not read.
            assertEquals(1, report.changeLogEntries());
        }
    }

    @Test
    void aRunOfTheChangesFallsBackWhenASettingOutsideTheChangeLogChanged() {
        rebuild();
        keycloak.realmGroup = "org";
        List<String> asked = new ArrayList<>();

        ProjectionReport report = service().rebuildChanges(asked::add, () -> asked.add("realm groups"));

        assertEquals("settings", report.fallbackReason());
        assertEquals(List.of("org-x", "realm groups"), asked);
        assertEquals(1, report.realmGroupTrees());

        keycloak.realmGroupMissing = true;
        assertEquals("projection.realm_group_unknown",
                assertThrows(FuhitoException.class, this::changes).code());
    }

    @Test
    void aRunOfTheChangesReadsTheChangeLogPageByPage() {
        rebuild();
        for (int i = 0; i < 1001; i++) {
            logged("FUHITO_QUALIFICATION", "q-" + i, "CREATE", "fuhito/qualifications/q-" + i, "{}");
        }
        loggedUnit(ward);

        ProjectionReport report = changes();

        assertEquals(1002, report.changeLogEntries());
        assertEquals(false, report.fellBack());
        assertEquals(0, changes().changeLogEntries());
    }

    @Test
    void aRunOfTheChangesAsksOnlyForWhatItMayChange() {
        String clinic = unit("Clinic", null);
        links.link(clinic, "org-y");
        rebuild();
        List<String> asked = new ArrayList<>();
        service().rebuildChanges(asked::add, () -> asked.add("realm groups"));
        assertEquals(List.of(), asked);

        // The nurse sits in a group of org-y by hand and has a record in an organization that is gone.
        keycloak.join(keycloak.createGroup("org-y", "By hand", null, clinic), "nurse");
        projectedMembers.record("org-gone", "nurse");
        loggedAssignment(nurseAssignment, "UPDATE");
        service().rebuildChanges(asked::add, () -> asked.add("realm groups"));

        assertEquals(List.of("org-x", "org-y"), asked);
        // A full rebuild removes the record of the organization that is gone.
        assertEquals(List.of("org-gone", "org-x"), projectedMembers.organizations());
    }

    @Test
    void aRunOfTheChangesWhileOrganizationsAreOffChangesTheRealmGroupsOnly() {
        keycloak.realmGroup = "org";
        keycloak.noOrganizations = true;
        rebuild();
        projectedMembers.record("org-x", "nurse");
        AssignmentService.Move move = assignments.move(nurseAssignment,
                new MoveRequest(nursing, "2026-06-01", null, null, null));
        loggedAssignment(nurseAssignment, "UPDATE");
        loggedAssignment(move.started().assignmentId(), "CREATE");
        loggedUnit(ward);

        ProjectionReport report = changes();

        assertEquals(List.of(1, 1, 0), List.of(report.membersJoined(), report.membersLeft(),
                report.organizationMembersRemoved()));
        assertEquals(List.of("org-x"), projectedMembers.organizations());
        assertSettled();
    }

    @Test
    void aRunOfTheChangesFailsLikeAFullRebuildWhenTheRealmTurnedItOff() {
        rebuild();
        keycloak.enabled = false;

        assertEquals("projection.disabled", assertThrows(FuhitoException.class, this::changes).code());
        // The realm turns it off while the run waits for its turn.
        keycloak.enabled = true;
        loggedUnit(ward);
        ProjectionService service = new ProjectionService(new ProjectionService.Work() {
            @Override
            public <T> T inNewTransaction(Function<ProjectionService.Parts, T> piece) {
                return piece.apply(new ProjectionService.Parts(orgUnitStore, assignmentStore, employmentStore,
                        people, refItems, keycloak, projectedMembers, changeLog, runs, Today.of(CLOCK)));
            }

            @Override
            public <T> T alone(Supplier<T> run) {
                keycloak.enabled = false;
                return run.get();
            }
        });
        assertEquals("projection.disabled", assertThrows(FuhitoException.class,
                () -> service.rebuildChanges(id -> { }, () -> { })).code());
    }
}
