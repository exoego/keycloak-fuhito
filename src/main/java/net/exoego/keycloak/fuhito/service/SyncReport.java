package net.exoego.keycloak.fuhito.service;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a sync from user attributes did, or would do.
 *
 * @param users the number of users with a department
 * @param employmentsStarted the number of employments that were started
 * @param assignmentsAdded the number of assignments that were added for users who had none from the sync
 * @param assignmentsMoved the number of users whose assignment ended and started again in another org unit
 * @param assignmentsChanged the number of assignments that got a new job title
 * @param assignmentsEnded the number of assignments that ended because the department was removed
 * @param reportingLinesChanged the number of users whose reporting line was added, replaced, or ended
 * @param unmatched the values that matched nothing
 * @param writes the writes that the sync did, for the event log and the change log
 */
public record SyncReport(int users, int employmentsStarted, int assignmentsAdded, int assignmentsMoved,
        int assignmentsChanged, int assignmentsEnded, int reportingLinesChanged, List<Unmatched> unmatched,
        List<ImportReport.Write> writes) {

    /**
     * A value of a user attribute that matched nothing.
     *
     * @param userId the ID of the user
     * @param field {@code department}, {@code title}, or {@code manager}
     * @param value the value
     * @param reason {@code unknown} when nothing has the value, {@code ambiguous} when more than one org unit
     *        has the name
     */
    @Schema(
            name = "SyncUnmatched",
            description = "A value of a user attribute that matched nothing.",
            requiredProperties = {"userId", "field", "value", "reason"})
    public record Unmatched(
            String userId,
            @Schema(enumeration = {"department", "title", "manager"})
            String field,
            String value,
            @Schema(
                    description = "`unknown` when nothing has the value. `ambiguous` when more than one org unit "
                            + "has the name.",
                    enumeration = {"unknown", "ambiguous"})
            String reason) {
    }
}
