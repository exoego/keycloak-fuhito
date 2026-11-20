package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.Employer;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Asks other rules once for each question, and gives the same answer or the same error again. A list of an import
 * changes no org unit and no reference item, so the answers hold for all of its rows.
 */
final class RememberedEmployers implements Employers {

    private record Question(String employerId, String externalEmployer, LocalDate from) {
    }

    private record Cover(Employer employer, ValidPeriod period) {
    }

    private record Answer(Employer employer, FuhitoException error) {

        static Answer of(Supplier<Employer> ask) {
            try {
                return new Answer(ask.get(), null);
            } catch (FuhitoException e) {
                return new Answer(null, e);
            }
        }

        Employer get() {
            if (error != null) {
                throw error;
            }
            return employer;
        }
    }

    private final Employers employers;
    private final Map<Question, Answer> employerOf = new HashMap<>();
    private final Map<Cover, Answer> covers = new HashMap<>();

    RememberedEmployers(Employers employers) {
        this.employers = employers;
    }

    @Override
    public Employer of(String requestedId, String externalEmployer, LocalDate firstDate) {
        return employerOf.computeIfAbsent(new Question(requestedId, externalEmployer, firstDate),
                question -> Answer.of(() -> employers.of(requestedId, externalEmployer, firstDate))).get();
    }

    @Override
    public void requireCovers(Employer employer, ValidPeriod period) {
        covers.computeIfAbsent(new Cover(employer, period), cover -> Answer.of(() -> {
            employers.requireCovers(employer, period);
            return employer;
        })).get();
    }
}
