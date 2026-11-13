package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.ReorganizationStep;

/**
 * A request to change many org units on one date, and its response.
 *
 * @param effectiveDate the date of every step, as {@code YYYY-MM-DD}. Only requests have it.
 * @param steps the steps, in the order to apply them. Only requests have them.
 * @param results what each step did, in the same order. Only responses have them.
 */
public record ReorganizationRepresentation(String effectiveDate, List<ReorganizationStep> steps,
        List<Result> results) {

    /**
     * What one step did.
     *
     * @param op the operation of the step
     * @param unit the unit after the step. For an end, it is the unit on its last date.
     */
    public record Result(String op, OrgUnitRepresentation unit) {
    }
}
