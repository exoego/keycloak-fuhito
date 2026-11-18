package net.exoego.keycloak.fuhito.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

import net.exoego.keycloak.fuhito.store.JobRunStore.JobRun;

/**
 * Decides when the timer rebuilds the projection of a realm.
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
}
