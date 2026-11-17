package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Gives org units and assignments the codes of their work location and cost center, and of the ones that apply to
 * them.
 */
final class UnitPlaces {

    private UnitPlaces() {
    }

    /**
     * Gives each unit its values on a date, with one read for all of them.
     *
     * @param states the units on the date
     * @param store the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param date the date
     * @return the units with their values, in the same order
     */
    static List<OrgUnitState> on(List<OrgUnitState> states, OrgUnitStore store, RefItemStore refItems,
            LocalDate date) {
        if (states.isEmpty()) {
            return states;
        }
        Map<String, UnitDefaults> applying = store.defaultsOn(states.stream().map(s -> s.unit().id()).toList(), date);
        Function<String, String> code = codes(refItems);
        return states.stream().map(state -> {
            UnitDefaults effective = applying.getOrDefault(state.unit().id(), UnitDefaults.NONE);
            return state.withPlaces(new LocationAndCost(code.apply(state.version().workLocationId()),
                    code.apply(state.version().costCenterId()), code.apply(effective.workLocationId()),
                    code.apply(effective.costCenterId())));
        }).toList();
    }

    /**
     * Gives each row of assignments its values. The values that apply to a row are the ones on its date nearest to
     * a date. There is one read for each such date.
     *
     * @param rows the rows
     * @param date the date
     * @param store the storage of org units of the realm
     * @param code the lookup of the code of a reference item
     * @return the values of each row, in the same order
     */
    static List<LocationAndCost> ofRows(List<Assignment> rows, LocalDate date, OrgUnitStore store,
            Function<String, String> code) {
        Map<LocalDate, Set<String>> unitsByDay = new HashMap<>();
        rows.forEach(row -> unitsByDay.computeIfAbsent(row.period().nearest(date), day -> new HashSet<>())
                .add(row.orgUnitId()));
        Map<LocalDate, Map<String, UnitDefaults>> defaults = new HashMap<>();
        unitsByDay.forEach((day, ids) -> defaults.put(day, store.defaultsOn(ids, day)));
        return rows.stream().map(row -> {
            UnitDefaults unit = defaults.get(row.period().nearest(date))
                    .getOrDefault(row.orgUnitId(), UnitDefaults.NONE);
            String workLocation = code.apply(row.workLocationId());
            String costCenter = code.apply(row.costCenterId());
            return new LocationAndCost(workLocation, costCenter,
                    workLocation == null ? code.apply(unit.workLocationId()) : workLocation,
                    costCenter == null ? code.apply(unit.costCenterId()) : costCenter);
        }).toList();
    }

    /**
     * Gives a row of an assignment its own values only, for a read that does not need the ones that apply.
     *
     * @param row the row
     * @param code the lookup of the code of a reference item
     * @return the values of the row
     */
    static LocationAndCost ownOfRow(Assignment row, Function<String, String> code) {
        return new LocationAndCost(code.apply(row.workLocationId()), code.apply(row.costCenterId()), null, null);
    }

    /**
     * Gives each unit the values of its own versions only, for a read of many dates.
     *
     * @param states the units
     * @param refItems the storage of reference items of the realm
     * @return the units with their own values, in the same order
     */
    static List<OrgUnitState> own(List<OrgUnitState> states, RefItemStore refItems) {
        Function<String, String> code = codes(refItems);
        return states.stream().map(state -> state.withPlaces(new LocationAndCost(
                code.apply(state.version().workLocationId()), code.apply(state.version().costCenterId()), null,
                null))).toList();
    }

    /**
     * Returns a lookup of the code of a reference item that reads each item once.
     *
     * @param refItems the storage of reference items of the realm
     * @return the lookup, which gives null for a null ID
     */
    static Function<String, String> codes(RefItemStore refItems) {
        Map<String, Optional<String>> codes = new HashMap<>();
        return id -> id == null ? null
                : codes.computeIfAbsent(id, key -> refItems.find(key).map(RefItem::code)).orElse(null);
    }
}
