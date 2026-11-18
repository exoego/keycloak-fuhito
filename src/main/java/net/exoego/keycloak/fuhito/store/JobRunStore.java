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
     * Where the last run of a job that was done started in the change log.
     * A run that fails keeps the checkpoint of the run before it.
     *
     * @param sequence the sequence number of the newest entry of the change log when the run started
     * @param settings what the run depended on outside the change log, as text that only the job reads
     */
    record Checkpoint(long sequence, String settings) {
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

    /**
     * Returns the checkpoint of the last run of a job that was done.
     *
     * @param job the name of the job
     * @return the checkpoint, or empty when no run of the job saved one
     */
    Optional<Checkpoint> checkpoint(String job);

    /**
     * Saves the checkpoint of the run that started last. A new run does not clear it.
     *
     * @param job the name of the job
     * @param checkpoint the checkpoint
     */
    void saveCheckpoint(String job, Checkpoint checkpoint);
}
