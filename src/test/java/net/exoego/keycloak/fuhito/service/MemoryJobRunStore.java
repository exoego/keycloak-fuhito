package net.exoego.keycloak.fuhito.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.store.JobRunStore;

/**
 * The checkpoints of the jobs of one realm, in memory. The services keep no runs, so the runs are not kept.
 */
final class MemoryJobRunStore implements JobRunStore {
    final Map<String, Checkpoint> checkpoints = new HashMap<>();

    @Override
    public Optional<JobRun> last(String job) {
        return Optional.empty();
    }

    @Override
    public void started(String job, Instant at) {
    }

    @Override
    public void ended(String job, Instant at, String state, String report, String errorCode) {
    }

    @Override
    public Optional<Checkpoint> checkpoint(String job) {
        return Optional.ofNullable(checkpoints.get(job));
    }

    @Override
    public void saveCheckpoint(String job, Checkpoint checkpoint) {
        checkpoints.put(job, checkpoint);
    }
}
