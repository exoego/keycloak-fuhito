package net.exoego.keycloak.fuhito.service;

/**
 * The work location and the cost center of an org unit or an assignment, as codes.
 *
 * @param workLocation the work location of the record itself, or null
 * @param costCenter the cost center of the record itself, or null
 * @param effectiveWorkLocation the work location that applies, or null
 * @param effectiveCostCenter the cost center that applies, or null
 */
public record LocationAndCost(String workLocation, String costCenter, String effectiveWorkLocation,
        String effectiveCostCenter) {

    /** No values. */
    public static final LocationAndCost NONE = new LocationAndCost(null, null, null, null);
}
