package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.PlaceSpan;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaceChangesTest {

    private static final LocalDate APRIL = LocalDate.parse("2026-04-01");
    private static final LocalDate OCTOBER = LocalDate.parse("2026-10-01");

    private static ValidPeriod from(LocalDate date) {
        return ValidPeriod.startingAt(date);
    }

    private static PlaceSpan span(String unit, ValidPeriod period, String workLocation, String costCenter) {
        return new PlaceSpan(unit, period, workLocation, costCenter);
    }

    // What the top unit takes from above, from each date.
    private static Supplier<NavigableMap<LocalDate, UnitDefaults>> top(Object... datesAndValues) {
        NavigableMap<LocalDate, UnitDefaults> top = new TreeMap<>();
        for (int i = 0; i < datesAndValues.length; i += 2) {
            top.put((LocalDate) datesAndValues[i], (UnitDefaults) datesAndValues[i + 1]);
        }
        return () -> top;
    }

    @Test
    void aUnitThatTakesAValueFromTheTopChangesOnTheDatesOnWhichTheTopChanges() {
        // The top gets another work location from October. The ward takes both values from it, and the clinic has
        // its own work location.
        ValidPeriod untilOctober = new ValidPeriod(APRIL, OCTOBER);
        List<PlaceSpan> before = List.of(span("top", from(APRIL), "tokyo", "cc"),
                span("ward", from(APRIL), "tokyo", "cc"), span("clinic", from(APRIL), "osaka", "cc"));
        List<PlaceSpan> after = List.of(span("top", untilOctober, "tokyo", "cc"), span("top", from(OCTOBER), "kyoto", "cc"),
                span("ward", untilOctober, "tokyo", "cc"), span("ward", from(OCTOBER), "kyoto", "cc"),
                span("clinic", untilOctober, "osaka", "cc"), span("clinic", from(OCTOBER), "osaka", "cc"));
        UnitDefaults tokyo = new UnitDefaults("tokyo", "cc");

        Map<String, PlaceChanges.Changed> changed = PlaceChanges.compare(
                new PlaceChanges.Applying(before, top(APRIL, tokyo)),
                new PlaceChanges.Applying(after, top(APRIL, tokyo, OCTOBER, new UnitDefaults("kyoto", "cc"))));

        assertEquals(Map.of("top", new PlaceChanges.Changed(List.of(from(OCTOBER)), List.of()),
                "ward", new PlaceChanges.Changed(List.of(from(OCTOBER)), List.of())), changed);
    }

    @Test
    void aValueThatNoUnitDownFromTheTopHasComesFromTheTop() {
        // The ward has no cost center down from the top, so it takes the one that applies to the top.
        List<PlaceSpan> spans = List.of(span("ward", from(APRIL), "tokyo", null));

        Map<String, PlaceChanges.Changed> changed = PlaceChanges.compare(
                new PlaceChanges.Applying(spans, top(APRIL, new UnitDefaults("osaka", "cc"))),
                new PlaceChanges.Applying(spans, top(APRIL, new UnitDefaults("osaka", "cc"), OCTOBER,
                        new UnitDefaults("osaka", "cc-new"))));

        assertEquals(Map.of("ward", new PlaceChanges.Changed(List.of(), List.of(from(OCTOBER)))), changed);
    }

    @Test
    void whatTheTopTakesFromAboveIsNotReadWhenEverySpanHasBothValues() {
        Supplier<NavigableMap<LocalDate, UnitDefaults>> unread = () -> {
            throw new AssertionError("read");
        };
        List<PlaceSpan> before = List.of(span("ward", from(APRIL), "tokyo", "cc"));
        List<PlaceSpan> after = List.of(span("ward", from(APRIL), "osaka", "cc"));

        assertEquals(Map.of("ward", new PlaceChanges.Changed(List.of(from(APRIL)), List.of())),
                PlaceChanges.compare(new PlaceChanges.Applying(before, unread),
                        new PlaceChanges.Applying(after, unread)));
    }

    @Test
    void theDatesOfOneValueThatFollowEachOtherBecomeOnePeriod() {
        List<PlaceSpan> before = List.of(span("ward", new ValidPeriod(APRIL, OCTOBER), "tokyo", null),
                span("ward", from(OCTOBER), "tokyo", null));
        List<PlaceSpan> after = List.of(span("ward", from(APRIL), "osaka", null));
        Supplier<NavigableMap<LocalDate, UnitDefaults>> top = top(APRIL, new UnitDefaults(null, "cc"));

        assertEquals(Map.of("ward", new PlaceChanges.Changed(List.of(from(APRIL)), List.of())),
                PlaceChanges.compare(new PlaceChanges.Applying(before, top), new PlaceChanges.Applying(after, top)));
    }

    @Test
    void aUnitThatIsBelowOnlyOnSomeDatesChangesOnThoseDates() {
        Supplier<NavigableMap<LocalDate, UnitDefaults>> top = top(APRIL, UnitDefaults.NONE);
        List<PlaceSpan> before = List.of(span("ward", from(APRIL), "tokyo", "cc"));
        List<PlaceSpan> after = List.of(span("ward", new ValidPeriod(APRIL, OCTOBER), "tokyo", "cc"));

        PlaceChanges.Changed ward = PlaceChanges.compare(new PlaceChanges.Applying(before, top),
                new PlaceChanges.Applying(after, top)).get("ward");

        assertEquals(new PlaceChanges.Changed(List.of(from(OCTOBER)), List.of(from(OCTOBER))), ward);
        assertEquals(List.of(from(OCTOBER)), ward.of(false));
        assertEquals(Map.of(), PlaceChanges.compare(new PlaceChanges.Applying(before, top),
                new PlaceChanges.Applying(before, top)));
    }

    @Test
    void anAssignmentReadsEachPeriodOnItsDateNearestToToday() {
        List<ValidPeriod> changed = List.of(new ValidPeriod(APRIL, OCTOBER));
        LocalDate today = LocalDate.parse("2026-06-15");

        // A period of today, one that ended in May, one that starts in August, and one that ended in March.
        assertTrue(PlaceChanges.reads(changed, from(LocalDate.parse("2020-04-01")), today));
        assertTrue(PlaceChanges.reads(changed, new ValidPeriod(LocalDate.parse("2020-04-01"),
                LocalDate.parse("2026-05-01")), today));
        assertTrue(PlaceChanges.reads(changed, from(LocalDate.parse("2026-08-01")), today));
        assertFalse(PlaceChanges.reads(changed, new ValidPeriod(LocalDate.parse("2020-04-01"), APRIL), today));
        // A period that starts after the change is read on its first date.
        assertFalse(PlaceChanges.reads(changed, from(OCTOBER), today));
    }
}
