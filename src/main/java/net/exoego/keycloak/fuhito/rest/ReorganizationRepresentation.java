package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.ReorganizationStep;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A request to change many org units on one date, and its response.
 *
 * @param effectiveDate the date of every step, as {@code YYYY-MM-DD}. Only requests have it.
 * @param steps the steps, in the order to apply them. Only requests have them.
 * @param results what each step did, in the same order. Only responses have them.
 */
@Schema(
        name = "Reorganization",
        description = "A request to change many org units on one date, and its response.",
        requiredProperties = {"effectiveDate", "steps"})
public record ReorganizationRepresentation(
        @Schema(description = "The date of every step.", format = "date", writeOnly = true)
        String effectiveDate,
        @Schema(description = "The steps, in the order to apply them.", writeOnly = true)
        List<ReorganizationStep> steps,
        @Schema(description = "What each step did, in the same order.", readOnly = true)
        List<Result> results) {

    /**
     * What one step did.
     *
     * @param op the operation of the step
     * @param unit the unit after the step. For an end, it is the unit on its last date.
     */
    @Schema(name = "ReorganizationResult", description = "What one step did.", requiredProperties = {"op", "unit"})
    public record Result(
            @Schema(enumeration = {"create", "change", "move", "end"})
            String op,
            @Schema(description = "The unit after the step. For an end, it is the unit on its last date.")
            OrgUnitRepresentation unit) {
    }
}
