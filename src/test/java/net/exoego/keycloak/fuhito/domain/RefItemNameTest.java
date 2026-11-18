package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemNameTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    private static RefItemName names(LocalDate from, LocalDate to, String name) {
        return new RefItemName(name, "item", from, to, name, Map.of());
    }

    @Test
    void rejectsAnEmptyOrReversedPeriod() {
        assertThrows(IllegalArgumentException.class, () -> names(APRIL, APRIL, "a"));
        assertThrows(IllegalArgumentException.class, () -> names(OCTOBER, APRIL, "a"));
    }

    @Test
    void holdsFromItsFirstDateUntilItsEnd() {
        RefItemName period = names(APRIL, OCTOBER, "a");

        assertFalse(period.holdsOn(APRIL.minusDays(1)));
        assertTrue(period.holdsOn(APRIL));
        assertTrue(period.holdsOn(OCTOBER.minusDays(1)));
        assertFalse(period.holdsOn(OCTOBER));
    }

    @Test
    void namesWithNoDatesHoldOnEveryDate() {
        RefItemName always = names(null, null, "a");

        assertTrue(always.holdsOn(LocalDate.of(1900, 1, 1)));
        assertTrue(always.holdsOn(LocalDate.of(2999, 1, 1)));
        assertTrue(names(null, APRIL, "a").holdsOn(APRIL.minusDays(1)));
        assertFalse(names(null, APRIL, "a").holdsOn(APRIL));
    }

    @Test
    void changesTheEndOrTheNamesAndKeepsTheRest() {
        RefItemName period = names(APRIL, null, "a");

        assertEquals(new RefItemName("a", "item", APRIL, OCTOBER, "a", Map.of()), period.endingAt(OCTOBER));
        assertEquals(new RefItemName("a", "item", APRIL, null, "b", Map.of("en", "B")),
                period.renamed("b", Map.of("en", "B")));
    }

    @Test
    void aDateBeforeTheFirstPeriodGetsTheFirstNames() {
        RefItemName first = names(APRIL, OCTOBER, "a");
        RefItemName second = names(OCTOBER, null, "b");
        List<RefItemName> all = List.of(first, second);

        assertEquals(first, RefItemName.on(all, APRIL.minusYears(10)));
        assertEquals(first, RefItemName.on(all, APRIL));
        assertEquals(second, RefItemName.on(all, OCTOBER));
    }

    @Test
    void anItemTakesTheNamesAndTheirDates() {
        RefItem item = new RefItem("item", RefKind.JOB_TITLE, "chief", "Chief", Map.of(), 0, Map.of(), true);

        RefItem renamed = item.withNames(new RefItemName("n", "item", APRIL, OCTOBER, "Head", Map.of("en", "Head")));

        assertEquals("Head", renamed.name());
        assertEquals(Map.of("en", "Head"), renamed.names());
        assertEquals(APRIL, renamed.validFrom());
        assertEquals(OCTOBER, renamed.validTo());
        assertEquals("chief", renamed.code());
    }
}
