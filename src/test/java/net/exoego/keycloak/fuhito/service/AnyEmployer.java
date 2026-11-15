package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;

import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Accepts every employer, for tests that are not about employers.
 * A request that names none gets the employer {@code employer}.
 */
final class AnyEmployer implements Employers {

    static final String DEFAULT = "employer";

    @Override
    public String of(String requestedId, LocalDate firstDate) {
        return requestedId == null ? DEFAULT : requestedId;
    }

    @Override
    public void requireCovers(String employerId, ValidPeriod period) {
    }
}
