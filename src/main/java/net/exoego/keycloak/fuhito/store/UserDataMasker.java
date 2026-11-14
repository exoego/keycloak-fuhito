package net.exoego.keycloak.fuhito.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Removes what identifies a person from the stored data about one user, and keeps the records.
 */
public interface UserDataMasker {

    /**
     * Tells when the data of a person was masked.
     *
     * @param personId the ID of the person
     * @return the time of the masking, or empty when the data was never masked
     */
    Optional<Instant> maskedAt(String personId);

    /**
     * Clears the staff number, the numbers of qualifications, the external IDs, and the free attributes of the
     * employments, assignments, and qualifications of a person, and the same fields in the change log entries
     * about them. Every ID, the org units, the job titles, the occupations, and the dates stay.
     *
     * @param personId the ID of the person
     * @param now the time to record as the time of the masking
     */
    void mask(String personId, Instant now);
}
