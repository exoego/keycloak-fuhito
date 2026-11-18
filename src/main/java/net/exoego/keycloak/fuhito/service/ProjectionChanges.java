package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.ChangeLogEntry;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.service.ProjectionService.MemberWrite;
import net.exoego.keycloak.fuhito.service.ProjectionService.Parts;
import net.exoego.keycloak.fuhito.service.ProjectionService.Target;
import net.exoego.keycloak.fuhito.store.JobRunStore.Checkpoint;
import net.exoego.keycloak.fuhito.store.OrganizationGroups.Group;
import net.exoego.keycloak.fuhito.store.OrganizationGroups.OwnedGroup;

/**
 * A rebuild of the projection that looks only at what the change log names since the last run that was done.
 * An assignment, an employment, or a person names the users of the person. The run compares the groups and the
 * organizations of each such user with what the assignments of today give. An org unit names its group and the
 * groups below it, in each organization and in the realm group tree.
 */
final class ProjectionChanges {

    /**
     * The name of the job whose checkpoint the projection keeps.
     */
    static final String JOB = "projection";

    // The writes of these change nothing that the projection reads.
    private static final Set<String> IGNORED = Set.of("FUHITO_REF_ITEM", "FUHITO_REF_KIND",
            "FUHITO_ATTRIBUTE_DEFINITION", "FUHITO_REPORTING_LINE", "FUHITO_UNIT_ROLE", "FUHITO_QUALIFICATION",
            "FUHITO_EXTERNAL_ID", "FUHITO_CHANGE_ORDER");

    private static final int ENTRIES_AT_A_TIME = 1000;
    private static final int USERS_AT_A_TIME = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What the change log names since the last run that was done.
     *
     * @param sequence the newest entry of the change log when the run started
     * @param settings what the run depends on outside the change log
     * @param realmGroups true when the realm mirrors the tree into realm groups
     * @param entries the number of entries that were read
     * @param fallback why a full rebuild runs instead, or null
     * @param users the users to look at again
     * @param units the org units that changed
     * @param timeUnits the org units whose time settings changed
     */
    record Scope(long sequence, String settings, boolean realmGroups, int entries, String fallback,
            Set<String> users, Set<String> units, Set<String> timeUnits) {

        static Scope fallback(int entries, String reason) {
            return new Scope(0, "", false, entries, reason, Set.of(), Set.of(), Set.of());
        }

        boolean empty() {
            return users.isEmpty() && units.isEmpty();
        }
    }

    // A linked unit that exists on its own date.
    private record Org(String id, OrgUnit root, LocalDate date) {
    }

    // What the run mirrors into as it is today, and the groups that mirror org units there now.
    // The realm group is null when the realm does not mirror into one. Each of its roots has a date of its own.
    private record Targets(boolean organizations, List<Org> orgs, String realmGroup, Map<String, LocalDate> roots,
            LocalDate realmDate, Map<LocalDate, OrgUnitTree> trees, Map<String, List<Group>> groups,
            List<Group> realmGroups, Set<String> existing) {

        Set<LocalDate> dates() {
            Set<LocalDate> dates = new HashSet<>(roots.values());
            orgs.forEach(org -> dates.add(org.date()));
            return dates;
        }

        List<Group> groupsOf(String organizationId) {
            return groups.getOrDefault(organizationId, List.of());
        }

        // The groups whose members the run may change, with the organization of each, or "" for a realm group.
        Map<String, String> live() {
            Map<String, String> result = new HashMap<>();
            orgs.forEach(org -> groupsOf(org.id()).forEach(group -> result.put(group.id(), org.id())));
            realmGroups.forEach(group -> result.put(group.id(), ""));
            return result;
        }
    }

    // The org units of one place that the run mirrors again, each parent before its children, and the groups there
    // that mirror a changed unit or one of those units, with the groups below them.
    private record Region(List<OrgUnitState> units, List<Group> groups) {

        boolean isEmpty() {
            return units.isEmpty() && groups.isEmpty();
        }
    }

    // The groups and the organizations that the assignments of today give each user.
    private record Wanted(Map<String, Set<String>> groups, Map<String, Set<String>> organizations) {
    }

    private final ProjectionService projection;

    ProjectionChanges(ProjectionService projection) {
        this.projection = projection;
    }

    /**
     * Reads the change log since the last run that was done and finds the users and the org units to look at.
     *
     * @return what changed, or why a full rebuild must run instead
     */
    Scope scope() {
        // The run itself fails when the realm turned the projection off. Then the last run shows the failure.
        if (!projection.piece(parts -> parts.keycloak().projectionEnabled())) {
            return new Scope(0, "", false, 0, null, Set.of(), Set.of(), Set.of());
        }
        Optional<String> realmGroup;
        try {
            realmGroup = projection.piece(parts -> parts.keycloak().realmGroupRoot());
        } catch (FuhitoException e) {
            // The full rebuild fails with the same error, and keeps it as its last run.
            return Scope.fallback(0, "settings");
        }
        String settings = ProjectionService.settings(
                projection.piece(parts -> parts.keycloak().organizationsAvailable()), realmGroup);
        long sequence = projection.piece(parts -> parts.changeLog().lastSequence());
        Optional<Checkpoint> checkpoint = projection.piece(parts -> parts.runs().checkpoint(JOB));
        if (checkpoint.isEmpty()) {
            return Scope.fallback(0, "no_earlier_run");
        }
        if (!checkpoint.get().settings().equals(settings)) {
            return Scope.fallback(0, "settings");
        }
        long since = checkpoint.get().sequence();
        Reading reading = new Reading();
        long after = since;
        while (after < sequence && reading.fallback == null) {
            long from = after;
            List<ChangeLogEntry> page = projection.piece(parts -> parts.changeLog().list(from, null, null, 0,
                    ENTRIES_AT_A_TIME));
            page.stream().filter(entry -> entry.sequence() <= sequence).forEach(reading::add);
            after = page.size() < ENTRIES_AT_A_TIME ? sequence : page.getLast().sequence();
        }
        if (reading.fallback != null) {
            return Scope.fallback(reading.entries, reading.fallback);
        }
        return reading.users(since, sequence)
                .map(users -> new Scope(sequence, settings, realmGroup.isPresent(), reading.entries, null, users,
                        reading.units, reading.timeUnits))
                .orElseGet(() -> Scope.fallback(reading.entries, "earlier_state"));
    }

    // The resources that the entries name.
    private final class Reading {
        private final Set<String> assignments = new HashSet<>();
        private final Set<String> employments = new HashSet<>();
        // The operation of the first entry of each person.
        private final Map<String, String> people = new HashMap<>();
        private final Set<String> users = new HashSet<>();
        private final Set<String> units = new HashSet<>();
        private final Set<String> timeUnits = new HashSet<>();
        private int entries;
        private String fallback;

        void add(ChangeLogEntry entry) {
            if (fallback != null) {
                return;
            }
            entries++;
            String id = entry.resourceId();
            switch (entry.resourceType()) {
                case "FUHITO_ASSIGNMENT" -> assignments.add(id);
                case "FUHITO_EMPLOYMENT" -> employments.add(id);
                case "FUHITO_PERSON" -> {
                    people.putIfAbsent(id, entry.operation());
                    userOf(json(entry.representation())).ifPresent(users::add);
                }
                case "FUHITO_ORG_UNIT" -> {
                    units.add(id);
                    if (entry.resourcePath().endsWith("/time-settings")) {
                        timeUnits.add(id);
                    }
                }
                // A mask clears names. An erase removes the person and the entries about the person.
                case "FUHITO_PERSON_DATA" -> fallback = entry.operation().equals("DELETE") ? "erasure" : null;
                case "FUHITO_RESTORE" -> fallback = "restore";
                case "FUHITO_HIERARCHY" -> fallback = "hierarchy";
                default -> fallback = IGNORED.contains(entry.resourceType()) ? null : "unknown_entry";
            }
        }

        // The users of the people that the entries name, now and when the last run started.
        // An assignment keeps its employment, and an employment keeps its person. A link of a person to a user
        // changes only with an entry of the person. Empty when the log does not tell a user that the run needs.
        Optional<Set<String>> users(long since, long sequence) {
            Map<String, String> employmentOf = new HashMap<>();
            projection.piece(parts -> parts.assignments().rowsOf(assignments))
                    .forEach(row -> employmentOf.put(row.assignmentId(), row.employmentId()));
            Set<String> employmentIds = new HashSet<>(employments);
            employmentIds.addAll(employmentOf.values());
            Optional<List<JsonNode>> removedAssignments = states("FUHITO_ASSIGNMENT",
                    without(assignments, employmentOf.keySet()), sequence);
            if (removedAssignments.isEmpty()) {
                return Optional.empty();
            }
            removedAssignments.get().forEach(state -> employmentIds.add(state.path("employmentId").asText()));
            List<Employment> found = projection.piece(parts -> parts.employments().findAll(employmentIds));
            found.stream().map(Employment::userId).filter(Objects::nonNull).forEach(users::add);
            Optional<List<JsonNode>> removedEmployments = states("FUHITO_EMPLOYMENT",
                    without(employmentIds, found.stream().map(Employment::id).collect(Collectors.toSet())), sequence);
            if (removedEmployments.isEmpty()) {
                return Optional.empty();
            }
            Set<String> owners = removedEmployments.get().stream().map(state -> state.path("personId").asText())
                    .collect(Collectors.toSet());
            users.addAll(projection.piece(parts -> owners.stream().map(parts.people()::find)
                    .flatMap(Optional::stream).map(Person::userId).filter(Objects::nonNull).toList()));
            Set<String> changed = people.entrySet().stream().filter(person -> !person.getValue().equals("CREATE"))
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            Optional<List<JsonNode>> before = states("FUHITO_PERSON", changed, since);
            before.ifPresent(states -> states.forEach(state -> userOf(state).ifPresent(users::add)));
            return before.map(states -> users);
        }
    }

    private static Set<String> without(Set<String> all, Set<String> some) {
        Set<String> result = new HashSet<>(all);
        result.removeAll(some);
        return result;
    }

    // The newest state of each resource up to a sequence number. Empty when one has none in the change log.
    private Optional<List<JsonNode>> states(String resourceType, Set<String> ids, long upTo) {
        Map<String, String> states = ids.isEmpty() ? Map.of()
                : projection.piece(parts -> parts.changeLog().statesUpTo(resourceType, ids, upTo));
        return states.size() < ids.size() ? Optional.empty()
                : Optional.of(states.values().stream().map(ProjectionChanges::json).toList());
    }

    private static JsonNode json(String text) {
        return text == null ? JSON.missingNode() : Unchecked.io(() -> JSON.readTree(text));
    }

    private static Optional<String> userOf(JsonNode state) {
        JsonNode userId = state.path("userId");
        return userId.isTextual() ? Optional.of(userId.asText()) : Optional.empty();
    }

    /**
     * Lists the organizations that a run of the changes may change, for the check of the permissions.
     *
     * @param scope what changed
     * @return the IDs of the organizations
     */
    Set<String> organizations(Scope scope) {
        Set<String> result = new TreeSet<>();
        if (scope.empty()) {
            return result;
        }
        Targets targets = targets();
        if (!targets.organizations()) {
            return result;
        }
        if (!scope.units().isEmpty()) {
            Set<String> whole = wholeOrganizations(scope, targets);
            for (Org org : targets.orgs()) {
                if (whole.contains(org.id()) || !regionOf(targets, org, scope.units()).isEmpty()) {
                    result.add(org.id());
                }
            }
            result.addAll(orphans(targets));
        }
        Map<String, String> live = targets.live();
        for (List<String> some : ProjectionService.inPieces(sorted(scope.users()), USERS_AT_A_TIME)) {
            projection.piece(parts -> {
                wanted(parts, some, targets).organizations().values().forEach(result::addAll);
                parts.projectedMembers().organizationsOf(some).values().forEach(organizations -> organizations
                        .stream().filter(targets.existing()::contains).forEach(result::add));
                parts.keycloak().groupsOf(some).values().forEach(groups -> groups.stream().map(live::get)
                        .filter(organizationId -> organizationId != null && !organizationId.isEmpty())
                        .forEach(result::add));
                return null;
            });
        }
        return result;
    }

    /**
     * Tells whether a run of the changes may change the realm groups, for the check of the permissions.
     *
     * @param scope what changed
     * @return true when it may
     */
    boolean touchesRealmGroups(Scope scope) {
        return scope.realmGroups() && !scope.empty();
    }

    /**
     * Runs the changes. The caller holds the lock of the projection of the realm.
     *
     * @param scope what changed
     * @param organizationsSeen the number of organizations that the run may change
     * @return what the run changed, and how much it looked at
     */
    ProjectionReport run(Scope scope, int organizationsSeen) {
        projection.requireEnabled();
        Set<String> users = new HashSet<>(scope.users());
        Set<String> mirrored = new HashSet<>();
        if (!scope.empty()) {
            Targets targets = targets();
            if (!scope.units().isEmpty()) {
                users.addAll(mirrorUnits(scope, targets, mirrored));
                targets = withGroupsOfNow(targets);
            }
            lookAgain(users, targets);
        }
        projection.saveCheckpoint(new Checkpoint(scope.sequence(), scope.settings()));
        return projection.report(mirrored.size(), scope.realmGroups())
                .ofChanges(scope.entries(), users.size(), organizationsSeen);
    }

    private Targets targets() {
        boolean organizations = projection.piece(parts -> parts.keycloak().organizationsAvailable());
        String realmGroup = projection.piece(parts -> parts.keycloak().realmGroupRoot()).orElse(null);
        LocalDate realmDate = projection.piece(parts -> parts.today().ofRealm());
        Map<LocalDate, OrgUnitTree> trees = new HashMap<>();
        List<Org> linked = !organizations ? List.of() : projection.piece(parts -> parts.orgUnits().units().stream()
                .filter(unit -> unit.keycloakOrganizationId() != null)
                .map(unit -> new Org(unit.keycloakOrganizationId(), unit, parts.today().ofUnit(unit.id())))
                .toList());
        List<Org> orgs = linked.stream().filter(org -> tree(trees, org.date()).exists(org.root().id())).toList();
        Map<String, LocalDate> roots = new HashMap<>();
        if (realmGroup != null) {
            OrgUnitTree realmTree = tree(trees, realmDate);
            roots.putAll(projection.piece(parts -> realmTree.roots().stream().collect(Collectors.toMap(
                    root -> root.unit().id(), root -> parts.today().ofUnit(root.unit().id())))));
            roots.values().forEach(date -> tree(trees, date));
        }
        Set<String> existing = organizations ? Set.copyOf(projection.piece(parts -> parts.keycloak().organizations()))
                : Set.of();
        return withGroupsOfNow(new Targets(organizations, orgs, realmGroup, roots, realmDate, trees, Map.of(),
                List.of(), existing));
    }

    private OrgUnitTree tree(Map<LocalDate, OrgUnitTree> trees, LocalDate date) {
        return trees.computeIfAbsent(date, day -> projection.piece(parts -> new OrgUnitTree(parts.orgUnits(),
                parts.refItems(), day)));
    }

    private Targets withGroupsOfNow(Targets targets) {
        List<OwnedGroup> owned = projection.piece(parts -> parts.keycloak().ownedGroups());
        Map<String, List<Group>> groups = owned.stream().filter(group -> group.organizationId() != null)
                .collect(Collectors.groupingBy(OwnedGroup::organizationId,
                        Collectors.mapping(OwnedGroup::group, Collectors.toList())));
        groups.replaceAll((organizationId, list) -> parentFirst(list));
        List<Group> realmGroups = targets.realmGroup() == null ? List.of()
                : parentFirst(below(targets.realmGroup(), owned.stream()
                        .filter(group -> group.organizationId() == null).map(OwnedGroup::group).toList()));
        return new Targets(targets.organizations(), targets.orgs(), targets.realmGroup(), targets.roots(),
                targets.realmDate(), targets.trees(), groups, realmGroups, targets.existing());
    }

    // The realm groups below the root, with no parent for those right below it.
    private static List<Group> below(String rootGroupId, List<Group> realmGroups) {
        Map<String, Group> byId = realmGroups.stream().collect(Collectors.toMap(Group::id, group -> group));
        List<Group> result = new ArrayList<>();
        for (Group group : realmGroups) {
            String parentId = group.parentId();
            while (byId.containsKey(parentId)) {
                parentId = byId.get(parentId).parentId();
            }
            if (rootGroupId.equals(parentId)) {
                result.add(rootGroupId.equals(group.parentId())
                        ? new Group(group.id(), group.name(), null, group.orgUnitId()) : group);
            }
        }
        return result;
    }

    // A parent comes before its children.
    private static List<Group> parentFirst(List<Group> groups) {
        Map<String, Group> byId = groups.stream().collect(Collectors.toMap(Group::id, group -> group));
        Map<String, Integer> depths = new HashMap<>();
        for (Group group : groups) {
            int depth = 0;
            for (Group up = byId.get(group.parentId()); up != null; up = byId.get(up.parentId())) {
                depth++;
            }
            depths.put(group.id(), depth);
        }
        return groups.stream().sorted(Comparator.comparing((Group group) -> depths.get(group.id()))
                .thenComparing(Group::id)).toList();
    }

    private static List<String> sorted(Set<String> ids) {
        return List.copyOf(new TreeSet<>(ids));
    }

    // A changed linked unit, or a time setting at it or above it, can change everything in its organization.
    private Set<String> wholeOrganizations(Scope scope, Targets targets) {
        Set<String> result = new HashSet<>();
        for (Org org : targets.orgs()) {
            String rootId = org.root().id();
            if (scope.units().contains(rootId) || timeChangedAt(scope, targets, rootId)) {
                result.add(org.id());
            }
        }
        return result;
    }

    // The date of a unit follows the nearest time setting at the unit or above it.
    private boolean timeChangedAt(Scope scope, Targets targets, String unitId) {
        return !scope.timeUnits().isEmpty() && projection.piece(parts -> parts.orgUnits().lineOn(
                parts.orgUnits().defaultHierarchyId(), unitId, targets.realmDate())).stream()
                .anyMatch(scope.timeUnits()::contains);
    }

    // The organizations that keep groups or records although no unit of today links them.
    private Set<String> orphans(Targets targets) {
        Set<String> result = new TreeSet<>(targets.groups().keySet());
        result.addAll(projection.piece(parts -> parts.projectedMembers().organizations()));
        targets.orgs().forEach(org -> result.remove(org.id()));
        return result;
    }

    // Returns the users who joined or left with a group.
    private Set<String> mirrorUnits(Scope scope, Targets targets, Set<String> mirrored) {
        Set<String> users = new HashSet<>();
        Set<String> whole = wholeOrganizations(scope, targets);
        for (Org org : targets.orgs()) {
            Region region = regionOf(targets, org, scope.units());
            if (whole.contains(org.id())) {
                projection.project(org.root(), org.date());
                mirrored.add(org.id());
            } else if (!region.isEmpty()) {
                mirror(new Target(org.id(), true), org.root().id(), targets.trees().get(org.date()), region,
                        targets.groupsOf(org.id()), org.date())
                        .ifPresentOrElse(users::addAll, () -> projection.project(org.root(), org.date()));
                mirrored.add(org.id());
            }
        }
        if (targets.organizations()) {
            // The same as a full rebuild does for an organization that no unit of today links.
            for (String organizationId : orphans(targets)) {
                projection.removeGone(targets.groupsOf(organizationId), Map.of());
                projection.removeMembers(organizationId, Set.of(), targets.existing().contains(organizationId));
            }
        }
        if (targets.realmGroup() != null) {
            // Each root is mirrored as it is on its own date. That is simple only with one date.
            boolean oneDate = targets.roots().values().stream().allMatch(targets.realmDate()::equals);
            OrgUnitTree tree = targets.trees().get(targets.realmDate());
            Region region = region(tree, unitId -> inRealm(targets, unitId, targets.realmDate()), scope.units(),
                    targets.realmGroups());
            if (!oneDate || !scope.timeUnits().isEmpty()) {
                projection.projectRealm(targets.realmGroup());
            } else if (!region.isEmpty()) {
                mirror(new Target(targets.realmGroup(), false), null, tree, region, targets.realmGroups(),
                        targets.realmDate())
                        .ifPresentOrElse(users::addAll, () -> projection.projectRealm(targets.realmGroup()));
            }
        }
        return users;
    }

    private static Region regionOf(Targets targets, Org org, Set<String> changed) {
        OrgUnitTree tree = targets.trees().get(org.date());
        String rootId = org.root().id();
        return region(tree, unitId -> !unitId.equals(rootId) && holds(tree, rootId, unitId), changed,
                targets.groupsOf(org.id()));
    }

    private static Region region(OrgUnitTree tree, Predicate<String> holds, Set<String> changed,
            List<Group> existing) {
        Set<String> held = changed.stream().filter(holds).collect(Collectors.toSet());
        List<OrgUnitState> units = new ArrayList<>();
        for (String unitId : sorted(held)) {
            // A unit below another changed unit comes with that one.
            if (tree.path(unitId).stream().map(state -> state.unit().id())
                    .noneMatch(id -> !id.equals(unitId) && held.contains(id))) {
                units.addAll(tree.subtree(unitId));
            }
        }
        Set<String> unitIds = new HashSet<>(changed);
        units.forEach(unit -> unitIds.add(unit.unit().id()));
        Set<String> picked = new HashSet<>();
        List<Group> groups = new ArrayList<>();
        for (Group group : existing) {
            if (unitIds.contains(group.orgUnitId()) || picked.contains(group.parentId())) {
                picked.add(group.id());
                groups.add(group);
            }
        }
        return new Region(units, groups);
    }

    // Whether the subtree of a linked unit holds a unit, on the date of the tree.
    private static boolean holds(OrgUnitTree tree, String rootId, String unitId) {
        return tree.exists(unitId) && tree.path(unitId).stream().anyMatch(state -> state.unit().id().equals(rootId));
    }

    // Whether a root of the realm group tree holds a unit, on the date of that root.
    private static boolean inRealm(Targets targets, String unitId, LocalDate date) {
        OrgUnitTree tree = targets.trees().get(date);
        if (!tree.exists(unitId)) {
            return false;
        }
        OrgUnitState top = tree.path(unitId).getFirst();
        return top.parentId() == null && date.equals(targets.roots().get(top.unit().id()));
    }

    // Mirrors a region the way a full rebuild mirrors a whole place. Returns the users who joined or left with a
    // group, or empty when the group of a parent outside the region is missing.
    private Optional<Set<String>> mirror(Target target, String rootId, OrgUnitTree tree, Region region,
            List<Group> existing, LocalDate date) {
        List<OrgUnitState> units = region.units();
        Set<String> unitIds = units.stream().map(unit -> unit.unit().id()).collect(Collectors.toSet());
        Map<String, Group> mirrored = ProjectionService.byUnit(existing);
        Map<String, String> groupIdByUnit = new HashMap<>();
        Set<String> parents = new LinkedHashSet<>();
        units.stream().map(OrgUnitState::parentId).filter(parentId -> !unitIds.contains(parentId))
                .forEach(parents::add);
        // Keycloak allows one name among the groups of one parent, so the siblings outside the region count too.
        List<OrgUnitState> siblings = new ArrayList<>(units.stream()
                .filter(unit -> unitIds.contains(unit.parentId())).toList());
        for (String parentId : parents) {
            siblings.addAll(parentId == null ? tree.roots() : tree.children(parentId));
            if (parentId != null && !parentId.equals(rootId)) {
                Group parent = mirrored.get(parentId);
                if (parent == null) {
                    return Optional.empty();
                }
                groupIdByUnit.put(parentId, parent.id());
            }
        }
        ProjectionService.requireUniqueNames(siblings);
        projection.park(rootId, units, region.groups(), mirrored);
        List<String> created = units.stream().map(unit -> unit.unit().id())
                .filter(unitId -> !mirrored.containsKey(unitId)).toList();
        projection.mirrorAll(target, rootId, units, mirrored, groupIdByUnit);
        Set<String> kept = new HashSet<>(groupIdByUnit.values());
        List<String> gone = region.groups().stream()
                .filter(group -> group.orgUnitId() != null && !kept.contains(group.id())).map(Group::id).toList();
        Set<String> users = new HashSet<>();
        projection.piece(parts -> parts.keycloak().members(gone)).values().forEach(users::addAll);
        projection.removeGone(region.groups(), groupIdByUnit);
        for (List<String> some : ProjectionService.inPieces(created, ProjectionService.UNITS_AT_A_TIME)) {
            projection.piece(parts -> ProjectionService.usersByUnit(parts, some, date)).values()
                    .forEach(users::addAll);
        }
        return Optional.of(users);
    }

    private void lookAgain(Set<String> users, Targets targets) {
        for (List<String> some : ProjectionService.inPieces(sorted(users), USERS_AT_A_TIME)) {
            projection.run(projection.piece(parts -> writes(parts, some, targets)));
        }
    }

    // The writes that make the groups and the organizations of some users match what their assignments give.
    private List<MemberWrite> writes(Parts parts, List<String> users, Targets targets) {
        Wanted wanted = wanted(parts, users, targets);
        Map<String, String> live = targets.live();
        Map<String, Set<String>> inGroup = new HashMap<>();
        Map<String, Set<String>> wantGroup = new HashMap<>();
        parts.keycloak().groupsOf(users).forEach((userId, groups) -> groups.stream().filter(live::containsKey)
                .forEach(groupId -> add(inGroup, groupId, userId)));
        wanted.groups().forEach((userId, groups) -> groups.forEach(groupId -> add(wantGroup, groupId, userId)));
        List<MemberWrite> writes = new ArrayList<>();
        Set<String> groupIds = new TreeSet<>(inGroup.keySet());
        groupIds.addAll(wantGroup.keySet());
        for (String groupId : groupIds) {
            projection.memberWrites(groupId, inGroup.getOrDefault(groupId, Set.of()),
                    wantGroup.getOrDefault(groupId, Set.of()), writes);
        }
        if (targets.organizations()) {
            Map<String, Set<String>> wanting = new HashMap<>();
            Map<String, Set<String>> recorded = new HashMap<>();
            wanted.organizations().forEach((userId, organizations) -> organizations
                    .forEach(organizationId -> add(wanting, organizationId, userId)));
            parts.projectedMembers().organizationsOf(users).forEach((userId, organizations) -> organizations
                    .forEach(organizationId -> add(recorded, organizationId, userId)));
            Set<String> organizationIds = new TreeSet<>(wanting.keySet());
            organizationIds.addAll(recorded.keySet());
            for (String organizationId : organizationIds) {
                Set<String> wantedHere = wanting.getOrDefault(organizationId, Set.of());
                Set<String> records = recorded.getOrDefault(organizationId, Set.of());
                Set<String> members = wantedHere.isEmpty() ? Set.of()
                        : parts.keycloak().organizationMembers(organizationId, wantedHere);
                for (String userId : new TreeSet<>(wantedHere)) {
                    if (!members.contains(userId)) {
                        writes.add(projection.addMember(organizationId, userId, records.contains(userId)));
                    }
                }
                // A record of an organization that is gone goes with a full rebuild.
                if (targets.existing().contains(organizationId)) {
                    for (String userId : new TreeSet<>(records)) {
                        if (!wantedHere.contains(userId)) {
                            writes.add(projection.removeMember(organizationId, userId));
                        }
                    }
                }
            }
        }
        return writes;
    }

    private static Wanted wanted(Parts parts, List<String> users, Targets targets) {
        Map<String, String> userOfEmployment = new HashMap<>();
        parts.employments().findByUsers(users).forEach(employment ->
                userOfEmployment.put(employment.id(), employment.userId()));
        Wanted wanted = new Wanted(new HashMap<>(), new HashMap<>());
        Map<String, Map<String, String>> groupOfUnit = new HashMap<>();
        targets.orgs().forEach(org -> groupOfUnit.put(org.id(), groupIds(targets.groupsOf(org.id()))));
        Map<String, String> realmGroupOfUnit = groupIds(targets.realmGroups());
        for (LocalDate date : targets.dates()) {
            OrgUnitTree tree = targets.trees().get(date);
            for (Assignment row : parts.assignments().rowsOfEmploymentsOn(userOfEmployment.keySet(), date)) {
                String userId = userOfEmployment.get(row.employmentId());
                String unitId = row.orgUnitId();
                Set<String> line = !tree.exists(unitId) ? Set.of() : tree.path(unitId).stream()
                        .map(state -> state.unit().id()).collect(Collectors.toSet());
                for (Org org : targets.orgs()) {
                    if (org.date().equals(date) && line.contains(org.root().id())) {
                        add(wanted.organizations(), userId, org.id());
                        // A dated change that became true with no write has no group yet. A full rebuild makes it.
                        Optional.ofNullable(groupOfUnit.get(org.id()).get(unitId))
                                .ifPresent(groupId -> add(wanted.groups(), userId, groupId));
                    }
                }
                if (targets.realmGroup() != null && inRealm(targets, unitId, date)) {
                    Optional.ofNullable(realmGroupOfUnit.get(unitId))
                            .ifPresent(groupId -> add(wanted.groups(), userId, groupId));
                }
            }
        }
        return wanted;
    }

    private static Map<String, String> groupIds(List<Group> groups) {
        Map<String, String> result = new HashMap<>();
        ProjectionService.byUnit(groups).forEach((unitId, group) -> result.put(unitId, group.id()));
        return result;
    }

    private static void add(Map<String, Set<String>> map, String key, String value) {
        map.computeIfAbsent(key, k -> new HashSet<>()).add(value);
    }
}
