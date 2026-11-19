package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * Asks other rules once for each question, and gives the same answer or the same error again. A list of an import
 * changes no org unit, so the answers hold for all of its rows.
 */
final class RememberedEmployers implements Employers {

    private record Question(String employerId, LocalDate from, LocalDate to) {
    }

    private record Answer(String employerId, FuhitoException error) {

        static Answer of(Supplier<String> ask) {
            try {
                return new Answer(ask.get(), null);
            } catch (FuhitoException e) {
                return new Answer(null, e);
            }
        }

        String get() {
            if (error != null) {
                throw error;
            }
            return employerId;
        }
    }

    private final Employers employers;
    private final Map<Question, Answer> employerOf = new HashMap<>();
    private final Map<Question, Answer> covers = new HashMap<>();

    RememberedEmployers(Employers employers) {
        this.employers = employers;
    }

    @Override
    public String of(String requestedId, LocalDate firstDate) {
        return employerOf.computeIfAbsent(new Question(requestedId, firstDate, null),
                question -> Answer.of(() -> employers.of(requestedId, firstDate))).get();
    }

    @Override
    public void requireCovers(String employerId, ValidPeriod period) {
        covers.computeIfAbsent(new Question(employerId, period.from(), period.to()), question -> Answer.of(() -> {
            employers.requireCovers(employerId, period);
            return employerId;
        })).get();
    }
}
