package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.UserDataMasker;

/**
 * Masks the personal data of a user who left. The records stay, so that applications that refer to them by ID
 * still find them.
 */
public class UserMaskingService {

    private final UserDataMasker masker;
    private final EmploymentStore employments;
    private final Clock clock;
    private final Clock timestamps;

    /**
     * Creates the service.
     *
     * @param masker the storage operation that masks
     * @param employments the storage of employments of the realm
     * @param clock the clock that decides what "today" is
     * @param timestamps the clock for the time of the masking
     */
    public UserMaskingService(UserDataMasker masker, EmploymentStore employments, Clock clock,
            Clock timestamps) {
        this.timestamps = timestamps;
        this.masker = masker;
        this.employments = employments;
        this.clock = clock;
    }

    /**
     * Masks the data of a person. It cannot be undone.
     * Masking again keeps the first time, and clears what was added since.
     *
     * @param personId the ID of the person
     * @return the time of the first masking
     * @throws FuhitoException when the person has an employment that is open today or starts later
     */
    public Instant mask(String personId) {
        LocalDate today = LocalDate.now(clock);
        boolean active = employments.findByPerson(personId).stream()
                .anyMatch(employment -> employment.period().endsAfter(today));
        if (active) {
            throw FuhitoException.conflict("person.still_employed", Map.of("id", personId));
        }
        Instant first = masker.maskedAt(personId).orElse(Instant.now(timestamps).truncatedTo(ChronoUnit.MILLIS));
        masker.mask(personId, first);
        return first;
    }

    /**
     * Tells when the data of a user was masked.
     *
     * @param personId the ID of the person
     * @return the time of the masking, or empty when the data was never masked
     */
    public Optional<Instant> maskedAt(String personId) {
        return masker.maskedAt(personId);
    }
}
