package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidPeriodTest {

    private static LocalDate day(int dayOfApril) {
        return LocalDate.of(2026, 4, dayOfApril);
    }

    private static ValidPeriod period(int from, int to) {
        return new ValidPeriod(day(from), day(to));
    }

    private static ValidPeriod openFrom(int from) {
        return ValidPeriod.startingAt(day(from));
    }

    @Test
    void rejectsAMissingStart() {
        assertThrows(NullPointerException.class, () -> new ValidPeriod(null, day(2)));
    }

    @Test
    void rejectsAnEmptyOrReversedRange() {
        assertThrows(IllegalArgumentException.class, () -> period(5, 5));
        assertThrows(IllegalArgumentException.class, () -> period(5, 4));
    }

    @Test
    void acceptsASingleDay() {
        assertEquals(day(6), period(5, 6).to());
    }

    @Test
    void knowsWhetherItHasAnEnd() {
        assertTrue(openFrom(5).isOpenEnded());
        assertNull(openFrom(5).to());
        assertFalse(period(5, 10).isOpenEnded());
    }

    @Test
    void containsItsStartAndNotItsEnd() {
        ValidPeriod p = period(5, 10);
        assertFalse(p.contains(day(4)));
        assertTrue(p.contains(day(5)));
        assertTrue(p.contains(day(9)));
        assertFalse(p.contains(day(10)));
    }

    @Test
    void containsEveryLaterDateWhenOpenEnded() {
        assertFalse(openFrom(5).contains(day(4)));
        assertTrue(openFrom(5).contains(LocalDate.of(2999, 1, 1)));
    }

    @Test
    void adjacentPeriodsDoNotOverlap() {
        assertFalse(period(5, 10).overlaps(period(10, 15)));
        assertFalse(period(10, 15).overlaps(period(5, 10)));
    }

    @Test
    void periodsThatShareADateOverlap() {
        assertTrue(period(5, 10).overlaps(period(9, 15)));
        assertTrue(period(9, 15).overlaps(period(5, 10)));
        assertTrue(period(5, 10).overlaps(period(6, 7)));
    }

    @Test
    void openEndedPeriodsOverlapEverythingLater() {
        assertTrue(openFrom(5).overlaps(period(20, 25)));
        assertTrue(period(20, 25).overlaps(openFrom(5)));
        assertTrue(openFrom(5).overlaps(openFrom(20)));
        assertFalse(openFrom(10).overlaps(period(5, 10)));
        assertFalse(period(5, 10).overlaps(openFrom(10)));
    }

    @Test
    void enclosesAPeriodInside() {
        ValidPeriod p = period(5, 10);
        assertTrue(p.encloses(period(5, 10)));
        assertTrue(p.encloses(period(6, 9)));
        assertFalse(p.encloses(period(4, 9)));
        assertFalse(p.encloses(period(6, 11)));
        assertFalse(p.encloses(openFrom(6)));
    }

    @Test
    void openEndedPeriodEnclosesEverythingLater() {
        assertTrue(openFrom(5).encloses(period(5, 10)));
        assertTrue(openFrom(5).encloses(openFrom(6)));
        assertFalse(openFrom(5).encloses(openFrom(4)));
    }

    @Test
    void changesTheEnd() {
        assertEquals(period(5, 8), period(5, 10).endingAt(day(8)));
        assertEquals(period(5, 8), openFrom(5).endingAt(day(8)));
        assertEquals(openFrom(5), period(5, 10).endingAt(null));
        assertThrows(IllegalArgumentException.class, () -> period(5, 10).endingAt(day(5)));
    }

    @Test
    void splitsInTwo() {
        assertEquals(List.of(period(5, 7), period(7, 10)), period(5, 10).splitAt(day(7)));
        assertEquals(List.of(period(5, 7), openFrom(7)), openFrom(5).splitAt(day(7)));
    }

    @Test
    void doesNotSplitAtItsEdgesOrOutside() {
        ValidPeriod p = period(5, 10);
        assertThrows(IllegalArgumentException.class, () -> p.splitAt(day(4)));
        assertThrows(IllegalArgumentException.class, () -> p.splitAt(day(5)));
        assertThrows(IllegalArgumentException.class, () -> p.splitAt(day(10)));
        assertThrows(IllegalArgumentException.class, () -> p.splitAt(day(11)));
    }
}
