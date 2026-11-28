package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
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
        Function<String, String> code = codes(refItems, Stream.concat(
                states.stream().flatMap(state -> Stream.of(state.version().workLocationId(),
                        state.version().costCenterId())),
                applying.values().stream().flatMap(unit -> Stream.of(unit.workLocationId(), unit.costCenterId()))));
        return states.stream().map(state -> {
            UnitDefaults effective = applying.getOrDefault(state.unit().id(), UnitDefaults.NONE);
            return state.withPlaces(new LocationAndCost(code.apply(state.version().workLocationId()),
                    code.apply(state.version().costCenterId()), code.apply(effective.workLocationId()),
                    code.apply(effective.costCenterId())));
        }).toList();
    }

    /**
     * Gives the work location and the cost center that apply to one unit on every date of a period. They change only
     * where the details or the parent of the unit or of a unit above change. So the unit is read on the first date
     * of the period and on each such date in it.
     *
     * @param store the storage of org units of the realm
     * @param id the ID of the unit, which exists on every date of the period
     * @param period the dates
     * @return the values by the date from which they apply, until the next date
     */
    static NavigableMap<LocalDate, UnitDefaults> applying(OrgUnitStore store, String id, ValidPeriod period) {
        String hierarchyId = store.defaultHierarchyId();
        Set<LocalDate> changes = new HashSet<>(List.of(period.from()));
        Deque<Map.Entry<String, ValidPeriod>> line = new ArrayDeque<>(List.of(Map.entry(id, period)));
        while (!line.isEmpty()) {
            Map.Entry<String, ValidPeriod> unit = line.pop();
            ValidPeriod dates = unit.getValue();
            store.versions(unit.getKey()).stream()
                    .map(version -> version.period().from())
                    .filter(dates::contains)
                    .forEach(changes::add);
            for (OrgUnitLink link : store.parentLinks(hierarchyId, unit.getKey())) {
                link.period().intersection(dates).ifPresent(shared -> {
                    changes.add(shared.from());
                    Optional.ofNullable(shared.to()).filter(dates::contains).ifPresent(changes::add);
                    line.push(Map.entry(link.parentId(), shared));
                });
            }
        }
        NavigableMap<LocalDate, UnitDefaults> applying = new TreeMap<>();
        for (LocalDate date : changes) {
            applying.put(date, store.defaultsOn(List.of(id), date).getOrDefault(id, UnitDefaults.NONE));
        }
        return applying;
    }

    /**
     * Gives the work location and the cost center that a unit takes from above on every date of a period. They are
     * the ones that apply to its parent in the default hierarchy, or none on a date with no parent.
     *
     * @param store the storage of org units of the realm
     * @param id the ID of the unit
     * @param period the dates
     * @return the values by the date from which they apply, until the next date
     */
    static NavigableMap<LocalDate, UnitDefaults> above(OrgUnitStore store, String id, ValidPeriod period) {
        NavigableMap<LocalDate, UnitDefaults> above = new TreeMap<>(Map.of(period.from(), UnitDefaults.NONE));
        for (OrgUnitLink link : store.parentLinks(store.defaultHierarchyId(), id)) {
            link.period().intersection(period).ifPresent(shared -> {
                if (shared.to() != null) {
                    above.putIfAbsent(shared.to(), UnitDefaults.NONE);
                }
                above.putAll(applying(store, link.parentId(), shared));
            });
        }
        return above;
    }

    /**
     * Gives each row of assignments its values. The values that apply to a row are the ones on its date nearest to
     * the date of the row. There is one read for each such date, and one read of the codes.
     *
     * @param rows the rows
     * @param dateOf the date of each row
     * @param store the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @return the values of each row, in the same order
     */
    static List<LocationAndCost> ofRows(List<Assignment> rows, Function<Assignment, LocalDate> dateOf,
            OrgUnitStore store, RefItemStore refItems) {
        Map<LocalDate, Set<String>> unitsByDay = new HashMap<>();
        rows.forEach(row -> unitsByDay.computeIfAbsent(row.period().nearest(dateOf.apply(row)),
                day -> new HashSet<>()).add(row.orgUnitId()));
        Map<LocalDate, Map<String, UnitDefaults>> defaults = new HashMap<>();
        unitsByDay.forEach((day, ids) -> defaults.put(day, store.defaultsOn(ids, day)));
        Function<String, String> code = codes(refItems, Stream.concat(
                rows.stream().flatMap(row -> Stream.of(row.workLocationId(), row.costCenterId())),
                defaults.values().stream().flatMap(units -> units.values().stream())
                        .flatMap(unit -> Stream.of(unit.workLocationId(), unit.costCenterId()))));
        return rows.stream().map(row -> {
            UnitDefaults unit = defaults.get(row.period().nearest(dateOf.apply(row)))
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
        Function<String, String> code = codes(refItems, states.stream()
                .flatMap(state -> Stream.of(state.version().workLocationId(), state.version().costCenterId())));
        return states.stream().map(state -> state.withPlaces(new LocationAndCost(
                code.apply(state.version().workLocationId()), code.apply(state.version().costCenterId()), null,
                null))).toList();
    }

    /**
     * Returns a lookup of the code of a reference item that reads the given items with one read.
     *
     * @param refItems the storage of reference items of the realm
     * @param ids the IDs of the items that the lookup gives, with nulls too
     * @return the lookup, which gives null for a null ID
     * @throws IllegalArgumentException from the lookup, for an ID that it did not read
     */
    static Function<String, String> codes(RefItemStore refItems, Stream<String> ids) {
        Set<String> read = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> codes = refItems.codesOf(read);
        return id -> {
            if (id == null) {
                return null;
            }
            if (!read.contains(id)) {
                throw new IllegalArgumentException("The code of " + id + " was not read");
            }
            return codes.get(id);
        };
    }
}
