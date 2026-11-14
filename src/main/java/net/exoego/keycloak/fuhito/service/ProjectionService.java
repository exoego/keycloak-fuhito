package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.OrganizationGroups;
import net.exoego.keycloak.fuhito.store.OrganizationGroups.Group;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Mirrors the org structure of today into the groups of Keycloak organizations.
 * The projection goes one way. The tables of this extension stay the source of truth.
 * For each org unit that is linked to a Keycloak organization, the units below it become groups of that
 * organization, and the assignments that hold today become group memberships.
 */
public class ProjectionService {

    private final OrgUnitStore orgUnits;
    private final AssignmentStore assignments;
    private final EmploymentStore employments;
    private final RefItemStore refItems;
    private final OrganizationGroups keycloak;
    private final Today today;

    private int groupsCreated;
    private int groupsRenamed;
    private int groupsMoved;
    private int groupsRemoved;
    private int membersJoined;
    private int membersLeft;
    private int organizationMembersAdded;

    /**
     * Creates the service for one rebuild.
     *
     * @param orgUnits the storage of org units of the realm
     * @param assignments the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param refItems the storage of reference items of the realm
     * @param keycloak the groups and members of the Keycloak organizations of the realm
     * @param clock the clock that decides what "today" is
     */
    public ProjectionService(OrgUnitStore orgUnits, AssignmentStore assignments, EmploymentStore employments,
            RefItemStore refItems, OrganizationGroups keycloak, Clock clock) {
        this(orgUnits, assignments, employments, refItems, keycloak, Today.of(clock));
    }

    /**
     * Creates the service for a realm where an org unit can have its own time settings.
     *
     * @param orgUnits the storage of org units of the realm
     * @param assignments the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param refItems the storage of reference items of the realm
     * @param keycloak the groups and members of the Keycloak organizations of the realm
     * @param today what today is for the realm and for each org unit
     */
    public ProjectionService(OrgUnitStore orgUnits, AssignmentStore assignments, EmploymentStore employments,
            RefItemStore refItems, OrganizationGroups keycloak, Today today) {
        this.orgUnits = orgUnits;
        this.assignments = assignments;
        this.employments = employments;
        this.refItems = refItems;
        this.keycloak = keycloak;
        this.today = today;
    }

    /**
     * Makes the groups and the memberships match the org structure of today.
     * Running it again with no change in between changes nothing.
     *
     * @return what the rebuild changed
     * @throws FuhitoException when the realm did not turn the projection on, or two sibling units have one name
     */
    public ProjectionReport rebuild() {
        if (!keycloak.projectionEnabled()) {
            throw FuhitoException.conflict("projection.disabled", Map.of());
        }
        // Each linked unit is mirrored as it is on its own date.
        Map<LocalDate, OrgUnitTree> trees = new HashMap<>();
        int organizations = 0;
        for (OrgUnit unit : orgUnits.units()) {
            if (unit.keycloakOrganizationId() == null) {
                continue;
            }
            LocalDate date = today.ofUnit(unit.id());
            OrgUnitTree tree = trees.computeIfAbsent(date, d -> new OrgUnitTree(orgUnits, refItems, d));
            // A linked unit that does not exist today has nothing to mirror.
            if (tree.exists(unit.id())) {
                project(unit, tree, date);
                organizations++;
            }
        }
        return new ProjectionReport(organizations, groupsCreated, groupsRenamed, groupsMoved, groupsRemoved,
                membersJoined, membersLeft, organizationMembersAdded);
    }

    private void project(OrgUnit root, OrgUnitTree tree, LocalDate today) {
        String organizationId = root.keycloakOrganizationId();
        List<OrgUnitState> subtree = tree.subtree(root.id());
        List<OrgUnitState> below = subtree.subList(1, subtree.size());
        List<Group> existing = keycloak.groups(organizationId);
        requireUniqueNames(below);

        Map<String, Group> mirrored = existing.stream()
                .filter(group -> group.orgUnitId() != null)
                .collect(Collectors.toMap(Group::orgUnitId, group -> group, (first, second) -> first));
        Map<String, String> groupIdByUnit = new HashMap<>();
        for (OrgUnitState unit : below) {
            String unitId = unit.unit().id();
            String parentGroupId = unit.parentId().equals(root.id()) ? null : groupIdByUnit.get(unit.parentId());
            Group group = mirrored.get(unitId);
            if (group == null) {
                groupIdByUnit.put(unitId, keycloak.createGroup(organizationId, unit.version().name(), parentGroupId,
                        unitId));
                groupsCreated++;
                continue;
            }
            groupIdByUnit.put(unitId, group.id());
            if (!Objects.equals(group.parentId(), parentGroupId)) {
                keycloak.moveGroup(organizationId, group.id(), parentGroupId);
                groupsMoved++;
            }
            if (!group.name().equals(unit.version().name())) {
                keycloak.renameGroup(group.id(), unit.version().name());
                groupsRenamed++;
            }
        }
        removeGroupsOfGoneUnits(existing, groupIdByUnit);

        Map<String, Set<String>> usersByUnit = usersByUnit(subtree, today);
        Set<String> everyone = usersByUnit.values().stream().flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String userId : everyone) {
            if (keycloak.addOrganizationMember(organizationId, userId)) {
                organizationMembersAdded++;
            }
        }
        groupIdByUnit.forEach((unitId, groupId) -> {
            Set<String> wanted = usersByUnit.getOrDefault(unitId, Set.of());
            Set<String> current = keycloak.members(groupId);
            for (String userId : wanted) {
                if (!current.contains(userId) && keycloak.join(groupId, userId)) {
                    membersJoined++;
                }
            }
            for (String userId : current) {
                if (!wanted.contains(userId) && keycloak.leave(groupId, userId)) {
                    membersLeft++;
                }
            }
        });
    }

    // Keycloak allows one name among the groups of one parent.
    private static void requireUniqueNames(List<OrgUnitState> below) {
        Set<String> seen = new HashSet<>();
        for (OrgUnitState unit : below) {
            if (!seen.add(unit.parentId() + "/" + unit.version().name())) {
                throw FuhitoException.conflict("projection.duplicate_name",
                        Map.of("orgUnitId", unit.unit().id(), "name", unit.version().name()));
            }
        }
    }

    // A group whose unit ended, or moved out of the organization, goes away. Removing a group removes the
    // groups below it, so a group below a removed one is not removed a second time.
    private void removeGroupsOfGoneUnits(List<Group> existing, Map<String, String> groupIdByUnit) {
        Set<String> kept = new HashSet<>(groupIdByUnit.values());
        Set<String> removed = new HashSet<>();
        for (Group group : existing) {
            if (group.orgUnitId() == null || kept.contains(group.id())) {
                continue;
            }
            if (group.parentId() == null || !removed.contains(group.parentId())) {
                keycloak.removeGroup(group.id());
            }
            removed.add(group.id());
            groupsRemoved++;
        }
    }

    private Map<String, Set<String>> usersByUnit(List<OrgUnitState> subtree, LocalDate today) {
        List<String> unitIds = subtree.stream().map(unit -> unit.unit().id()).toList();
        List<Assignment> rows = assignments.rowsOn(unitIds, today);
        Map<String, String> userByEmployment = employments
                .findAll(rows.stream().map(Assignment::employmentId).distinct().toList()).stream()
                .collect(Collectors.toMap(Employment::id, Employment::userId));
        Map<String, Set<String>> result = new HashMap<>();
        for (Assignment row : rows) {
            result.computeIfAbsent(row.orgUnitId(), id -> new LinkedHashSet<>())
                    .add(userByEmployment.get(row.employmentId()));
        }
        return result;
    }
}
