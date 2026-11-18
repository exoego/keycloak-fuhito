package net.exoego.keycloak.fuhito.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.JobRunStore.JobRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real time is 01:00 on April 1 in Tokyo. It is 12:00 on March 31 in New York.
 */
class DailyProjectionTest {

    private static final Clock BASE = Clock.fixed(Instant.parse("2026-03-31T16:00:00Z"), ZoneOffset.UTC);
    private static final String REALM = "7d3a4c4e-0b6f-4a52-9a3e-1f0c5c2b8e11";
    private static final Duration DELAY = Duration.ofSeconds(452);

    private final MemoryOrgUnitStore store = new MemoryOrgUnitStore();
    private final OrgUnitService orgUnits = new OrgUnitService(store, new MemoryExternalIdStore(),
            new MemoryAssignmentStore(), new MemoryRefItemStore(), Today.of(BASE));
    private final MemoryJobRunStore runs = new MemoryJobRunStore();

    // The lock of the realm. A call of the API or another node may hold it.
    private static final class Lock implements ProjectionService.Work {
        private boolean heldByAnother;
        private boolean held;

        @Override
        public <T> T inNewTransaction(Function<ProjectionService.Parts, T> piece) {
            throw new UnsupportedOperationException("The tests rebuild nothing.");
        }

        @Override
        public <T> T alone(Supplier<T> run) {
            if (heldByAnother) {
                throw FuhitoException.conflict("projection.running", Map.of());
            }
            held = true;
            try {
                return run.get();
            } finally {
                held = false;
            }
        }
    }

    private static Instant tokyo(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+09:00").toInstant();
    }

    private static Optional<JobRun> done(Instant startedAt) {
        return Optional.of(new JobRun(DailyProjection.JOB, "DONE", startedAt, startedAt.plusSeconds(40), null, null));
    }

    private static Optional<JobRun> failed(Instant endedAt) {
        return Optional.of(new JobRun(DailyProjection.JOB, "FAILED", endedAt.minusSeconds(40), endedAt, null,
                "projection.duplicate_name"));
    }

    private Instant next(String timeZone, String dayStart, Optional<JobRun> last) {
        return DailyProjection.next(REALM, last,
                new UnitDays(store, BusinessDay.rule(timeZone, dayStart), BASE));
    }

    private String unit(String name, String parentId) {
        return orgUnits.create(new NewOrgUnit(null, name, null, null, null, null, null, parentId, "2020-04-01", null,
                null, null, null)).unit().id();
    }

    // A run of the timer that starts now and takes 40 seconds.
    private Optional<String> timerRun(Lock lock) {
        return DailyProjection.rebuild(lock, work -> work.alone(() -> "report"),
                error -> DailyProjection.record(runs, BASE.instant(), BASE.instant().plusSeconds(40), error));
    }

    @Test
    void theDelayComesFromTheRealmIdAndStaysUnderFifteenMinutes() {
        assertEquals(DELAY, DailyProjection.delay(REALM));
        assertEquals(Duration.ofSeconds(97), DailyProjection.delay("a"));
        Set<Duration> delays = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Duration delay = DailyProjection.delay(
                    UUID.nameUUIDFromBytes(("realm " + i).getBytes(StandardCharsets.UTF_8)).toString());
            assertTrue(!delay.isNegative() && delay.compareTo(Duration.ofMinutes(15)) < 0, delay::toString);
            delays.add(delay);
        }
        // The realms spread over the minutes.
        assertTrue(delays.size() > 500, () -> delays.size() + " delays");
    }

    @Test
    void aRealmThatTheTimerNeverRanIsDueAtOnce() {
        assertTrue(next("Asia/Tokyo", null, Optional.empty()).isBefore(BASE.instant()));
    }

    @Test
    void afterARunTheNextRunIsAtTheNextDayStartPlusTheDelay() {
        assertEquals(tokyo("2026-04-02T00:00:00").plus(DELAY),
                next("Asia/Tokyo", null, done(tokyo("2026-04-01T00:20:00"))));
        // New York is 13 hours behind Tokyo.
        assertEquals(tokyo("2026-04-01T13:00:00").plus(DELAY),
                next("America/New_York", null, done(tokyo("2026-04-01T00:20:00"))));
    }

    @Test
    void theDayStartOfTheRealmMovesTheRun() {
        // At 02:00 it is still March 31 with a day start of 04:00. So the next run is on the same morning.
        assertEquals(tokyo("2026-04-01T04:00:00").plus(DELAY),
                next("Asia/Tokyo", "04:00", done(tokyo("2026-04-01T02:00:00"))));
        assertEquals(tokyo("2026-04-02T04:00:00").plus(DELAY),
                next("Asia/Tokyo", "04:00", done(tokyo("2026-04-01T04:20:00"))));
    }

    @Test
    void aRunBeforeTheDelayIsOverDoesNotCountForThatDayStart() {
        // A run at 00:03 has seen the new date. The rule still runs the realm at the day start plus the delay.
        assertEquals(tokyo("2026-04-01T00:00:00").plus(DELAY),
                next("Asia/Tokyo", null, done(tokyo("2026-04-01T00:03:00"))));
        assertEquals(tokyo("2026-04-02T00:00:00").plus(DELAY),
                next("Asia/Tokyo", null, done(tokyo("2026-04-01T00:00:00").plus(DELAY))));
    }

    @Test
    void aRunThatWasMissedIsDueAtOnce() {
        // The server was down for three days.
        Instant next = next("Asia/Tokyo", null, done(tokyo("2026-03-28T00:20:00")));

        assertEquals(tokyo("2026-03-29T00:00:00").plus(DELAY), next);
        assertTrue(next.isBefore(BASE.instant()));
    }

    @Test
    void aFailedRunRunsAgainFifteenMinutesLater() {
        assertEquals(tokyo("2026-04-01T00:23:00"), next("Asia/Tokyo", null, failed(tokyo("2026-04-01T00:08:00"))));
        // Also when a day start comes first.
        assertEquals(tokyo("2026-04-01T00:10:00"), next("Asia/Tokyo", null, failed(tokyo("2026-03-31T23:55:00"))));
    }

    @Test
    void aUnitWithATimeOfItsOwnRunsTheRealmAtItsDayStartToo() {
        String america = unit("America", null);
        String sales = unit("Sales", america);
        unit("Team", sales);
        Optional<JobRun> last = done(tokyo("2026-04-01T00:20:00"));
        assertEquals(tokyo("2026-04-02T00:00:00").plus(DELAY), next("Asia/Tokyo", null, last));

        orgUnits.setTime(america, "America/New_York", null);
        // Midnight in New York is 13:00 in Tokyo.
        assertEquals(tokyo("2026-04-01T13:00:00").plus(DELAY), next("Asia/Tokyo", null, last));

        // Sales keeps the time zone from above, with a day start of its own. 03:00 in New York is 16:00 in Tokyo.
        // America still starts its day first.
        orgUnits.setTime(sales, null, "03:00");
        assertEquals(tokyo("2026-04-01T13:00:00").plus(DELAY), next("Asia/Tokyo", null, last));
        orgUnits.setTime(america, null, null);
        orgUnits.setTime(sales, "America/New_York", "03:00");
        assertEquals(tokyo("2026-04-01T16:00:00").plus(DELAY), next("Asia/Tokyo", null, last));
    }

    @Test
    void aUnitWithOnlyADayStartUsesTheTimeZoneOfTheRealm() {
        String office = unit("Office", null);
        orgUnits.setTime(office, null, "13:00");

        assertEquals(tokyo("2026-04-01T13:00:00").plus(DELAY),
                next("Asia/Tokyo", null, done(tokyo("2026-04-01T00:20:00"))));
    }

    @Test
    void aRealmWhoseRebuildGoesOnIsDueAgainAtTheNextCheck() {
        // The last run failed 20 minutes ago, so the realm is due.
        DailyProjection.record(runs, BASE.instant().minusSeconds(1240), BASE.instant().minusSeconds(1200),
                "projection.duplicate_name");
        Optional<JobRun> failed = runs.last(DailyProjection.JOB);
        var lock = new Lock();
        lock.heldByAnother = true;

        assertEquals(Optional.empty(), timerRun(lock));
        assertEquals(failed, runs.last(DailyProjection.JOB), "nothing is recorded");
        assertTrue(next("Asia/Tokyo", null, runs.last(DailyProjection.JOB)).isBefore(BASE.instant()),
                "the realm is still due");

        lock.heldByAnother = false;
        assertEquals(Optional.of("report"), timerRun(lock));
        assertEquals("DONE", runs.last(DailyProjection.JOB).orElseThrow().state());
        assertTrue(next("Asia/Tokyo", null, runs.last(DailyProjection.JOB)).isAfter(BASE.instant()),
                "the run of the day is done");
    }

    @Test
    void theRunIsRecordedBeforeTheLockOfTheRealmIsReleased() {
        var lock = new Lock();
        List<String> records = new ArrayList<>();
        Consumer<String> record = error -> records.add(error + (lock.held ? " in the lock" : " after the lock"));

        DailyProjection.rebuild(lock, work -> work.alone(() -> "report"), record);
        assertThrows(FuhitoException.class, () -> DailyProjection.rebuild(lock, work -> work.alone(() -> {
            throw FuhitoException.conflict("projection.duplicate_name", Map.of());
        }), record));
        assertThrows(IllegalStateException.class, () -> DailyProjection.rebuild(lock, work -> work.alone(() -> {
            throw new IllegalStateException("crash");
        }), record));

        assertEquals(List.of("null in the lock", "projection.duplicate_name in the lock", "internal in the lock"),
                records);
    }

    @Test
    void aCrashBeforeTheLockIsRecordedAsAFailure() {
        List<String> records = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> DailyProjection.rebuild(new Lock(), work -> {
            throw new IllegalStateException("no connection");
        }, records::add));

        assertEquals(List.of("internal"), records);
    }
}
