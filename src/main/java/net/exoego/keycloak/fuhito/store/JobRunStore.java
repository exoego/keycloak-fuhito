package net.exoego.keycloak.fuhito.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage of the last run of each long job of one realm.
 */
public interface JobRunStore {

    /**
     * The last run of a long job, such as a rebuild of the projection.
     *
     * @param job the name of the job
     * @param state {@code RUNNING}, {@code DONE}, or {@code FAILED}
     * @param startedAt when the run started
     * @param endedAt when the run ended, or null while it runs
     * @param report the counts of the report as JSON, or null
     * @param errorCode the code of the error of a failed run, or null
     */
    record JobRun(String job, String state, Instant startedAt, Instant endedAt, String report, String errorCode) {
    }

    /**
     * Returns the last run of a job.
     *
     * @param job the name of the job
     * @return the run, or empty when the job never ran
     */
    Optional<JobRun> last(String job);

    /**
     * Records the start of a run, in place of the run before.
     *
     * @param job the name of the job
     * @param at when it started
     */
    void started(String job, Instant at);

    /**
     * Records the end of the run that started last.
     *
     * @param job the name of the job
     * @param at when it ended
     * @param state {@code DONE} or {@code FAILED}
     * @param report the counts of the report as JSON, or null
     * @param errorCode the code of the error, or null
     */
    void ended(String job, Instant at, String state, String report, String errorCode);
}
