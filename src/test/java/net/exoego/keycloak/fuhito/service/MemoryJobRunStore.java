package net.exoego.keycloak.fuhito.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.store.JobRunStore;

final class MemoryJobRunStore implements JobRunStore {

    private final Map<String, JobRun> runs = new HashMap<>();

    @Override
    public Optional<JobRun> last(String job) {
        return Optional.ofNullable(runs.get(job));
    }

    @Override
    public void started(String job, Instant at) {
        runs.put(job, new JobRun(job, "RUNNING", at, null, null, null));
    }

    @Override
    public void ended(String job, Instant at, String state, String report, String errorCode) {
        runs.computeIfPresent(job, (name, run) -> new JobRun(name, state, run.startedAt(), at, report, errorCode));
    }
}
