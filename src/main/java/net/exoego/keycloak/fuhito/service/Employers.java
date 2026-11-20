package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.function.Consumer;

import net.exoego.keycloak.fuhito.domain.Employer;
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
     * @param externalEmployer the code of the external employer that the request names, or null when it names none
     * @param firstDate the first date of the employment
     * @return the employer
     * @throws FuhitoException when the request names an org unit and an external employer, the named unit is not a
     *         legal entity on the date, the realm has no active external employer with the code, or the request names
     *         neither and the realm does not have exactly one legal entity on the date
     */
    Employer of(String requestedId, String externalEmployer, LocalDate firstDate);

    /**
     * Requires that an employer exists until the end of a period of an employment. An external employer has no end.
     *
     * @param employer the employer
     * @param period the period of the employment
     * @throws FuhitoException when the employer ends before the period does
     */
    void requireCovers(Employer employer, ValidPeriod period);

    /**
     * Returns the check of {@link #requireCovers} for one employer. It reads the employer once for any number of
     * periods.
     *
     * @param employer the employer
     * @return the check of a period of an employment, which fails as {@link #requireCovers} does
     */
    default Consumer<ValidPeriod> covers(Employer employer) {
        return period -> requireCovers(employer, period);
    }
}
