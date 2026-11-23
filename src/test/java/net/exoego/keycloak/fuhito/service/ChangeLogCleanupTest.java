package net.exoego.keycloak.fuhito.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeLogCleanupTest {

    private static final Instant NOW = Instant.parse("2026-11-22T03:00:00Z");

    private final List<Instant> removals = new ArrayList<>();
    private final List<String> steps = new ArrayList<>();
    private boolean locked = true;

    private final MemoryChangeLogStore store = new MemoryChangeLogStore() {
        @Override
        public boolean lockRealmAgainstRemoval() {
            steps.add("lock");
            return locked;
        }

        @Override
        public long removePropagated(Instant before) {
            steps.add("remove");
            removals.add(before);
            return 7;
        }
    };

    private Supplier<String> setting(String days) {
        return () -> {
            steps.add("read");
            return days;
        };
    }

    @Test
    void removesThePropagatedChangesOlderThanTheDaysOfTheRealm() {
        assertEquals(7, ChangeLogCleanup.removeOld(store, setting(null), NOW));
        ChangeLogCleanup.removeOld(store, setting("30"), NOW);
        ChangeLogCleanup.removeOld(store, setting("0"), NOW);

        assertEquals(List.of(NOW.minus(Duration.ofDays(90)), NOW.minus(Duration.ofDays(30)), NOW), removals);
    }

    // The realm cannot be removed and made again while it is locked.
    @Test
    void readsTheSettingWhileTheRealmIsLocked() {
        ChangeLogCleanup.removeOld(store, setting("30"), NOW);

        assertEquals(List.of("lock", "read", "remove"), steps);
    }

    @Test
    void removesNothingWhenNoEntryCanBeThatOld() {
        assertEquals(0, ChangeLogCleanup.removeOld(store, setting(Integer.toString(Integer.MAX_VALUE)), NOW));
        assertEquals(List.of(), removals);
    }

    @Test
    void rejectsDaysThatAreNoWholeNumberOfZeroOrMore() {
        for (String days : List.of("-1", "abc", "", "1.5", "2147483648")) {
            FuhitoException e = assertThrows(FuhitoException.class, () -> ChangeLogCleanup.removeOld(store,
                    setting(days), NOW), days);
            assertEquals(List.of("change_log.keep_days_invalid", Map.of("value", days)),
                    List.of(e.code(), e.params()));
        }
        assertEquals(List.of(), removals);
    }

    @Test
    void aRealmWithNothingToLockLosesNothingButItsBadSettingIsStillRejected() {
        locked = false;

        assertEquals(0, ChangeLogCleanup.removeOld(store, setting("0"), NOW));
        FuhitoException e = assertThrows(FuhitoException.class,
                () -> ChangeLogCleanup.removeOld(store, setting("-1"), NOW));
        assertEquals("change_log.keep_days_invalid", e.code());
        assertEquals(List.of(), removals);
    }
}
