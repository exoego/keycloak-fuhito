package net.exoego.keycloak.fuhito.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Removes what identifies a person from the stored data about one user, and keeps the records.
 */
public interface UserDataMasker {

    /**
     * Tells when the data of a user was masked.
     *
     * @param userId the ID of the user
     * @return the time of the masking, or empty when the data was never masked
     */
    Optional<Instant> maskedAt(String userId);

    /**
     * Clears the staff number, the numbers of qualifications, the external IDs, and the free attributes of the
     * employments, assignments, and qualifications of a user, and the same fields in the change log entries
     * about them. Every ID, the org units, the job titles, the occupations, and the dates stay.
     *
     * @param userId the ID of the user
     * @param now the time to record as the time of the masking
     */
    void mask(String userId, Instant now);
}
