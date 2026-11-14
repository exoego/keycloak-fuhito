package net.exoego.keycloak.fuhito.domain;

/**
 * The relationship between a person and the legal entity of the realm, for a period.
 * It also covers people who are not direct employees, such as agency staff and contractors.
 *
 * @param id the ID
 * @param personId the ID of the person
 * @param userId the ID of the Keycloak user that the person is linked to, or null. It is read from the person.
 * @param period the dates of the employment
 * @param staffNumber the number that the legal entity gives to the person, or null
 * @param endReasonId the ID of the end reason, or null
 * @param externalId the ID of the employment in the system it came from, or null
 * @param source the name of that system, or null
 */
public record Employment(String id, String personId, String userId, ValidPeriod period, String staffNumber, String endReasonId,
        String externalId, String source) {
}
