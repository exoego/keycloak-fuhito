package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.store.UnitRoleStore;

final class MemoryUnitRoleStore implements UnitRoleStore {

    final List<UnitRole> roles = new ArrayList<>();

    /** The tree that the holders of a unit come from. A test of holders sets it. */
    MemoryOrgUnitStore orgUnits;
    MemoryRefItemStore refItems = new MemoryRefItemStore();

    @Override
    public Optional<UnitRole> find(String id) {
        return roles.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public List<UnitRole> findByEmployment(String employmentId) {
        return roles.stream().filter(r -> r.employmentId().equals(employmentId))
                .sorted(Comparator.comparing((UnitRole r) -> r.period().from()).thenComparing(UnitRole::id))
                .toList();
    }

    @Override
    public List<UnitRole> holdersOn(String hierarchyId, String orgUnitId, String roleId, boolean nearest,
            LocalDate date, int first, int max) {
        List<OrgUnitState> path = new OrgUnitTree(orgUnits, refItems, date, hierarchyId).path(orgUnitId);
        List<UnitRole> found = new ArrayList<>();
        for (int depth = 0; depth < path.size(); depth++) {
            String unitId = path.get(path.size() - 1 - depth).unit().id();
            boolean own = depth == 0;
            Set<String> nearer = found.stream().map(UnitRole::roleId).collect(Collectors.toSet());
            roles.stream()
                    .filter(r -> r.orgUnitId().equals(unitId) && r.period().contains(date)
                            && (own || r.includesDescendants()) && (roleId == null || roleId.equals(r.roleId()))
                            && !(nearest && nearer.contains(r.roleId())))
                    .sorted(Comparator.comparing((UnitRole r) -> refItems.find(r.roleId()).orElseThrow().code())
                            .thenComparing(UnitRole::id))
                    .forEach(found::add);
        }
        return found.stream().skip(first).limit(max).toList();
    }

    @Override
    public List<UnitRole> ofPersonOn(String personId, LocalDate date, int first, int max) {
        return roles.stream()
                .filter(r -> r.personId().equals(personId) && r.period().contains(date))
                .sorted(Comparator.comparing((UnitRole r) -> r.period().from()).thenComparing(UnitRole::id))
                .skip(first).limit(max).toList();
    }

    @Override
    public List<UnitRole> searchOn(Search search, String afterId, int first, int max) {
        return found(search)
                .filter(r -> afterId == null || r.id().compareTo(afterId) > 0)
                .sorted(Comparator.comparing(UnitRole::id))
                .skip(first)
                .limit(max)
                .toList();
    }

    @Override
    public long countSearchOn(Search search) {
        return found(search).count();
    }

    private Stream<UnitRole> found(Search search) {
        return roles.stream().filter(r -> r.period().contains(search.date())
                && (search.roleId() == null || search.roleId().equals(r.roleId()))
                && (search.orgUnitId() == null || search.orgUnitId().equals(r.orgUnitId())));
    }

    @Override
    public void insert(UnitRole role) {
        roles.add(role);
    }

    @Override
    public void update(UnitRole role) {
        roles.replaceAll(r -> r.id().equals(role.id()) ? role : r);
    }

    @Override
    public void delete(String id) {
        roles.removeIf(r -> r.id().equals(id));
    }
}
