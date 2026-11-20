package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;

/**
 * Which assignments a list of members of an org unit has.
 *
 * @param hierarchyId the ID of the hierarchy that says what is below the unit
 * @param orgUnitId the ID of the org unit
 * @param includeDescendants true to also take the assignments to every unit below
 * @param date the date that the assignments hold on
 * @param occupationId the ID of an occupation to keep, or null for all
 * @param jobTitleId the ID of a job title to keep, or null for all
 * @param status an employment status to keep, or null for all
 * @param seconded which side of a secondment to keep, or null for all
 * @param places the work location and the cost center to keep, or null for all
 */
public record MemberFilter(String hierarchyId, String orgUnitId, boolean includeDescendants, LocalDate date,
        String occupationId, String jobTitleId, EmploymentStatus status, Seconded seconded, Places places) {

    /**
     * A filter on whether a person works outside the legal entity that employs them.
     *
     * @param wanted true to keep the seconded people, false to keep everyone else
     * @param hierarchyId the ID of the default hierarchy, which says which legal entity a unit belongs to
     */
    public record Seconded(boolean wanted, String hierarchyId) {
    }

    /**
     * A filter on the work location and the cost center that apply to an assignment on the date. Each is the one
     * of the assignment, else the one of its org unit, else the one of the nearest unit above it.
     *
     * @param workLocationId the ID of a work location to keep, or null for all
     * @param costCenterId the ID of a cost center to keep, or null for all
     * @param hierarchyId the ID of the default hierarchy, which says which units are above
     */
    public record Places(String workLocationId, String costCenterId, String hierarchyId) {
    }
}
