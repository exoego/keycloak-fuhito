package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;

import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Accepts every org unit as a legal entity, for tests that are not about legal entities.
 * A request that names none gets the employer {@code employer}. An external employer is checked as the rule of the
 * extension checks it.
 */
final class AnyEmployer implements Employers {

    static final String DEFAULT = "employer";

    private final Employers externals;

    AnyEmployer() {
        this(new MemoryRefItemStore());
    }

    AnyEmployer(RefItemStore refItems) {
        this.externals = new LegalEntityEmployers(new MemoryOrgUnitStore(), refItems);
    }

    @Override
    public Employer of(String requestedId, String externalEmployer, LocalDate firstDate) {
        if (externalEmployer != null) {
            return externals.of(requestedId, externalEmployer, firstDate);
        }
        return Employer.legalEntity(requestedId == null ? DEFAULT : requestedId);
    }

    @Override
    public void requireCovers(Employer employer, ValidPeriod period) {
    }
}
