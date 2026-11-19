package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemPeriodTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    private static Localized<RefItemName> english(String name) {
        return Localized.of("en", new RefItemName(name));
    }

    private static RefItemPeriod names(LocalDate from, LocalDate to, String name) {
        return new RefItemPeriod(name, "item", from, to, english(name));
    }

    @Test
    void rejectsAnEmptyOrReversedPeriod() {
        assertThrows(IllegalArgumentException.class, () -> names(APRIL, APRIL, "a"));
        assertThrows(IllegalArgumentException.class, () -> names(OCTOBER, APRIL, "a"));
    }

    @Test
    void holdsFromItsFirstDateUntilItsEnd() {
        RefItemPeriod period = names(APRIL, OCTOBER, "a");

        assertFalse(period.holdsOn(APRIL.minusDays(1)));
        assertTrue(period.holdsOn(APRIL));
        assertTrue(period.holdsOn(OCTOBER.minusDays(1)));
        assertFalse(period.holdsOn(OCTOBER));
    }

    @Test
    void namesWithNoDatesHoldOnEveryDate() {
        RefItemPeriod always = names(null, null, "a");

        assertTrue(always.holdsOn(LocalDate.of(1900, 1, 1)));
        assertTrue(always.holdsOn(LocalDate.of(2999, 1, 1)));
        assertTrue(names(null, APRIL, "a").holdsOn(APRIL.minusDays(1)));
        assertFalse(names(null, APRIL, "a").holdsOn(APRIL));
    }

    @Test
    void changesTheEndOrTheNamesAndKeepsTheRest() {
        RefItemPeriod period = names(APRIL, null, "a");
        Localized<RefItemName> renamed = new Localized<>("ja", Map.of("ja", new RefItemName("部長"),
                "en", new RefItemName("Manager")));

        assertEquals(new RefItemPeriod("a", "item", APRIL, OCTOBER, english("a")), period.endingAt(OCTOBER));
        assertEquals(new RefItemPeriod("a", "item", APRIL, null, renamed), period.renamed(renamed));
    }

    @Test
    void aDateBeforeTheFirstPeriodGetsTheFirstNames() {
        RefItemPeriod first = names(APRIL, OCTOBER, "a");
        RefItemPeriod second = names(OCTOBER, null, "b");
        List<RefItemPeriod> all = List.of(first, second);

        assertEquals(first, RefItemPeriod.on(all, APRIL.minusYears(10)));
        assertEquals(first, RefItemPeriod.on(all, APRIL));
        assertEquals(second, RefItemPeriod.on(all, OCTOBER));
    }

    @Test
    void anItemTakesTheNamesAndTheirDates() {
        RefItem item = new RefItem("item", RefKind.JOB_TITLE, "chief", english("Chief"), 0, Map.of(), true);
        Localized<RefItemName> head = new Localized<>("ja", Map.of("ja", new RefItemName("部長"),
                "en", new RefItemName("Head")));

        RefItem renamed = item.withNames(new RefItemPeriod("n", "item", APRIL, OCTOBER, head));

        assertEquals(head, renamed.names());
        assertEquals("Head", renamed.names().in(Locale.ENGLISH).name());
        assertEquals(APRIL, renamed.validFrom());
        assertEquals(OCTOBER, renamed.validTo());
        assertEquals("chief", renamed.code());
    }
}
