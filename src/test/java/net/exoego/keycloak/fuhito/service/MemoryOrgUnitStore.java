package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
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

    @Override
    public Optional<OrgUnit> findUnit(String id) {
        return Optional.ofNullable(units.get(id));
    }

    @Override
    public Optional<OrgUnit> findUnitByCode(String code) {
        return units.values().stream().filter(u -> code.equals(u.code())).findFirst();
    }

    @Override
    public Optional<OrgUnit> findUnitByExternalId(String source, String externalId) {
        return units.values().stream()
                .filter(u -> source.equals(u.source()) && externalId.equals(u.externalId()))
                .findFirst();
    }

    @Override
    public Optional<OrgUnit> findUnitByOrganizationId(String organizationId) {
        return units.values().stream().filter(u -> organizationId.equals(u.keycloakOrganizationId())).findFirst();
    }

    @Override
    public void updateUnit(OrgUnit unit) {
        units.put(unit.id(), unit);
    }

    /** The unit types that tell groups from line units. A test of groups sets it. */
    MemoryRefItemStore refItems = new MemoryRefItemStore();

    @Override
    public List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, Boolean group, LocalDate date, int first,
            int max) {
        return placed(of(group, new OrgUnitTree(this, refItems, date, hierarchyId).subtree(rootId)), first, max);
    }

    @Override
    public List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, boolean linkedOnly, Boolean group,
            LocalDate date, int first, int max) {
        OrgUnitTree tree = new OrgUnitTree(this, refItems, date, hierarchyId);
        if (parentId != null) {
            return placed(of(group, tree.children(parentId)), first, max);
        }
        return placed(of(group, linkedOnly ? tree.linkedRoots() : tree.roots()), first, max);
    }

    private static List<OrgUnitState> of(Boolean group, List<OrgUnitState> states) {
        return states.stream().filter(state -> group == null || state.group() == group).toList();
    }

    @Override
    public List<PlacedOrgUnit> placedOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        return placed(new OrgUnitTree(this, new MemoryRefItemStore(), date, hierarchyId).all().stream()
                .filter(state -> ids.contains(state.unit().id())).toList(), 0, Integer.MAX_VALUE);
    }

    /** The IDs of the unit types that mark a legal entity. A test of legal entities sets them. */
    final Set<String> legalEntityTypes = new HashSet<>();

    @Override
    public List<PlacedOrgUnit> legalEntitiesOn(String hierarchyId, LocalDate date, int first, int max) {
        return placed(new OrgUnitTree(this, new MemoryRefItemStore(), date, hierarchyId).all().stream()
                .filter(state -> legalEntityTypes.contains(state.version().unitTypeId()))
                .sorted(Comparator.comparingInt((OrgUnitState state) -> state.version().displayOrder())
                        .thenComparing(state -> state.version().name()))
                .toList(), first, max);
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
    public List<PlacedOrgUnit> pathOn(String hierarchyId, String id, LocalDate date) {
        OrgUnitTree tree = new OrgUnitTree(this, new MemoryRefItemStore(), date, hierarchyId);
        return tree.exists(id) ? placed(tree.path(id), 0, Integer.MAX_VALUE) : List.of();
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
    public List<OrgUnitVersion> versions(String orgUnitId) {
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
