package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * A license or certificate that a person holds.
 *
 * @param id the ID
 * @param personId the ID of the person
 * @param userId the ID of the Keycloak user that the person is linked to, or null. It is read from the person.
 * @param kindId the ID of the qualification kind
 * @param number the number of the license or certificate, or null
 * @param period the dates when the qualification is valid
 * @param attributes free values that the realm defines
 * @param externalId the ID of the qualification in the system it came from, or null
 * @param source the name of that system, or null
 */
public record Qualification(String id, String personId, String userId, String kindId, String number, ValidPeriod period,
        Map<String, Object> attributes, String externalId, String source) {
}
