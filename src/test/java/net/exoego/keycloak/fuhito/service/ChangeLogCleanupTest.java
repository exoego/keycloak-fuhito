package net.exoego.keycloak.fuhito.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeLogCleanupTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");

    private final List<Instant> removals = new ArrayList<>();

    private final MemoryChangeLogStore store = new MemoryChangeLogStore() {
        @Override
        public long removePropagated(Instant before) {
            removals.add(before);
            return 7;
        }
    };

    @Test
    void removesThePropagatedChangesOlderThanTheDaysOfTheRealm() {
        assertEquals(7, ChangeLogCleanup.removeOld(store, null, NOW));
        ChangeLogCleanup.removeOld(store, "30", NOW);
        ChangeLogCleanup.removeOld(store, "0", NOW);

        assertEquals(List.of(NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(30)), NOW), removals);
    }

    @Test
    void removesNothingWhenNoEntryCanBeThatOld() {
        assertEquals(0, ChangeLogCleanup.removeOld(store, Integer.toString(Integer.MAX_VALUE), NOW));
        assertEquals(List.of(), removals);
    }

    @Test
    void rejectsDaysThatAreNoWholeNumberOfZeroOrMore() {
        for (String days : List.of("-1", "abc", "", "1.5", "2147483648")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> ChangeLogCleanup.removeOld(store, days,
                    NOW), days);
            assertEquals(List.of("change_log.keep_days_invalid", Map.of("value", days)),
                    List.of(e.code(), e.params()));
        }
        assertEquals(List.of(), removals);
    }
}
