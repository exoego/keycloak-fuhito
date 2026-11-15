package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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

    /**
     * What one piece of a rebuild works with. Every piece gets its own, bound to its own transaction.
     *
     * @param orgUnits the storage of org units of the realm
     * @param assignments the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param refItems the storage of reference items of the realm
     * @param keycloak the groups and members of the Keycloak organizations of the realm
     * @param today what today is for the realm and for each org unit
     */
    public record Parts(OrgUnitStore orgUnits, AssignmentStore assignments, EmploymentStore employments,
            RefItemStore refItems, OrganizationGroups keycloak, Today today) {
    }

    /**
     * Runs the pieces of a rebuild.
     * A rebuild of a large realm writes too much for one transaction. Keycloak ends a transaction after some
     * minutes, and the work of each read grows with everything that the transaction has loaded.
     * A rebuild can run again after a failure, so its pieces need not succeed or fail together.
     */
    public interface Work {

        /**
         * Runs one piece in a transaction of its own.
         *
         * @param <T> the type of the result
         * @param piece the piece
         * @return the result of the piece
         */
        <T> T inNewTransaction(Function<Parts, T> piece);

        /**
         * Runs a whole run, unless another run for the realm is going on.
         *
         * @param <T> the type of the result
         * @param run the run
         * @return the result of the run
         * @throws FuhitoException when another run is going on
         */
        default <T> T alone(Supplier<T> run) {
            return run.get();
        }
    }

    private record Linked(OrgUnit unit, LocalDate date) {
    }

    private record Plan(List<OrgUnitState> below, List<Group> existing) {
    }

    private static final int UNITS_AT_A_TIME = 100;

    // Keycloak flushes after each change of a membership, and a flush looks through everything that the
    // transaction loaded. So a transaction with few writes keeps each flush small.
    private static final int MEMBER_WRITES_AT_A_TIME = 50;

    private final Work work;

    private int groupsCreated;
    private int groupsRenamed;
    private int groupsMoved;
    private int groupsRemoved;
    private int membersJoined;
    private int membersLeft;
    private int organizationMembersAdded;

    /**
     * Creates the service for one rebuild that runs in pieces.
     *
     * @param work runs each piece in a transaction of its own
     */
    public ProjectionService(Work work) {
        this.work = work;
    }

    /**
     * Creates the service for one rebuild that runs in the transaction of the caller.
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
     * The rebuild runs in the transaction of the caller.
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
        Parts parts = new Parts(orgUnits, assignments, employments, refItems, keycloak, today);
        this.work = new Work() {
            @Override
            public <T> T inNewTransaction(Function<Parts, T> piece) {
                return piece.apply(parts);
            }
        };
    }

    /**
     * Makes the groups and the memberships match the org structure of today.
     * Running it again with no change in between changes nothing.
     * The work is done in pieces. When one fails, the pieces before it stay, and the next run goes on.
     *
     * @return what the rebuild changed
     * @throws FuhitoException when the realm did not turn the projection on, two sibling units have one name,
     *         or another rebuild is going on
     */
    public ProjectionReport rebuild() {
        return work.alone(this::rebuildAlone);
    }

    private ProjectionReport rebuildAlone() {
        if (!piece(parts -> parts.keycloak().projectionEnabled())) {
            throw FuhitoException.conflict("projection.disabled", Map.of());
        }
        // Each linked unit is mirrored as it is on its own date.
        List<Linked> linked = piece(parts -> parts.orgUnits().units().stream()
                .filter(unit -> unit.keycloakOrganizationId() != null)
                .map(unit -> new Linked(unit, parts.today().ofUnit(unit.id())))
                .toList());
        int organizations = 0;
        for (Linked one : linked) {
            if (project(one.unit(), one.date())) {
                organizations++;
            }
        }
        return new ProjectionReport(organizations, groupsCreated, groupsRenamed, groupsMoved, groupsRemoved,
                membersJoined, membersLeft, organizationMembersAdded);
    }

    // Every piece puts off its writes to its end. See OrganizationGroups.inOneFlush.
    private <T> T piece(Function<Parts, T> piece) {
        return work.inNewTransaction(parts -> parts.keycloak().inOneFlush(() -> piece.apply(parts)));
    }

    private boolean project(OrgUnit root, LocalDate today) {
        String organizationId = root.keycloakOrganizationId();
        Plan plan = piece(parts -> {
            OrgUnitTree tree = new OrgUnitTree(parts.orgUnits(), parts.refItems(), today);
            // A linked unit that does not exist today has nothing to mirror.
            if (!tree.exists(root.id())) {
                return null;
            }
            List<OrgUnitState> subtree = tree.subtree(root.id());
            List<OrgUnitState> below = List.copyOf(subtree.subList(1, subtree.size()));
            requireUniqueNames(below);
            return new Plan(below, parts.keycloak().groups(organizationId));
        });
        if (plan == null) {
            return false;
        }

        Map<String, Group> mirrored = plan.existing().stream()
                .filter(group -> group.orgUnitId() != null)
                .collect(Collectors.toMap(Group::orgUnitId, group -> group, (first, second) -> first));
        // A parent comes before its children, so its group is known when a child needs it.
        Map<String, String> groupIdByUnit = new HashMap<>();
        for (List<OrgUnitState> some : inPieces(plan.below(), UNITS_AT_A_TIME)) {
            piece(parts -> {
                some.forEach(unit -> mirror(parts.keycloak(), organizationId, root.id(), unit, mirrored,
                        groupIdByUnit));
                return null;
            });
        }
        piece(parts -> {
            removeGroupsOfGoneUnits(parts.keycloak(), plan.existing(), groupIdByUnit);
            return null;
        });

        Set<String> everyone = new HashSet<>();
        // The linked unit itself has no group. Its people are members of the organization only.
        List<String> unitIds = new ArrayList<>(List.of(root.id()));
        plan.below().forEach(unit -> unitIds.add(unit.unit().id()));
        for (List<String> some : inPieces(unitIds, UNITS_AT_A_TIME)) {
            List<Consumer<OrganizationGroups>> writes = piece(parts -> memberWrites(parts, organizationId, some,
                    today, groupIdByUnit, everyone));
            for (List<Consumer<OrganizationGroups>> few : inPieces(writes, MEMBER_WRITES_AT_A_TIME)) {
                piece(parts -> {
                    few.forEach(write -> write.accept(parts.keycloak()));
                    return null;
                });
            }
        }
        return true;
    }

    private static <E> List<List<E>> inPieces(List<E> all, int size) {
        List<List<E>> pieces = new ArrayList<>();
        for (int start = 0; start < all.size(); start += size) {
            pieces.add(all.subList(start, Math.min(start + size, all.size())));
        }
        return pieces;
    }

    // Reads who is where for some units, in a few queries, and returns the writes that are missing.
    private List<Consumer<OrganizationGroups>> memberWrites(Parts parts, String organizationId, List<String> unitIds,
            LocalDate today, Map<String, String> groupIdByUnit, Set<String> everyone) {
        List<Consumer<OrganizationGroups>> writes = new ArrayList<>();
        Map<String, Set<String>> usersByUnit = usersByUnit(parts, unitIds, today);
        Set<String> newHere = new LinkedHashSet<>();
        usersByUnit.values().forEach(users -> users.stream().filter(everyone::add).forEach(newHere::add));
        Set<String> inOrganization = parts.keycloak().organizationMembers(organizationId, newHere);
        for (String userId : newHere) {
            if (!inOrganization.contains(userId)) {
                writes.add(keycloak -> {
                    if (keycloak.addOrganizationMember(organizationId, userId)) {
                        organizationMembersAdded++;
                    }
                });
            }
        }
        List<String> groupIds = unitIds.stream().map(groupIdByUnit::get).filter(Objects::nonNull).toList();
        Map<String, Set<String>> members = parts.keycloak().members(groupIds);
        for (String unitId : unitIds) {
            String groupId = groupIdByUnit.get(unitId);
            if (groupId != null) {
                memberWrites(groupId, members.getOrDefault(groupId, Set.of()),
                        usersByUnit.getOrDefault(unitId, Set.of()), writes);
            }
        }
        return writes;
    }

    private void mirror(OrganizationGroups keycloak, String organizationId, String rootId, OrgUnitState unit,
            Map<String, Group> mirrored, Map<String, String> groupIdByUnit) {
        String unitId = unit.unit().id();
        String parentGroupId = unit.parentId().equals(rootId) ? null : groupIdByUnit.get(unit.parentId());
        Group group = mirrored.get(unitId);
        if (group == null) {
            groupIdByUnit.put(unitId, keycloak.createGroup(organizationId, unit.version().name(), parentGroupId,
                    unitId));
            groupsCreated++;
            return;
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

    private void memberWrites(String groupId, Set<String> current, Set<String> wanted,
            List<Consumer<OrganizationGroups>> writes) {
        for (String userId : wanted) {
            if (!current.contains(userId)) {
                writes.add(keycloak -> {
                    if (keycloak.join(groupId, userId)) {
                        membersJoined++;
                    }
                });
            }
        }
        for (String userId : current) {
            if (!wanted.contains(userId)) {
                writes.add(keycloak -> {
                    if (keycloak.leave(groupId, userId)) {
                        membersLeft++;
                    }
                });
            }
        }
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
    private void removeGroupsOfGoneUnits(OrganizationGroups keycloak, List<Group> existing,
            Map<String, String> groupIdByUnit) {
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

    // The org units come in pieces. One query for all of them would name every employment of the organization,
    // and the database takes only so many values in one statement.
    private static Map<String, Set<String>> usersByUnit(Parts parts, List<String> unitIds, LocalDate today) {
        List<Assignment> rows = parts.assignments().rowsOn(unitIds, today);
        // A person with no Keycloak user cannot be a member of a group.
        Map<String, String> userByEmployment = parts.employments()
                .findAll(rows.stream().map(Assignment::employmentId).distinct().toList()).stream()
                .filter(employment -> employment.userId() != null)
                .collect(Collectors.toMap(Employment::id, Employment::userId));
        Map<String, Set<String>> result = new HashMap<>();
        for (Assignment row : rows) {
            Set<String> users = result.computeIfAbsent(row.orgUnitId(), id -> new LinkedHashSet<>());
            Optional.ofNullable(userByEmployment.get(row.employmentId())).ifPresent(users::add);
        }
        return result;
    }
}
