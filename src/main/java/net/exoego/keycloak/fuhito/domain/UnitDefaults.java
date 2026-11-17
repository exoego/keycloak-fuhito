package net.exoego.keycloak.fuhito.domain;

/**
 * The work location and the cost center that apply to an org unit on a date. Each comes from the unit, or else
 * from the nearest unit above it in the default hierarchy.
 *
 * @param workLocationId the ID of the work location, or null
 * @param costCenterId the ID of the cost center, or null
 */
public record UnitDefaults(String workLocationId, String costCenterId) {

    /** No work location and no cost center. */
    public static final UnitDefaults NONE = new UnitDefaults(null, null);
}
