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
 */
public record MemberFilter(String hierarchyId, String orgUnitId, boolean includeDescendants, LocalDate date,
        String occupationId, String jobTitleId, EmploymentStatus status, Seconded seconded) {

    /**
     * A filter on whether a person works outside the legal entity that employs them.
     *
     * @param wanted true to keep the seconded people, false to keep everyone else
     * @param hierarchyId the ID of the hierarchy that says what is below a legal entity
     */
    public record Seconded(boolean wanted, String hierarchyId) {
    }
}
