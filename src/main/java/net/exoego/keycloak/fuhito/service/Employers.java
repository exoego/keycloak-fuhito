package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Decides who employs a person in a new employment.
 */
public interface Employers {

    /**
     * Returns the employer of an employment that starts on a date.
     *
     * @param requestedId the ID of the org unit that the request names, or null when it names none
     * @param firstDate the first date of the employment
     * @return the ID of the employer
     * @throws FuhitoException when the named unit is not a legal entity on the date, or the request names none and
     *         the realm does not have exactly one legal entity on the date
     */
    String of(String requestedId, LocalDate firstDate);

    /**
     * Requires that an employer exists until the end of a period of an employment.
     *
     * @param employerId the ID of the employer
     * @param period the period of the employment
     * @throws FuhitoException when the employer ends before the period does
     */
    void requireCovers(String employerId, ValidPeriod period);
}
