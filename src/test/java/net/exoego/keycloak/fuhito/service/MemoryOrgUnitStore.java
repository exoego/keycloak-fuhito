package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

final class MemoryOrgUnitStore implements OrgUnitStore {

    final Map<String, OrgUnit> units = new LinkedHashMap<>();
    final List<OrgUnitVersion> versions = new ArrayList<>();
    final List<OrgUnitLink> links = new ArrayList<>();
    final List<OrgUnitSuccession> successions = new ArrayList<>();
    final Map<String, String> hierarchyCodes = new LinkedHashMap<>(Map.of("default-hierarchy", "default"));

    @Override
    public Map<String, String> hierarchyCodes() {
        return hierarchyCodes;
    }

    @Override
    public String defaultHierarchyId() {
        return "default-hierarchy";
    }

    /** How often one unit, its versions, or its parents were read. */
    int oneReads;
    /** How often the units, the versions, or the parents of a list, or the versions with some codes were read. */
    int manyReads;

    @Override
    public Optional<OrgUnit> findUnit(String id) {
        oneReads++;
        return Optional.ofNullable(units.get(id));
    }

    @Override
    public List<OrgUnit> findUnits(Collection<String> ids) {
        manyReads++;
        return ids.stream().distinct().map(units::get).filter(Objects::nonNull).toList();
    }

    @Override
    public Optional<OrgUnit> findUnitByCode(String code, LocalDate date) {
        return versions.stream()
                .filter(v -> code.equals(v.code()) && v.period().contains(date))
                .findFirst()
                .map(v -> units.get(v.orgUnitId()));
    }

    @Override
    public List<OrgUnitVersion> versionsWithCodes(Collection<String> codes) {
        manyReads++;
        return versions.stream().filter(v -> v.code() != null && codes.contains(v.code())).toList();
    }

    @Override
    public Map<String, List<OrgUnitVersion>> versionsOf(Collection<String> orgUnitIds) {
        manyReads++;
        Map<String, List<OrgUnitVersion>> found = new HashMap<>();
        orgUnitIds.stream().distinct().forEach(id -> {
            List<OrgUnitVersion> of = stored(id);
            if (!of.isEmpty()) {
                found.put(id, of);
            }
        });
        return found;
    }

    @Override
    public Map<String, ValidPeriod> livesOf(Collection<String> ids) {
        Map<String, ValidPeriod> lives = new HashMap<>();
        versionsOf(ids).forEach((id, of) -> lives.put(id, new ValidPeriod(of.getFirst().period().from(),
                of.getLast().period().to())));
        return lives;
    }

    @Override
    public Map<String, List<OrgUnitLink>> parentLinksOf(String hierarchyId, Collection<String> childIds) {
        manyReads++;
        Map<String, List<OrgUnitLink>> found = new HashMap<>();
        childIds.stream().distinct().forEach(id -> {
            List<OrgUnitLink> of = parentsOf(hierarchyId, id);
            if (!of.isEmpty()) {
                found.put(id, of);
            }
        });
        return found;
    }

    @Override
    public Optional<OrgUnit> findUnitByOrganizationId(String organizationId) {
        return units.values().stream().filter(u -> organizationId.equals(u.keycloakOrganizationId())).findFirst();
    }

    @Override
    public void updateUnit(OrgUnit unit) {
        units.put(unit.id(), unit);
    }

    /** The unit types that tell lateral units from line units. A test of lateral units sets it. */
    MemoryRefItemStore refItems = new MemoryRefItemStore();

    @Override
    public List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, Boolean lateral, LocalDate date, int first,
            int max) {
        return placed(of(lateral, new OrgUnitTree(this, refItems, date, hierarchyId).subtree(rootId)), first, max);
    }

    @Override
    public List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, Boolean lateral, LocalDate date,
            int first, int max) {
        return placed(of(lateral, new OrgUnitTree(this, refItems, date, hierarchyId).children(parentId)), first, max);
    }

    @Override
    public long countTreeOn(String hierarchyId, String rootId, Boolean lateral, LocalDate date) {
        return treeOn(hierarchyId, rootId, lateral, date, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public long countChildrenOn(String hierarchyId, String parentId, Boolean lateral, LocalDate date) {
        return childrenOn(hierarchyId, parentId, lateral, date, 0, Integer.MAX_VALUE).size();
    }

    private static List<OrgUnitState> of(Boolean lateral, List<OrgUnitState> states) {
        return states.stream().filter(state -> lateral == null || state.lateral() == lateral).toList();
    }

    @Override
    public List<PlacedOrgUnit> placedOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        return placed(new OrgUnitTree(this, new MemoryRefItemStore(), date, hierarchyId).all().stream()
                .filter(state -> ids.contains(state.unit().id())).toList(), 0, Integer.MAX_VALUE);
    }

    // The order of siblings that the database uses.
    private static final Comparator<OrgUnitVersion> ORDER = Comparator.comparingInt(OrgUnitVersion::displayOrder)
            .thenComparing((OrgUnitVersion v) -> v.names().primary().phoneticName(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(v -> v.names().primary().name())
            .thenComparing(OrgUnitVersion::orgUnitId);

    @Override
    public List<PlacedOrgUnit> searchOn(String hierarchyId, Search search, LocalDate date, OrgUnitVersion after,
            int first, int max) {
        return placed(found(hierarchyId, search, date).stream()
                .filter(state -> after == null || ORDER.compare(state.version(), after) > 0)
                .toList(), first, max);
    }

    @Override
    public long countSearchOn(String hierarchyId, Search search, LocalDate date) {
        return found(hierarchyId, search, date).size();
    }

    private List<OrgUnitState> found(String hierarchyId, Search search, LocalDate date) {
        String text = search.text() == null ? null : search.text().toLowerCase(Locale.ROOT);
        OrgUnitTree tree = new OrgUnitTree(this, refItems, date, hierarchyId);
        List<OrgUnitState> units = search.roots() == null ? tree.all()
                : search.roots() == Roots.LINKED ? tree.linkedRoots()
                : tree.roots().stream().filter(state -> !state.rollup()).toList();
        return of(search.lateral(), units).stream()
                .filter(state -> search.rollup() == null || state.rollup() == search.rollup())
                .filter(state -> text == null || state.version().names().byLanguage().values().stream()
                        .flatMap(names -> java.util.stream.Stream.of(names.name(), names.shortName(),
                                names.phoneticName()))
                        .anyMatch(name -> name != null && name.toLowerCase(Locale.ROOT).contains(text)))
                .filter(state -> search.code() == null || search.code().equals(state.version().code()))
                .filter(state -> search.unitTypeId() == null
                        || search.unitTypeId().equals(state.version().unitTypeId()))
                .filter(state -> search.legalEntity() == null
                        || search.legalEntity() == legalEntityTypes.contains(state.version().unitTypeId()))
                .sorted(Comparator.comparing(OrgUnitState::version, ORDER))
                .toList();
    }

    /** The IDs of the unit types that mark a legal entity. A test of legal entities sets them. */
    final Set<String> legalEntityTypes = new HashSet<>();

    /** The roles over org units. A test of roles sets it. */
    MemoryUnitRoleStore unitRoles;

    @Override
    public long unitRolesHoldingFrom(String orgUnitId, LocalDate date) {
        return unitRoles == null ? 0 : unitRoles.roles.stream()
                .filter(r -> r.orgUnitId().equals(orgUnitId) && r.period().endsAfter(date))
                .count();
    }

    /** The employments that name an employer. A test of employers sets it. */
    MemoryEmploymentStore employments;

    @Override
    public long employmentsStartingIn(String employerId, LocalDate from, LocalDate to) {
        return employments == null ? 0 : employments.employments.values().stream()
                .filter(e -> employerId.equals(e.employerId()) && !e.period().from().isBefore(from)
                        && (to == null || e.period().from().isBefore(to)))
                .count();
    }

    @Override
    public long employmentsHoldingFrom(String employerId, LocalDate date) {
        return employments == null ? 0 : employments.employments.values().stream()
                .filter(e -> employerId.equals(e.employerId()) && e.period().endsAfter(date))
                .count();
    }

    @Override
    public Map<String, Set<String>> ancestorsOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        OrgUnitTree tree = new OrgUnitTree(this, new MemoryRefItemStore(), date, hierarchyId);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        ids.stream().filter(tree::exists).forEach(id -> result.put(id,
                tree.path(id).stream().map(state -> state.unit().id()).collect(Collectors.toSet())));
        return result;
    }

    @Override
    public Map<String, List<PlacedOrgUnit>> pathsOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        OrgUnitTree tree = new OrgUnitTree(this, new MemoryRefItemStore(), date, hierarchyId);
        Map<String, List<PlacedOrgUnit>> paths = new HashMap<>();
        ids.stream().filter(tree::exists).forEach(id -> paths.put(id, placed(tree.path(id), 0, Integer.MAX_VALUE)));
        return paths;
    }

    /** How often the work locations and the cost centers that apply were read. */
    int defaultsReads;

    @Override
    public Map<String, UnitDefaults> defaultsOn(Collection<String> ids, LocalDate date) {
        defaultsReads++;
        Map<String, UnitDefaults> result = new LinkedHashMap<>();
        for (String id : ids) {
            String workLocationId = null;
            String costCenterId = null;
            for (String unitId : line(defaultHierarchyId(), id, date)) {
                Optional<OrgUnitVersion> version = stored(unitId).stream()
                        .filter(v -> v.period().contains(date))
                        .findFirst();
                if (version.isEmpty()) {
                    break;
                }
                workLocationId = workLocationId == null ? version.get().workLocationId() : workLocationId;
                costCenterId = costCenterId == null ? version.get().costCenterId() : costCenterId;
            }
            if (workLocationId != null || costCenterId != null) {
                result.put(id, new UnitDefaults(workLocationId, costCenterId));
            }
        }
        return result;
    }

    @Override
    public Map<String, String> legalEntitiesOn(Collection<String> ids, LocalDate date) {
        OrgUnitTree tree = new OrgUnitTree(this, refItems, date);
        Map<String, String> result = new HashMap<>();
        ids.stream().filter(tree::exists).forEach(id -> tree.path(id).reversed().stream()
                .filter(OrgUnitState::legalEntity)
                .findFirst()
                .ifPresent(entity -> result.put(id, entity.unit().id())));
        return result;
    }

    @Override
    public List<OrgUnit> unitsWithOwnTime() {
        return units.values().stream().filter(unit -> unit.timeZone() != null || unit.dayStart() != null).toList();
    }

    /** How often the units above a unit were read. */
    int lineReads;

    @Override
    public List<String> lineOn(String hierarchyId, String id, LocalDate date) {
        lineReads++;
        return line(hierarchyId, id, date);
    }

    private List<String> line(String hierarchyId, String id, LocalDate date) {
        List<String> line = new ArrayList<>();
        for (String next = units.containsKey(id) ? id : null; next != null && !line.contains(next);) {
            line.add(next);
            String child = next;
            next = links.stream()
                    .filter(l -> l.hierarchyId().equals(hierarchyId) && l.childId().equals(child)
                            && l.period().contains(date))
                    .map(OrgUnitLink::parentId)
                    .findFirst()
                    .orElse(null);
        }
        return line;
    }

    private static List<PlacedOrgUnit> placed(List<OrgUnitState> states, int first, int max) {
        return states.stream().skip(first).limit(max)
                .map(state -> new PlacedOrgUnit(state.unit(), state.version(), state.parentId()))
                .toList();
    }

    @Override
    public List<OrgUnit> units() {
        return List.copyOf(units.values());
    }

    @Override
    public List<OrgUnitVersion> versionsOn(LocalDate date) {
        return versions.stream().filter(v -> v.period().contains(date)).toList();
    }

    @Override
    public List<OrgUnitLink> linksOn(String hierarchyId, LocalDate date) {
        return links.stream().filter(l -> l.hierarchyId().equals(hierarchyId) && l.period().contains(date)).toList();
    }

    @Override
    public void insertUnit(OrgUnit unit) {
        units.put(unit.id(), unit);
    }

    @Override
    public void insertUnits(List<OrgUnit> list) {
        list.forEach(this::insertUnit);
    }

    @Override
    public List<OrgUnitVersion> versions(String orgUnitId) {
        oneReads++;
        return stored(orgUnitId);
    }

    private List<OrgUnitVersion> stored(String orgUnitId) {
        return versions.stream()
                .filter(v -> v.orgUnitId().equals(orgUnitId))
                .sorted(Comparator.comparing(v -> v.period().from()))
                .toList();
    }

    @Override
    public void insertVersion(OrgUnitVersion version) {
        versions.add(version);
    }

    @Override
    public void updateVersion(OrgUnitVersion version) {
        versions.replaceAll(v -> v.id().equals(version.id()) ? version : v);
    }

    @Override
    public List<OrgUnitLink> parentLinks(String hierarchyId, String childId) {
        oneReads++;
        return parentsOf(hierarchyId, childId);
    }

    private List<OrgUnitLink> parentsOf(String hierarchyId, String childId) {
        return links.stream()
                .filter(l -> l.hierarchyId().equals(hierarchyId) && l.childId().equals(childId))
                .sorted(Comparator.comparing(l -> l.period().from()))
                .toList();
    }

    @Override
    public List<OrgUnitLink> childLinks(String hierarchyId, String parentId) {
        return links.stream()
                .filter(l -> l.hierarchyId().equals(hierarchyId) && parentId.equals(l.parentId()))
                .sorted(Comparator.comparing(l -> l.period().from()))
                .toList();
    }

    @Override
    public List<OrgUnitLink> parentLinks(String childId) {
        return links.stream()
                .filter(l -> l.childId().equals(childId))
                .sorted(Comparator.comparing(l -> l.period().from()))
                .toList();
    }

    @Override
    public long childrenHoldingFrom(String parentId, LocalDate date) {
        return links.stream()
                .filter(l -> l.parentId().equals(parentId) && l.period().endsAfter(date))
                .map(OrgUnitLink::childId)
                .distinct()
                .count();
    }

    @Override
    public void insertLink(OrgUnitLink link) {
        links.add(link);
    }

    @Override
    public void updateLink(OrgUnitLink link) {
        links.replaceAll(l -> l.id().equals(link.id()) ? link : l);
    }

    @Override
    public void deleteLink(String id) {
        links.removeIf(l -> l.id().equals(id));
    }

    @Override
    public void insertSuccession(OrgUnitSuccession succession) {
        if (successions.stream().anyMatch(other -> other.predecessorId().equals(succession.predecessorId())
                && other.successorId().equals(succession.successorId()))) {
            throw new IllegalStateException("stored twice: " + succession);
        }
        successions.add(succession);
    }

    @Override
    public List<OrgUnitSuccession> successions(String orgUnitId) {
        return successions.stream()
                .filter(s -> s.predecessorId().equals(orgUnitId) || s.successorId().equals(orgUnitId))
                .sorted(Comparator.comparing(OrgUnitSuccession::effectiveDate)
                        .thenComparing(OrgUnitSuccession::predecessorId)
                        .thenComparing(OrgUnitSuccession::successorId))
                .toList();
    }
}
