package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.EmploymentView.TermsView;

/**
 * The start and the end of a leave of absence as operations of their own.
 * They change only the status of an employment, and keep every other value of its terms.
 */
public class LeaveService {

    private final EmploymentService employments;

    /**
     * Creates the service.
     *
     * @param employments the operations on employments of the realm
     */
    public LeaveService(EmploymentService employments) {
        this.employments = employments;
    }

    /**
     * Puts a person on leave from a date. With an end, the status of before comes back on that date.
     *
     * @param employmentId the ID of the employment
     * @param validFrom the first date of the leave, as {@code YYYY-MM-DD}
     * @param validTo the first date after the leave, as {@code YYYY-MM-DD}, or null when it is not known yet
     * @return the employment with all its terms
     * @throws FuhitoException when the employment does not hold on a date, the person is on leave already,
     *         or a change of terms is stored for a later date
     */
    public EmploymentView start(String employmentId, String validFrom, String validTo) {
        LocalDate from = Checks.date("validFrom", validFrom);
        LocalDate to = Checks.optionalDate("validTo", validTo);
        if (to != null) {
            Checks.period(from, to);
        }
        List<TermsView> all = employments.get(employmentId).terms();
        TermsView current = on(all, employmentId, from);
        if (current.terms().status() == EmploymentStatus.ON_LEAVE) {
            throw FuhitoException.conflict("employment.already_on_leave",
                    Map.of("id", employmentId, "date", validFrom));
        }
        // The leave would end without a word on the date of a change that is stored for later.
        TermsView last = all.getLast();
        if (!last.terms().id().equals(current.terms().id())) {
            throw FuhitoException.conflict("employment.terms_change_stored", Map.of("id", employmentId, "date",
                    last.terms().period().from().toString()));
        }
        LocalDate end = current.terms().period().to();
        if (to != null && end != null && to.isAfter(end)) {
            throw FuhitoException.notFound("employment.not_valid_on", Map.of("id", employmentId, "date", validTo));
        }
        employments.changeTerms(employmentId, withStatus(current, validFrom, EmploymentStatus.ON_LEAVE));
        // A leave up to the end of the employment has no day to come back on.
        if (to != null && !to.equals(end)) {
            employments.changeTerms(employmentId, withStatus(current, validTo, current.terms().status()));
        }
        return employments.get(employmentId);
    }

    /**
     * Ends a leave on a date. The person is active from that date.
     * A return that was stored for a later date stays, and changes nothing more.
     *
     * @param employmentId the ID of the employment
     * @param validFrom the first date back at work, as {@code YYYY-MM-DD}
     * @return the employment with all its terms
     * @throws FuhitoException when the employment does not hold on the date or the person is not on leave
     */
    public EmploymentView end(String employmentId, String validFrom) {
        LocalDate from = Checks.date("validFrom", validFrom);
        TermsView current = on(employments.get(employmentId).terms(), employmentId, from);
        if (current.terms().status() != EmploymentStatus.ON_LEAVE) {
            throw FuhitoException.conflict("employment.not_on_leave", Map.of("id", employmentId, "date", validFrom));
        }
        return employments.changeTerms(employmentId, withStatus(current, validFrom, EmploymentStatus.ACTIVE));
    }

    private static TermsView on(List<TermsView> all, String employmentId, LocalDate date) {
        return all.stream()
                .filter(terms -> terms.terms().period().contains(date))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                        Map.of("id", employmentId, "date", date.toString())));
    }

    private static TermsChange withStatus(TermsView current, String validFrom, EmploymentStatus status) {
        EmploymentTerms terms = current.terms();
        return new TermsChange(validFrom, current.employmentType(), current.grade(), status.name(),
                terms.workPercent(), terms.attributes());
    }
}
