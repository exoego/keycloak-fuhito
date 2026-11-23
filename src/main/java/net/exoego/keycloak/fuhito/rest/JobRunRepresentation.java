package net.exoego.keycloak.fuhito.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.store.JobRunStore.JobRun;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The last run of a long job.
 */
@Schema(name = "JobRun", description = "The last run of a long job, also one that the timer started.")
public record JobRunRepresentation(
        @Schema(enumeration = {"projection", "sync"})
        String job,
        @Schema(
                description = "`ABANDONED` is a run that still showed `RUNNING` after six hours, for example "
                        + "because its node stopped.",
                enumeration = {"RUNNING", "DONE", "FAILED", "ABANDONED"})
        String state,
        String startedAt,
        @Schema(description = "It is missing while the run goes on.")
        String endedAt,
        @Schema(description = "The counts of the report of a run that is done. A list counts as its size.")
        Map<String, Object> report,
        @Schema(description = "The error code of a failed run, as an error response has it. `internal` when the "
                + "error has no code.")
        String error) {

    // The lock of a run goes away after the same time.
    private static final Duration ABANDONED_AFTER = Duration.ofHours(6);
    private static final ObjectMapper JSON = new ObjectMapper();

    static JobRunRepresentation of(JobRun run, Instant now) {
        boolean abandoned = run.state().equals("RUNNING") && run.startedAt().plus(ABANDONED_AFTER).isBefore(now);
        Map<String, Object> report = run.report() == null ? null
                : Unchecked.io(() -> JSON.readValue(run.report(), new TypeReference<Map<String, Object>>() { }));
        return new JobRunRepresentation(run.job(), abandoned ? "ABANDONED" : run.state(), run.startedAt().toString(),
                Objects.toString(run.endedAt(), null), report, run.errorCode());
    }
}
