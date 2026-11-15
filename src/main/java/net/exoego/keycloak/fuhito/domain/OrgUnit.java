package net.exoego.keycloak.fuhito.domain;

/**
 * The identity of a node in the organization tree. It stays the same when the name, the code, or the parent
 * changes.
 *
 * @param id the ID
 * @param externalId the ID of the unit in the system it came from, or null
 * @param source the name of that system, or null
 * @param keycloakOrganizationId the ID of the linked Keycloak organization, or null
 * @param timeZone the time zone of this unit and the units below it, or null to use the one from above
 * @param dayStart the time of day when the date changes for this unit and the units below it, as {@code HH:mm},
 *        or null to use the one from above
 */
public record OrgUnit(String id, String externalId, String source, String keycloakOrganizationId,
        String timeZone, String dayStart) {

    /**
     * Creates a unit with no time settings of its own.
     *
     * @param id the ID
     * @param externalId the ID of the unit in the system it came from, or null
     * @param source the name of that system, or null
     * @param keycloakOrganizationId the ID of the linked Keycloak organization, or null
     */
    public OrgUnit(String id, String externalId, String source, String keycloakOrganizationId) {
        this(id, externalId, source, keycloakOrganizationId, null, null);
    }
}
