package net.exoego.keycloak.fuhito.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ProjectionService.Parts;
import net.exoego.keycloak.fuhito.store.JobRunStore;
import net.exoego.keycloak.fuhito.store.JobRunStore.JobRun;

/**
 * Decides when the timer rebuilds the projection of a realm, and records its runs.
 * The timer runs once a day, after the date changes for the realm or for an org unit with a time of its own.
 * Each realm runs some minutes after the day start, so that the realms do not all start at once.
 */
public final class DailyProjection {

    /**
     * The job that keeps the last run of the timer. A rebuild that a caller asks for is not in it.
     */
    public static final String JOB = "projection-timer";

    /**
     * The wait before a run that failed runs again.
     */
    public static final Duration RETRY = Duration.ofMinutes(15);

    private static final int SPREAD_SECONDS = 15 * 60;

    private static final String RUNNING = "projection.running";

    private static final String INTERNAL = "internal";

    private DailyProjection() {
    }

    /**
     * Returns how long after each day start the realm runs. It is the same every day and on every node.
     *
     * @param realmId the ID of the realm
     * @return a time from 0 up to 15 minutes
     */
    public static Duration delay(String realmId) {
        return Duration.ofSeconds(Math.floorMod(realmId.hashCode(), SPREAD_SECONDS));
    }

    /**
     * Returns when the timer runs the realm next.
     * After a run that was done, it is the first day start plus the delay of the realm that comes after the start
     * of that run.
     * After a run that failed, it is 15 minutes after the failure.
     *
     * @param realmId the ID of the realm
     * @param last the last run of the timer, or empty when the timer never ran for the realm
     * @param days the dates of the realm and of its org units
     * @return the time of the next run. A time in the past means that the run is due, also a run that was missed
     *         while the server was down.
     */
    public static Instant next(String realmId, Optional<JobRun> last, UnitDays days) {
        if (last.isEmpty()) {
            return Instant.EPOCH;
        }
        JobRun run = last.get();
        if (run.state().equals("FAILED")) {
            return run.endedAt().plus(RETRY);
        }
        Duration delay = delay(realmId);
        Instant from = run.startedAt().minus(delay);
        return days.rules().stream()
                .map(rule -> rule.nextDayStart(from))
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .plus(delay);
    }

    /**
     * Rebuilds the projection as the run of the timer, and records the run.
     * After it takes the lock of the realm, it checks again that the realm is due. Another node may have run the
     * realm since the timer read the last run.
     * The run is recorded while the rebuild still holds the lock of the realm. So a node that checks right after
     * the rebuild finds the run, and does not run the realm again.
     * When another rebuild of the realm goes on, from a call or on another node, nothing runs and nothing is
     * recorded. The next check looks at the realm again.
     *
     * @param <T> the type of the report
     * @param work the work of the rebuild. Its {@link ProjectionService.Work#aloneIf} holds the lock of the realm.
     * @param rebuild rebuilds with the work that it gets
     * @param due reads the last run again and says whether the realm is still due
     * @param record records the run. It gets the code of the error, or null when the rebuild is done.
     * @return the report, or empty when another rebuild of the realm goes on or the realm is no longer due
     */
    public static <T> Optional<T> rebuild(ProjectionService.Work work, Function<ProjectionService.Work, T> rebuild,
            BooleanSupplier due, Consumer<String> record) {
        var timer = new TimerWork(work, due, record);
        try {
            return Optional.of(rebuild.apply(timer));
        } catch (NotDue e) {
            return Optional.empty();
        } catch (FuhitoException e) {
            if (!timer.recorded && e.code().equals(RUNNING)) {
                return Optional.empty();
            }
            timer.recordOnce(e.code());
            throw e;
        } catch (RuntimeException e) {
            // A crash before the run started counts as a failure too.
            timer.recordOnce(INTERNAL);
            throw e;
        }
    }

    /**
     * Records a run of the timer in place of the run before.
     *
     * @param runs the runs of the realm
     * @param startedAt when the run started
     * @param endedAt when the run ended
     * @param error the code of the error, or null when the run is done
     */
    public static void record(JobRunStore runs, Instant startedAt, Instant endedAt, String error) {
        runs.started(JOB, startedAt);
        runs.ended(JOB, endedAt, error == null ? "DONE" : "FAILED", null, error);
    }

    // Ends a rebuild that found the realm no longer due. The lock is free again by then.
    private static final class NotDue extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotDue() {
            super(null, null, false, false);
        }
    }

    // Records the run once, inside the lock of the realm when the rebuild got that far.
    private static final class TimerWork implements ProjectionService.Work {

        private final ProjectionService.Work work;
        private final BooleanSupplier due;
        private final Consumer<String> record;
        private boolean recorded;

        TimerWork(ProjectionService.Work work, BooleanSupplier due, Consumer<String> record) {
            this.work = work;
            this.due = due;
            this.record = record;
        }

        @Override
        public <T> T inNewTransaction(Function<Parts, T> piece) {
            return work.inNewTransaction(piece);
        }

        @Override
        public void inParallel(List<Consumer<Parts>> pieces) {
            work.inParallel(pieces);
        }

        // A crash counts as a failure too. Otherwise the realm would run again at the next check.
        @Override
        public <T> T alone(Supplier<T> run) {
            return work.aloneIf(due, () -> {
                String error = INTERNAL;
                try {
                    T result = run.get();
                    error = null;
                    return result;
                } catch (FuhitoException e) {
                    error = e.code();
                    throw e;
                } finally {
                    recordOnce(error);
                }
            }).orElseThrow(NotDue::new);
        }

        void recordOnce(String error) {
            if (!recorded) {
                recorded = true;
                record.accept(error);
            }
        }
    }
}
