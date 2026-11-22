package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.PlaceSpan;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Compares the work location and the cost center that apply to the units below one unit before a write and after
 * it, on every date.
 */
final class PlaceChanges {

    private PlaceChanges() {
    }

    /**
     * What applies to a unit and to each unit below it, as one read gives it.
     *
     * @param spans what each unit takes from the units down from the top unit, the top unit included
     * @param above what the top unit takes from above, by the date from which it applies until the next date. It is
     *         read only when a span lacks a value.
     */
    record Applying(List<PlaceSpan> spans, Supplier<NavigableMap<LocalDate, UnitDefaults>> above) {
    }

    /**
     * The dates on which a value that applies to one unit changed.
     *
     * @param workLocation the dates of another work location, oldest first
     * @param costCenter the dates of another cost center, oldest first
     */
    record Changed(List<ValidPeriod> workLocation, List<ValidPeriod> costCenter) {

        /**
         * Returns the dates of one of the values.
         *
         * @param ofWorkLocation true for the work location, false for the cost center
         * @return the dates, oldest first
         */
        List<ValidPeriod> of(boolean ofWorkLocation) {
            return ofWorkLocation ? workLocation : costCenter;
        }
    }

    /**
     * Finds the units whose values changed on a date.
     *
     * @param before what applied before the write
     * @param after what applies after the write
     * @return the dates of the changes of each unit with a change, by the ID of the unit in order
     */
    static Map<String, Changed> compare(Applying before, Applying after) {
        Map<String, List<PlaceSpan>> was = byUnit(before.spans());
        Map<String, List<PlaceSpan>> now = byUnit(after.spans());
        TreeSet<String> units = new TreeSet<>(was.keySet());
        units.addAll(now.keySet());
        Map<String, Changed> changed = new TreeMap<>();
        for (String unit : units) {
            List<PlaceSpan> old = was.getOrDefault(unit, List.of());
            List<PlaceSpan> current = now.getOrDefault(unit, List.of());
            TreeSet<LocalDate> dates = new TreeSet<>();
            for (List<PlaceSpan> spans : List.of(old, current)) {
                spans.forEach(span -> {
                    dates.add(span.period().from());
                    if (span.period().to() != null) {
                        dates.add(span.period().to());
                    }
                });
            }
            if (Stream.concat(old.stream(), current.stream())
                    .anyMatch(span -> span.workLocationId() == null || span.costCenterId() == null)) {
                dates.addAll(before.above().get().keySet());
                dates.addAll(after.above().get().keySet());
            }
            List<ValidPeriod> workLocation = new ArrayList<>();
            List<ValidPeriod> costCenter = new ArrayList<>();
            for (LocalDate date : dates) {
                UnitDefaults a = valueOn(old, before.above(), date);
                UnitDefaults b = valueOn(current, after.above(), date);
                if (a == null && b == null) {
                    continue;
                }
                LocalDate next = dates.higher(date);
                if (a == null || b == null || !Objects.equals(a.workLocationId(), b.workLocationId())) {
                    add(workLocation, date, next);
                }
                if (a == null || b == null || !Objects.equals(a.costCenterId(), b.costCenterId())) {
                    add(costCenter, date, next);
                }
            }
            if (!workLocation.isEmpty() || !costCenter.isEmpty()) {
                changed.put(unit, new Changed(List.copyOf(workLocation), List.copyOf(costCenter)));
            }
        }
        return changed;
    }

    /**
     * Tells whether the read of a period of an assignment changed. The read takes the value on the date of the
     * period nearest to today.
     *
     * @param changed the dates on which the value of the org unit changed
     * @param row the dates of the period
     * @param today today for the org unit
     * @return true when the date of the read is one of the changed dates
     */
    static boolean reads(List<ValidPeriod> changed, ValidPeriod row, LocalDate today) {
        LocalDate date = row.nearest(today);
        return changed.stream().anyMatch(period -> period.contains(date));
    }

    private static Map<String, List<PlaceSpan>> byUnit(List<PlaceSpan> spans) {
        return spans.stream().collect(Collectors.groupingBy(PlaceSpan::orgUnitId));
    }

    // A unit that is not below on the date has no value. A value that the units down from the top do not give comes
    // from above the top unit.
    private static UnitDefaults valueOn(List<PlaceSpan> spans, Supplier<NavigableMap<LocalDate, UnitDefaults>> above,
            LocalDate date) {
        for (PlaceSpan span : spans) {
            if (span.period().contains(date)) {
                if (span.workLocationId() != null && span.costCenterId() != null) {
                    return new UnitDefaults(span.workLocationId(), span.costCenterId());
                }
                Map.Entry<LocalDate, UnitDefaults> taken = above.get().floorEntry(date);
                UnitDefaults applying = taken == null ? UnitDefaults.NONE : taken.getValue();
                return new UnitDefaults(
                        span.workLocationId() == null ? applying.workLocationId() : span.workLocationId(),
                        span.costCenterId() == null ? applying.costCenterId() : span.costCenterId());
            }
        }
        return null;
    }

    private static void add(List<ValidPeriod> periods, LocalDate from, LocalDate to) {
        if (!periods.isEmpty() && from.equals(periods.getLast().to())) {
            periods.set(periods.size() - 1, periods.getLast().endingAt(to));
        } else {
            periods.add(new ValidPeriod(from, to));
        }
    }
}
