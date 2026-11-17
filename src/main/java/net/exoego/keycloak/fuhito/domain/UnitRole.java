package net.exoego.keycloak.fuhito.domain;

/**
 * A role that an employment holds over an org unit for a period, without being a member of it.
 * Examples are the HR contact of a division and the approver of expenses of a department.
 *
 * @param id the ID
 * @param employmentId the ID of the employment that holds the role
 * @param personId the ID of the person of the employment
 * @param userId the ID of the Keycloak user of the person, or null
 * @param orgUnitId the ID of the org unit
 * @param roleId the ID of the reference item of the kind {@code UNIT_ROLE}
 * @param includesDescendants true when the role also covers every unit below in the default hierarchy
 * @param period the dates when the role holds
 * @param version the number that each write of the resource counts up. A new resource has 1.
 */
public record UnitRole(String id, String employmentId, String personId, String userId, String orgUnitId,
        String roleId, boolean includesDescendants, ValidPeriod period, long version) {

    /**
     * Returns the same role for another period.
     *
     * @param newPeriod the period
     * @return the role
     */
    public UnitRole withPeriod(ValidPeriod newPeriod) {
        return new UnitRole(id, employmentId, personId, userId, orgUnitId, roleId, includesDescendants, newPeriod,
                version);
    }
}
