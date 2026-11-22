package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.DerivedValue;
import net.exoego.keycloak.fuhito.domain.PlaceSpan;
import net.exoego.keycloak.fuhito.domain.PropagatedChange;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.PlaceStore;

/**
 * Finds the org units and the assignments whose work location or cost center that applies a write of a unit changed,
 * although the write did not write them. The write reads what applies below the unit before it changes the unit, and
 * again after. Each unit and each assignment whose read changed gets a new version, and the write names them for
 * the change log.
 */
final class DerivedPlaces {

    private final OrgUnitStore units;
    private final PlaceStore places;
    private final Today today;
    private final Set<String> written;

    /**
     * Creates the reads.
     *
     * @param units the storage of org units of the realm
     * @param places the reads of what applies below a unit
     * @param today what today is for each org unit
     * @param written the units that the request writes itself. They get no propagated change.
     */
    DerivedPlaces(OrgUnitStore units, PlaceStore places, Today today, Set<String> written) {
        this.units = units;
        this.places = places;
        this.today = today;
        this.written = Set.copyOf(written);
    }

    /**
     * Returns the same reads for a request that also writes some other units.
     *
     * @param unitIds the IDs of the units
     * @return the reads
     */
    DerivedPlaces writing(Collection<String> unitIds) {
        Set<String> all = new HashSet<>(written);
        all.addAll(unitIds);
        return new DerivedPlaces(units, places, today, all);
    }

    /**
     * Reads what applies below a unit on the dates that a change of its details changes. The caller writes the
     * details next, and then calls {@link Watch#afterWrite()}. What the unit takes from above stays, so it is read
     * once, and only when a unit lacks a value of its own and of the units above it up to this one.
     *
     * @param unitId the ID of the unit, which exists on every date of the period
     * @param period the dates that the write changes
     * @return what applied before the write
     */
    Watch watchDetails(String unitId, ValidPeriod period) {
        Supplier<NavigableMap<LocalDate, UnitDefaults>> above = once(() -> UnitPlaces.above(units, unitId, period));
        return new Watch(unitId, period, new PlaceChanges.Applying(places.placesBelow(unitId, period), above),
                () -> new PlaceChanges.Applying(places.placesBelow(unitId, period), above));
    }

    /**
     * Reads what applies below a unit on the dates that a move of the unit changes, as {@link #watchDetails} does.
     * A move changes only what the unit takes from above, so the units below are read once.
     *
     * @param unitId the ID of the unit, which exists on every date of the period
     * @param period the dates of the new parent
     * @return what applied before the move
     */
    Watch watchMove(String unitId, ValidPeriod period) {
        List<PlaceSpan> spans = places.placesBelow(unitId, period);
        boolean takes = spans.stream().anyMatch(span -> span.workLocationId() == null || span.costCenterId() == null);
        NavigableMap<LocalDate, UnitDefaults> before = takes ? UnitPlaces.above(units, unitId, period)
                : new TreeMap<>();
        return new Watch(unitId, period, new PlaceChanges.Applying(spans, () -> before),
                () -> new PlaceChanges.Applying(spans, once(() -> UnitPlaces.above(units, unitId, period))));
    }

    private static <T> Supplier<T> once(Supplier<T> read) {
        List<T> value = new ArrayList<>(1);
        return () -> {
            if (value.isEmpty()) {
                value.add(read.get());
            }
            return value.getFirst();
        };
    }

    /**
     * What applied to a unit and to the units below it before a write of the unit.
     */
    final class Watch {

        private final String unitId;
        private final ValidPeriod period;
        private final PlaceChanges.Applying before;
        private final Supplier<PlaceChanges.Applying> after;

        private Watch(String unitId, ValidPeriod period, PlaceChanges.Applying before,
                Supplier<PlaceChanges.Applying> after) {
            this.unitId = unitId;
            this.period = period;
            this.before = before;
            this.after = after;
        }

        /**
         * Compares what applies now with what applied before the write. Counts up the version of each unit and
         * assignment whose read changed, apart from the written unit.
         *
         * @return the units and the assignments whose read changed, units first
         */
        List<PropagatedChange> afterWrite() {
            Map<String, PlaceChanges.Changed> changed = PlaceChanges.compare(before, after.get());
            List<PropagatedChange> propagated = new ArrayList<>();
            changed.forEach((id, change) -> {
                if (!id.equals(unitId) && !written.contains(id)) {
                    propagated.add(new PropagatedChange(change.workLocation().isEmpty()
                            ? DerivedValue.ORG_UNIT_EFFECTIVE_COST_CENTER
                            : DerivedValue.ORG_UNIT_EFFECTIVE_WORK_LOCATION, id));
                }
            });
            places.countUpUnits(propagated.stream().map(PropagatedChange::resourceId).toList());
            Map<String, DerivedValue> readers = new TreeMap<>();
            readers(changed, true).forEach(id -> readers.put(id, DerivedValue.ASSIGNMENT_EFFECTIVE_WORK_LOCATION));
            readers(changed, false).forEach(id -> readers.putIfAbsent(id,
                    DerivedValue.ASSIGNMENT_EFFECTIVE_COST_CENTER));
            places.countUpAssignments(readers.keySet());
            readers.forEach((id, value) -> propagated.add(new PropagatedChange(value, id)));
            return propagated;
        }

        // The assignments with a period that reads one of the values on a date on which it changed.
        private Set<String> readers(Map<String, PlaceChanges.Changed> changed, boolean workLocation) {
            Map<String, List<ValidPeriod>> dates = changed.entrySet().stream()
                    .filter(unit -> !unit.getValue().of(workLocation).isEmpty())
                    .collect(Collectors.toMap(Map.Entry::getKey, unit -> unit.getValue().of(workLocation)));
            if (dates.isEmpty()) {
                return Set.of();
            }
            Set<LocalDate> todays = today.everywhere();
            Set<String> ids = new HashSet<>();
            for (PlaceStore.PlaceRow row : places.rowsTakingPlace(dates.keySet(), workLocation, period)) {
                if (reads(dates.get(row.orgUnitId()), row, todays)) {
                    ids.add(row.assignmentId());
                }
            }
            return ids;
        }

        // A read takes today of the org unit. That date is looked up only when the dates that are today somewhere in
        // the realm do not give one answer.
        private boolean reads(List<ValidPeriod> dates, PlaceStore.PlaceRow row, Set<LocalDate> todays) {
            Set<Boolean> answers = todays.stream()
                    .map(date -> PlaceChanges.reads(dates, row.period(), date))
                    .collect(Collectors.toSet());
            return answers.size() == 1 ? answers.iterator().next()
                    : PlaceChanges.reads(dates, row.period(), today.ofUnit(row.orgUnitId()));
        }
    }
}
