package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * The org units of one realm in the default hierarchy as they are on one date.
 */
final class OrgUnitTree {

    private static final Comparator<OrgUnitState> SIBLING_ORDER = Comparator
            .comparingInt((OrgUnitState s) -> s.version().displayOrder())
            .thenComparing(s -> s.version().phoneticName(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(s -> s.version().name())
            .thenComparing(s -> s.unit().id());

    private final LocalDate date;
    private final Map<String, OrgUnitState> states = new HashMap<>();
    private final Map<String, List<OrgUnitState>> children = new HashMap<>();
    private final List<OrgUnitState> roots = new ArrayList<>();

    OrgUnitTree(OrgUnitStore store, RefItemStore refItems, LocalDate date) {
        this.date = date;
        Map<String, OrgUnit> units = store.units().stream().collect(Collectors.toMap(OrgUnit::id, Function.identity()));
        Map<String, String> unitTypeCodes = refItems.list(RefKind.UNIT_TYPE, true).stream()
                .collect(Collectors.toMap(RefItem::id, RefItem::code));
        Map<String, String> parents = store.linksOn(store.defaultHierarchyId(), date).stream()
                .collect(Collectors.toMap(OrgUnitLink::childId, OrgUnitLink::parentId));
        store.versionsOn(date).forEach(version -> states.put(version.orgUnitId(), new OrgUnitState(
                units.get(version.orgUnitId()), version, unitTypeCodes.get(version.unitTypeId()),
                parents.get(version.orgUnitId()))));
        for (OrgUnitState state : states.values()) {
            if (state.parentId() == null) {
                roots.add(state);
            } else {
                children.computeIfAbsent(state.parentId(), id -> new ArrayList<>()).add(state);
            }
        }
        roots.sort(SIBLING_ORDER);
        children.values().forEach(list -> list.sort(SIBLING_ORDER));
    }

    List<OrgUnitState> roots() {
        return List.copyOf(roots);
    }

    /** Every unit, each parent before its children. */
    List<OrgUnitState> all() {
        List<OrgUnitState> result = new ArrayList<>();
        roots.forEach(root -> collect(root, result));
        return result;
    }

    List<OrgUnitState> children(String id) {
        require(id);
        return List.copyOf(children.getOrDefault(id, List.of()));
    }

    /** The unit and everything below it, each parent before its children. */
    List<OrgUnitState> subtree(String id) {
        List<OrgUnitState> result = new ArrayList<>();
        collect(require(id), result);
        return result;
    }

    /** From the root down to the unit. */
    List<OrgUnitState> path(String id) {
        List<OrgUnitState> result = new ArrayList<>();
        for (OrgUnitState state = require(id); state != null; state = states.get(state.parentId())) {
            result.add(0, state);
        }
        return result;
    }

    private void collect(OrgUnitState state, List<OrgUnitState> result) {
        result.add(state);
        children.getOrDefault(state.unit().id(), List.of()).forEach(child -> collect(child, result));
    }

    private OrgUnitState require(String id) {
        OrgUnitState state = states.get(id);
        if (state == null) {
            throw FuhitoException.notFound("org_unit.not_valid_on", Map.of("id", id, "date", date.toString()));
        }
        return state;
    }
}
