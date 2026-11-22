package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.EmploymentView.TermsView;
import net.exoego.keycloak.fuhito.store.AssignmentStore;

/**
 * A promotion as one operation. It changes the grade of an employment and the job title of an assignment on
 * one date, and keeps every value that the request does not name.
 */
public class PromotionService {

    /**
     * What a promotion wrote.
     *
     * @param employment the employment with all its terms
     * @param assignment the assignment with all its details, or null when the request named no job title and
     *        no head flag
     */
    public record Promotion(EmploymentView employment, AssignmentView assignment) {
    }

    private final EmploymentService employments;
    private final AssignmentService assignments;
    private final AssignmentStore assignmentStore;

    /**
     * Creates the service.
     *
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     * @param assignmentStore the storage of assignments of the realm
     */
    public PromotionService(EmploymentService employments, AssignmentService assignments,
            AssignmentStore assignmentStore) {
        this.employments = employments;
        this.assignments = assignments;
        this.assignmentStore = assignmentStore;
    }

    /**
     * Promotes a person on a date.
     * The change lasts until the next change that is already stored, as a change of terms or of details does.
     *
     * @param employmentId the ID of the employment
     * @param request the date and what changes
     * @return the employment and the assignment after the promotion
     * @throws FuhitoException when the request changes nothing, names an assignment with no job title and no
     *         head flag for it, the employment or the assignment does not hold on the date, the assignment is of
     *         another employment, or a code is unknown
     */
    public Promotion promote(String employmentId, PromotionRequest request) {
        Checks.required("body", request);
        LocalDate date = Checks.date("validFrom", request.validFrom());
        boolean changesAssignment = request.jobTitle() != null || request.head() != null;
        if (request.assignmentId() != null && !changesAssignment) {
            throw FuhitoException.invalid("employment.promotion_assignment_unused",
                    Map.of("assignmentId", request.assignmentId()));
        }
        if (request.grade() == null && !changesAssignment) {
            throw FuhitoException.invalid("employment.promotion_empty", Map.of());
        }
        TermsView current = employments.get(employmentId).terms().stream()
                .filter(terms -> terms.terms().period().contains(date))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                        Map.of("id", employmentId, "date", request.validFrom())));
        // The assignment is found first, so that a bad request fails before anything is written.
        String assignmentId = changesAssignment ? assignmentOf(employmentId, request, date) : null;

        if (request.grade() != null) {
            EmploymentTerms terms = current.terms();
            employments.changeTerms(employmentId, new TermsChange(request.validFrom(), current.employmentType(),
                    request.grade(), terms.status().name(), current.leaveReason(), terms.workPercent(),
                    Objects.toString(terms.contractEndDate(), null), terms.attributes()));
        }
        AssignmentView assignment = null;
        if (assignmentId != null) {
            AssignmentView.Row details = assignments.get(assignmentId).rows().stream()
                    .filter(row -> row.row().period().contains(date))
                    .findFirst()
                    .orElseThrow();
            Assignment row = details.row();
            assignment = assignments.changeDetails(assignmentId, new AssignmentValues(null, null,
                    request.validFrom(), null, request.jobTitle() == null ? details.jobTitle() : request.jobTitle(),
                    details.occupation(), details.kind(), details.places().workLocation(),
                    details.places().costCenter(), request.head() == null ? row.head() : request.head(),
                    row.primary(), false, row.allocationPercent(), row.displayOrder(), row.attributes(), null,
                    null)).view();
        }
        return new Promotion(employments.get(employmentId), assignment);
    }

    private String assignmentOf(String employmentId, PromotionRequest request, LocalDate date) {
        return assignmentStore.rowsOfEmployment(employmentId).stream()
                .filter(row -> row.period().contains(date))
                .filter(row -> request.assignmentId() == null ? row.primary()
                        : row.assignmentId().equals(request.assignmentId()))
                .map(Assignment::assignmentId)
                .findFirst()
                .orElseThrow(() -> request.assignmentId() == null
                        ? FuhitoException.invalid("employment.no_primary_assignment",
                                Map.of("id", employmentId, "date", request.validFrom()))
                        : FuhitoException.invalid("employment.assignment_not_on",
                                Map.of("id", employmentId, "assignmentId", request.assignmentId(), "date",
                                        request.validFrom())));
    }
}
