package net.exoego.keycloak.fuhito.domain;

/**
 * The relationship between a person and an employer, for a period.
 * It also covers people who are not direct employees, such as agency staff and contractors. Their employer can be
 * outside the realm.
 *
 * @param id the ID
 * @param personId the ID of the person
 * @param userId the ID of the Keycloak user that the person is linked to, or null. It is read from the person.
 * @param employer the legal entity of the realm or the external employer that employs the person. It never changes.
 * @param period the dates of the employment
 * @param staffNumber the number that the employer gives to the person, or null
 * @param endReasonId the ID of the end reason, or null
 * @param source the name of the system that made the employment, or null. Its IDs in each system are stored
 *        apart.
 * @param continuesEmploymentId the ID of the employment that this one continues after a transfer to another
 *        employer, or null
 * @param version the number that each write of the resource counts up. A new resource has 1.
 */
public record Employment(String id, String personId, String userId, Employer employer, ValidPeriod period,
        String staffNumber, String endReasonId, String source, String continuesEmploymentId, long version) {

    /**
     * Tells whether a person of this employment works in an org unit as a secondment. That is when the unit belongs
     * to another legal entity than the employer. An employment with an external employer is never a secondment.
     *
     * @param legalEntityId the ID of the legal entity that the org unit belongs to on the date, or null when it
     *        belongs to none
     * @return true for a secondment
     */
    public boolean secondedTo(String legalEntityId) {
        String own = employer.legalEntityId();
        return own != null && legalEntityId != null && !own.equals(legalEntityId);
    }
}
