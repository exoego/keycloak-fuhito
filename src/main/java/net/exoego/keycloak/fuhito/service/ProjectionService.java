package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.exoego.keycloak.fuhito.store.ProjectedMemberStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Mirrors the org structure of today into the groups of Keycloak organizations, and into realm groups.
 * The projection goes one way. The tables of this extension stay the source of truth.
 * For each org unit that is linked to a Keycloak organization, the units below it become groups of that
 * organization, and the assignments that hold today become group memberships.
 * When the realm names a realm group, every root and the units below it become groups below that group.
 * A user that the projection added to an organization leaves it again when no assignment of today puts the user
 * there. Members that someone else added stay.
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
     * @param projectedMembers the users that the projection added to the organizations of the realm
     * @param today what today is for the realm and for each org unit
     */
    public record Parts(OrgUnitStore orgUnits, AssignmentStore assignments, EmploymentStore employments,
            RefItemStore refItems, OrganizationGroups keycloak, ProjectedMemberStore projectedMembers, Today today) {
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
         * Runs pieces that do not depend on each other, each in a transaction of its own. They may run at the same
         * time. All of them run, also when one fails.
         *
         * @param pieces the pieces
         * @throws RuntimeException the failure of the first piece that failed
         */
        default void inParallel(List<Consumer<Parts>> pieces) {
            pieces.forEach(piece -> inNewTransaction(parts -> {
                piece.accept(parts);
                return null;
            }));
        }

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

    // Where groups go. An organization has members of its own. A realm group has groups below it only.
    private record Target(String id, boolean organization) {

        List<Group> groups(OrganizationGroups keycloak) {
            return organization ? keycloak.groups(id) : keycloak.realmGroups(id);
        }

        String create(OrganizationGroups keycloak, String name, String parentId, String orgUnitId) {
            return organization ? keycloak.createGroup(id, name, parentId, orgUnitId)
                    : keycloak.createRealmGroup(id, name, parentId, orgUnitId);
        }

        void move(OrganizationGroups keycloak, String groupId, String parentId) {
            if (organization) {
                keycloak.moveGroup(id, groupId, parentId);
            } else {
                keycloak.moveRealmGroup(id, groupId, parentId);
            }
        }
    }

    private static final int UNITS_AT_A_TIME = 100;

    // Keycloak flushes after each change of a membership, and a flush looks through everything that the
    // transaction loaded. So a transaction with few writes keeps each flush small.
    private static final int MEMBER_WRITES_AT_A_TIME = 50;

    private static final int RECORDS_AT_A_TIME = 1000;

    private final Work work;

    private int groupsCreated;
    private int groupsRenamed;
    private int groupsMoved;
    private int groupsRemoved;
    // The writes of members run at the same time, so their counts are shared.
    private final AtomicInteger membersJoined = new AtomicInteger();
    private final AtomicInteger membersLeft = new AtomicInteger();
    private final AtomicInteger organizationMembersAdded = new AtomicInteger();
    private final AtomicInteger organizationMembersRemoved = new AtomicInteger();
    // The groups that took their own ID as their name for a while.
    private final Set<String> parked = new HashSet<>();

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
     * @param projectedMembers the users that the projection added to the organizations of the realm
     * @param clock the clock that decides what "today" is
     */
    public ProjectionService(OrgUnitStore orgUnits, AssignmentStore assignments, EmploymentStore employments,
            RefItemStore refItems, OrganizationGroups keycloak, ProjectedMemberStore projectedMembers, Clock clock) {
        this(orgUnits, assignments, employments, refItems, keycloak, projectedMembers, Today.of(clock));
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
     * @param projectedMembers the users that the projection added to the organizations of the realm
     * @param today what today is for the realm and for each org unit
     */
    public ProjectionService(OrgUnitStore orgUnits, AssignmentStore assignments, EmploymentStore employments,
            RefItemStore refItems, OrganizationGroups keycloak, ProjectedMemberStore projectedMembers, Today today) {
        Parts parts = new Parts(orgUnits, assignments, employments, refItems, keycloak, projectedMembers, today);
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

    /**
     * Makes the groups and the memberships match the org structure of today, for a caller who must be allowed to
     * change each place the rebuild may write to. The checks run before anything is written.
     *
     * @param mayChangeOrganization throws when the caller may not change the organization with the given ID
     * @param mayChangeRealmGroups throws when the caller may not change the realm groups
     * @return what the rebuild changed
     * @throws FuhitoException when the realm did not turn the projection on, two sibling units have one name,
     *         or another rebuild is going on
     */
    public ProjectionReport rebuild(Consumer<String> mayChangeOrganization, Runnable mayChangeRealmGroups) {
        // A rebuild that fails for another reason fails in its run, which keeps the failure.
        if (piece(parts -> parts.keycloak().projectionEnabled())) {
            organizationsToChange().forEach(mayChangeOrganization);
            if (namesRealmGroup()) {
                mayChangeRealmGroups.run();
            }
        }
        return rebuild();
    }

    private boolean namesRealmGroup() {
        try {
            return piece(parts -> parts.keycloak().realmGroupRoot()).isPresent();
        } catch (FuhitoException e) {
            return true;
        }
    }

    // The linked organizations, and those that keep groups or members of an earlier rebuild, which this one
    // removes.
    private Set<String> organizationsToChange() {
        Set<String> result = piece(parts -> parts.orgUnits().units().stream()
                .map(OrgUnit::keycloakOrganizationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new)));
        Set<String> recorded = Set.copyOf(piece(parts -> parts.projectedMembers().organizations()));
        for (String organizationId : piece(parts -> parts.keycloak().organizations())) {
            if (!result.contains(organizationId) && (recorded.contains(organizationId)
                    || piece(parts -> parts.keycloak().groups(organizationId)).stream()
                            .anyMatch(group -> group.orgUnitId() != null))) {
                result.add(organizationId);
            }
        }
        return result;
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
        Set<String> mirrored = new HashSet<>();
        for (Linked one : linked) {
            if (project(one.unit(), one.date())) {
                organizations++;
                mirrored.add(one.unit().keycloakOrganizationId());
            }
        }
        // An organization that no unit of today links keeps none of the groups and members that a rebuild added.
        List<String> existing = piece(parts -> parts.keycloak().organizations());
        for (String organizationId : existing) {
            if (!mirrored.contains(organizationId)) {
                List<Group> owned = piece(parts -> parts.keycloak().groups(organizationId)).stream()
                        .filter(group -> group.orgUnitId() != null)
                        .toList();
                if (!owned.isEmpty()) {
                    removeGone(owned, Map.of());
                }
            }
        }
        for (String organizationId : piece(parts -> parts.projectedMembers().organizations())) {
            if (!mirrored.contains(organizationId)) {
                removeMembers(organizationId, Set.of(), existing.contains(organizationId));
            }
        }
        Optional<String> realmGroup = piece(parts -> parts.keycloak().realmGroupRoot());
        realmGroup.ifPresent(this::projectRealm);
        return new ProjectionReport(organizations, groupsCreated, groupsRenamed, groupsMoved, groupsRemoved,
                membersJoined.get(), membersLeft.get(), organizationMembersAdded.get(),
                organizationMembersRemoved.get(), realmGroup.isPresent() ? 1 : 0);
    }

    // Every piece puts off its writes to its end. See OrganizationGroups.inOneFlush.
    private <T> T piece(Function<Parts, T> piece) {
        return work.inNewTransaction(parts -> parts.keycloak().inOneFlush(() -> piece.apply(parts)));
    }

    private boolean project(OrgUnit root, LocalDate today) {
        Target target = new Target(root.keycloakOrganizationId(), true);
        Plan plan = piece(parts -> {
            OrgUnitTree tree = new OrgUnitTree(parts.orgUnits(), parts.refItems(), today);
            // A linked unit that does not exist today has nothing to mirror.
            if (!tree.exists(root.id())) {
                return null;
            }
            List<OrgUnitState> subtree = tree.subtree(root.id());
            List<OrgUnitState> below = List.copyOf(subtree.subList(1, subtree.size()));
            requireUniqueNames(below);
            return new Plan(below, target.groups(parts.keycloak()));
        });
        if (plan == null) {
            return false;
        }
        Map<String, String> groupIdByUnit = new HashMap<>();
        park(root.id(), plan.below(), plan.existing());
        mirrorAll(target, root.id(), plan.below(), byUnit(plan.existing()), groupIdByUnit);
        removeGone(plan.existing(), groupIdByUnit);
        // The linked unit itself has no group. Its people are members of the organization only.
        List<String> unitIds = new ArrayList<>(List.of(root.id()));
        plan.below().forEach(unit -> unitIds.add(unit.unit().id()));
        Set<String> wanted = new HashSet<>();
        writeMembers(target, unitIds, today, groupIdByUnit, wanted);
        removeMembers(target.id(), wanted, true);
        return true;
    }

    // Every root becomes a group right below the realm group, each as it is on its own date.
    private void projectRealm(String rootGroupId) {
        Target target = new Target(rootGroupId, false);
        List<Linked> roots = piece(parts -> new OrgUnitTree(parts.orgUnits(), parts.refItems(),
                parts.today().ofRealm()).roots().stream()
                .map(root -> new Linked(root.unit(), parts.today().ofUnit(root.unit().id())))
                .toList());
        Map<Linked, List<OrgUnitState>> trees = new LinkedHashMap<>();
        for (Linked root : roots) {
            trees.put(root, piece(parts -> {
                OrgUnitTree tree = new OrgUnitTree(parts.orgUnits(), parts.refItems(), root.date());
                return tree.exists(root.unit().id()) ? tree.subtree(root.unit().id()) : List.<OrgUnitState>of();
            }));
        }
        List<OrgUnitState> all = trees.values().stream().flatMap(List::stream).toList();
        requireUniqueNames(all);
        List<Group> existing = piece(parts -> target.groups(parts.keycloak()));
        Map<String, Group> mirrored = byUnit(existing);
        Map<String, String> groupIdByUnit = new HashMap<>();
        park(null, all, existing);
        trees.values().forEach(units -> mirrorAll(target, null, units, mirrored, groupIdByUnit));
        removeGone(existing, groupIdByUnit);
        Set<String> everyone = new HashSet<>();
        trees.forEach((root, units) -> writeMembers(target, units.stream().map(unit -> unit.unit().id()).toList(),
                root.date(), groupIdByUnit, everyone));
    }

    private static Map<String, Group> byUnit(List<Group> existing) {
        return existing.stream()
                .filter(group -> group.orgUnitId() != null)
                .collect(Collectors.toMap(Group::orgUnitId, group -> group, (first, second) -> first));
    }

    // A parent comes before its children, so its group is known when a child needs it.
    private void mirrorAll(Target target, String rootId, List<OrgUnitState> units, Map<String, Group> mirrored,
            Map<String, String> groupIdByUnit) {
        for (List<OrgUnitState> some : inPieces(units, UNITS_AT_A_TIME)) {
            piece(parts -> {
                some.forEach(unit -> mirror(parts.keycloak(), target, rootId, unit, mirrored, groupIdByUnit));
                return null;
            });
        }
    }

    // Keycloak allows one name among the groups of one parent. A group that goes away, or that gets another name
    // or another parent, first takes its own ID as its name. Then it holds no name that another group needs.
    private void park(String rootId, List<OrgUnitState> units, List<Group> existing) {
        Map<String, Group> mirrored = byUnit(existing);
        Map<String, OrgUnitState> wanted = new HashMap<>();
        units.forEach(unit -> wanted.put(unit.unit().id(), unit));
        List<Group> changing = existing.stream()
                .filter(group -> group.orgUnitId() != null)
                .filter(group -> changes(group, wanted.get(group.orgUnitId()), rootId, mirrored))
                .toList();
        for (List<Group> some : inPieces(changing, UNITS_AT_A_TIME)) {
            piece(parts -> {
                some.forEach(group -> parts.keycloak().renameGroup(group.id(), group.id()));
                return null;
            });
        }
        changing.forEach(group -> parked.add(group.id()));
    }

    private static boolean changes(Group group, OrgUnitState unit, String rootId, Map<String, Group> mirrored) {
        // A second group of the same unit goes away.
        if (unit == null || !mirrored.get(group.orgUnitId()).id().equals(group.id())) {
            return true;
        }
        boolean top = unit.parentId() == null || unit.parentId().equals(rootId);
        Group parent = top ? null : mirrored.get(unit.parentId());
        if (!top && parent == null) {
            return true;
        }
        return !Objects.equals(group.parentId(), parent == null ? null : parent.id())
                || !group.name().equals(unit.version().name());
    }

    private void removeGone(List<Group> existing, Map<String, String> groupIdByUnit) {
        piece(parts -> {
            removeGroupsOfGoneUnits(parts.keycloak(), existing, groupIdByUnit);
            return null;
        });
    }

    private void writeMembers(Target target, List<String> unitIds, LocalDate today, Map<String, String> groupIdByUnit,
            Set<String> everyone) {
        for (List<String> some : inPieces(unitIds, UNITS_AT_A_TIME)) {
            run(piece(parts -> memberWrites(parts, target, some, today, groupIdByUnit, everyone)));
        }
    }

    // The writes of different users do not touch the same rows, so they run at the same time. The time of a write
    // goes mostly into trips to the database.
    private void run(List<MemberWrite> writes) {
        List<Consumer<Parts>> pieces = new ArrayList<>();
        for (List<MemberWrite> few : byUser(writes)) {
            pieces.add(parts -> parts.keycloak().inOneFlush(() -> {
                few.forEach(write -> write.action().accept(parts));
                return null;
            }));
        }
        work.inParallel(pieces);
    }

    // A recorded user that no assignment of today puts in the organization leaves it. The record goes in any case,
    // also when someone else took the user out before.
    private void removeMembers(String organizationId, Set<String> wanted, boolean exists) {
        // The removal of an organization removes its records. A record that a rebuild wrote at the same time stays.
        if (!exists) {
            piece(parts -> {
                parts.projectedMembers().forgetOrganization(organizationId);
                return null;
            });
            return;
        }
        String after = null;
        while (true) {
            String from = after;
            List<String> page = piece(parts -> parts.projectedMembers().recordedAfter(organizationId, from,
                    RECORDS_AT_A_TIME));
            List<MemberWrite> writes = new ArrayList<>();
            for (String userId : page) {
                if (!wanted.contains(userId)) {
                    writes.add(new MemberWrite(userId, parts -> {
                        if (parts.keycloak().removeOrganizationMember(organizationId, userId)) {
                            organizationMembersRemoved.incrementAndGet();
                        }
                        parts.projectedMembers().forget(organizationId, userId);
                    }));
                }
            }
            run(writes);
            if (page.size() < RECORDS_AT_A_TIME) {
                return;
            }
            after = page.getLast();
        }
    }

    // Keycloak reads a user again in each transaction that changes it, and in a realm with organizations each
    // read asks for the organizations of the user. So the writes of one user go into the same transaction.
    private static List<List<MemberWrite>> byUser(List<MemberWrite> writes) {
        Map<String, List<MemberWrite>> ofUser = new LinkedHashMap<>();
        writes.forEach(write -> ofUser.computeIfAbsent(write.userId(), id -> new ArrayList<>()).add(write));
        List<List<MemberWrite>> pieces = new ArrayList<>();
        List<MemberWrite> current = new ArrayList<>();
        for (List<MemberWrite> some : ofUser.values()) {
            if (!current.isEmpty() && current.size() + some.size() > MEMBER_WRITES_AT_A_TIME) {
                pieces.add(current);
                current = new ArrayList<>();
            }
            current.addAll(some);
        }
        if (!current.isEmpty()) {
            pieces.add(current);
        }
        return pieces;
    }

    private record MemberWrite(String userId, Consumer<Parts> action) {
    }

    private static <E> List<List<E>> inPieces(List<E> all, int size) {
        List<List<E>> pieces = new ArrayList<>();
        for (int start = 0; start < all.size(); start += size) {
            pieces.add(all.subList(start, Math.min(start + size, all.size())));
        }
        return pieces;
    }

    // Reads who is where for some units, in a few queries, and returns the writes that are missing.
    private List<MemberWrite> memberWrites(Parts parts, Target target, List<String> unitIds,
            LocalDate today, Map<String, String> groupIdByUnit, Set<String> everyone) {
        List<MemberWrite> writes = new ArrayList<>();
        Map<String, Set<String>> usersByUnit = usersByUnit(parts, unitIds, today);
        if (target.organization()) {
            String organizationId = target.id();
            Set<String> newHere = new LinkedHashSet<>();
            usersByUnit.values().forEach(users -> users.stream().filter(everyone::add).forEach(newHere::add));
            Set<String> inOrganization = parts.keycloak().organizationMembers(organizationId, newHere);
            List<String> missing = newHere.stream().filter(userId -> !inOrganization.contains(userId)).toList();
            // A recorded user is missing when someone took the user out by hand. The old record goes.
            Set<String> recorded = missing.isEmpty() ? Set.of()
                    : parts.projectedMembers().recorded(organizationId, missing);
            for (String userId : missing) {
                writes.add(new MemberWrite(userId, write -> {
                    // A user who is a member already gets no record, because someone else added the user.
                    if (write.keycloak().addOrganizationMember(organizationId, userId)) {
                        organizationMembersAdded.incrementAndGet();
                        if (recorded.contains(userId)) {
                            write.projectedMembers().forget(organizationId, userId);
                        }
                        write.projectedMembers().record(organizationId, userId);
                    }
                }));
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

    private void mirror(OrganizationGroups keycloak, Target target, String rootId, OrgUnitState unit,
            Map<String, Group> mirrored, Map<String, String> groupIdByUnit) {
        String unitId = unit.unit().id();
        String parentGroupId = unit.parentId() == null || unit.parentId().equals(rootId) ? null
                : groupIdByUnit.get(unit.parentId());
        Group group = mirrored.get(unitId);
        if (group == null) {
            groupIdByUnit.put(unitId, target.create(keycloak, unit.version().name(), parentGroupId, unitId));
            groupsCreated++;
            return;
        }
        groupIdByUnit.put(unitId, group.id());
        if (!Objects.equals(group.parentId(), parentGroupId)) {
            target.move(keycloak, group.id(), parentGroupId);
            groupsMoved++;
        }
        boolean renamed = !group.name().equals(unit.version().name());
        if (renamed || parked.contains(group.id())) {
            keycloak.renameGroup(group.id(), unit.version().name());
        }
        if (renamed) {
            groupsRenamed++;
        }
    }

    private void memberWrites(String groupId, Set<String> current, Set<String> wanted,
            List<MemberWrite> writes) {
        for (String userId : wanted) {
            if (!current.contains(userId)) {
                writes.add(new MemberWrite(userId, parts -> {
                    if (parts.keycloak().join(groupId, userId)) {
                        membersJoined.incrementAndGet();
                    }
                }));
            }
        }
        for (String userId : current) {
            if (!wanted.contains(userId)) {
                writes.add(new MemberWrite(userId, parts -> {
                    if (parts.keycloak().leave(groupId, userId)) {
                        membersLeft.incrementAndGet();
                    }
                }));
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
