package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.service.PromotionRequest;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A request to promote a person on a date, and its response.
 */
@Schema(
        name = "Promotion",
        description = "A request to promote a person on a date, and its response. The request names a grade, a "
                + "job title, or a head flag, or several of them.",
        requiredProperties = {"version", "validFrom"})
public record PromotionRepresentation(
        @Schema(description = "The version of the employment that the caller read.", writeOnly = true)
        Long version,
        @Schema(description = "The first date of the new values.", format = "date", writeOnly = true)
        String validFrom,
        @Schema(description = "The code of the new grade. Leave it out to keep the grade.", writeOnly = true)
        String grade,
        @Schema(description = "The code of the new job title. Leave it out to keep the job title.", writeOnly = true)
        String jobTitle,
        @Schema(
                description = "The ID of the assignment that gets the job title and the head flag. The default is "
                        + "the primary assignment of the employment on the date. It must name an assignment of the "
                        + "employment that holds on the date. Any other ID fails with `employment.assignment_not_on`. "
                        + "A request that gives it with no `jobTitle` and no `head` fails with "
                        + "`employment.promotion_assignment_unused`.",
                writeOnly = true)
        String assignmentId,
        @Schema(
                description = "Whether the person leads the org unit from the date. Leave it out to keep the flag.",
                writeOnly = true)
        Boolean head,
        @Schema(description = "The employment with all its terms.", readOnly = true)
        EmploymentRepresentation employment,
        @Schema(
                description = "The assignment with all its details. It is absent when the request named no job "
                        + "title and no head flag.",
                readOnly = true)
        AssignmentRepresentation assignment) implements Versioned {

    PromotionRequest toRequest() {
        return new PromotionRequest(validFrom, grade, jobTitle, assignmentId, head);
    }

    static PromotionRepresentation of(EmploymentRepresentation employment, AssignmentRepresentation assignment) {
        return new PromotionRepresentation(null, null, null, null, null, null, employment, assignment);
    }
}
