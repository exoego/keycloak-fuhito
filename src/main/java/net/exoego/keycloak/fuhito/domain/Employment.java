package net.exoego.keycloak.fuhito.domain;

/**
 * The relationship between a person and a legal entity, for a period.
 * It also covers people who are not direct employees, such as agency staff and contractors.
 *
 * @param id the ID
 * @param personId the ID of the person
 * @param userId the ID of the Keycloak user that the person is linked to, or null. It is read from the person.
 * @param employerId the ID of the org unit that employs the person. It is a legal entity, and it never changes.
 *        It is null only for an employment that was stored before employers existed.
 * @param period the dates of the employment
 * @param staffNumber the number that the legal entity gives to the person, or null
 * @param endReasonId the ID of the end reason, or null
 * @param source the name of the system that made the employment, or null. Its IDs in each system are stored
 *        apart.
 * @param continuesEmploymentId the ID of the employment that this one continues after a transfer to another
 *        employer, or null
 * @param version the number that each write of the resource counts up. A new resource has 1.
 */
public record Employment(String id, String personId, String userId, String employerId, ValidPeriod period,
        String staffNumber, String endReasonId, String source, String continuesEmploymentId, long version) {
}
