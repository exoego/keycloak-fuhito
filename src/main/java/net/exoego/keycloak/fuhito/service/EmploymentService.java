package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the employments of one realm.
 */
public class EmploymentService {

    private final EmploymentStore store;
    private final AssignmentStore assignments;
    private final RefItemStore refItems;
    private final UserDirectory users;
    private final ReportingLineStore reportingLines;

    /**
     * Creates the service.
     *
     * @param store the storage of employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param users the users of the realm
     * @param reportingLines the storage of reporting lines of the realm
     */
    public EmploymentService(EmploymentStore store, AssignmentStore assignments, RefItemStore refItems,
            UserDirectory users, ReportingLineStore reportingLines) {
        this.reportingLines = reportingLines;
        this.store = store;
        this.assignments = assignments;
        this.refItems = refItems;
        this.users = users;
    }

    /**
     * Starts an employment of a user.
     * A user can have several employments. A new one after an old one ended is a rehire.
     * Two employments of one user may overlap, for a person with two contracts.
     *
     * @param request the values of the request
     * @return the new employment with its first terms
     * @throws FuhitoException when a value is bad, the user does not exist, or another user has the staff number
     */
    public EmploymentView start(NewEmployment request) {
        Checks.required("body", request);
        String userId = Checks.required("userId", request.userId());
        LocalDate from = Checks.date("validFrom", request.validFrom());
        ValidPeriod period = Checks.period(from, Checks.optionalDate("validTo", request.validTo()));
        String staffNumber = Checks.optionalCode("staffNumber", request.staffNumber());
        TermsChange firstTerms = new TermsChange(request.validFrom(), request.employmentType(), request.grade(),
                request.status(), request.workPercent(), request.attributes());
        EmploymentTerms terms = terms(newId(), null, period, firstTerms, null);
        if (!users.exists(userId)) {
            throw FuhitoException.invalid("employment.user_unknown", Map.of("userId", userId));
        }
        if (staffNumber != null
                && store.findByStaffNumber(staffNumber).stream().anyMatch(e -> !e.userId().equals(userId))) {
            throw FuhitoException.conflict("employment.staff_number_taken", Map.of("staffNumber", staffNumber));
        }

        Employment employment = new Employment(newId(), userId, period, staffNumber, null, request.externalId(),
                request.source());
        store.insert(employment);
        store.insertTerms(new EmploymentTerms(terms.id(), employment.id(), terms.period(), terms.employmentTypeId(),
                terms.gradeId(), terms.status(), terms.workPercent(), terms.attributes()));
        return get(employment.id());
    }

    /**
     * Replaces the terms of an employment from a date. The employment itself does not change.
     * The change lasts until the next change that is already stored, or until the employment ends.
     * A change on the first date of stored terms corrects those terms.
     * A leave of absence is a change of the status to {@code ON_LEAVE}, and the return is a change back.
     *
     * @param id the ID of the employment
     * @param request the new terms and their first date
     * @return the employment with all its terms
     * @throws FuhitoException when the employment does not exist on the date or a value is bad
     */
    public EmploymentView changeTerms(String id, TermsChange request) {
        Checks.required("body", request);
        LocalDate from = Checks.date("validFrom", request.validFrom());
        get(id);
        EmploymentTerms current = store.terms(id).stream()
                .filter(t -> t.period().contains(from))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("employment.not_valid_on",
                        Map.of("id", id, "date", from.toString())));

        if (current.period().from().equals(from)) {
            store.updateTerms(terms(current.id(), id, current.period(), request, current));
        } else {
            List<ValidPeriod> parts = current.period().splitAt(from);
            // Build the new terms first, so that a bad value fails before anything is written.
            EmploymentTerms next = terms(newId(), id, parts.get(1), request, current);
            store.updateTerms(new EmploymentTerms(current.id(), id, parts.getFirst(), current.employmentTypeId(),
                    current.gradeId(), current.status(), current.workPercent(), current.attributes()));
            store.insertTerms(next);
        }
        return get(id);
    }

    // Terms may keep a reference item that was deactivated after they got it.
    private EmploymentTerms terms(String termsId, String employmentId, ValidPeriod period, TermsChange values,
            EmploymentTerms current) {
        EmploymentStatus status = Checks.optionalEnum("status", EmploymentStatus.class, values.status(),
                EmploymentStatus.ACTIVE);
        RefItem employmentType = usableItem(RefKind.EMPLOYMENT_TYPE, values.employmentType(),
                current == null ? null : current.employmentTypeId());
        RefItem grade = usableItem(RefKind.GRADE, values.grade(), current == null ? null : current.gradeId());
        return new EmploymentTerms(termsId, employmentId, period, idOf(employmentType), idOf(grade), status,
                Checks.optionalPercent("workPercent", values.workPercent()),
                values.attributes() == null ? Map.of() : values.attributes());
    }

    /**
     * Sets the end of an employment.
     * An earlier end is how a person leaves. It also ends the terms and the assignments on that date,
     * and removes stored changes that would start on or after it.
     * A later end extends a fixed-term employment and its last terms. It does not extend assignments.
     *
     * @param id the ID of the employment
     * @param validTo the first date on which the employment no longer holds, as {@code YYYY-MM-DD}
     * @param endReason the code of the end reason, or null
     * @return the employment with all its terms
     * @throws FuhitoException when the date is not after the start of the employment or a value is bad
     */
    public EmploymentView end(String id, String validTo, String endReason) {
        LocalDate to = Checks.date("validTo", validTo);
        Employment employment = get(id).employment();
        if (!to.isAfter(employment.period().from())) {
            throw FuhitoException.notFound("employment.not_valid_on", Map.of("id", id, "date", validTo));
        }
        RefItem reason = usableItem(RefKind.END_REASON, endReason, employment.endReasonId());
        setEnd(employment, to, idOf(reason));
        return get(id);
    }

    /**
     * Removes the end of an employment, for example to take back a resignation.
     * The last terms get no end either. Assignments stay as they are.
     *
     * @param id the ID of the employment
     * @return the employment with all its terms
     * @throws FuhitoException when the employment does not exist
     */
    public EmploymentView removeEnd(String id) {
        setEnd(get(id).employment(), null, null);
        return get(id);
    }

    private void setEnd(Employment employment, LocalDate to, String endReasonId) {
        String id = employment.id();
        store.update(new Employment(id, employment.userId(), employment.period().endingAt(to),
                employment.staffNumber(), endReasonId, employment.externalId(), employment.source()));

        List<EmploymentTerms> terms = store.terms(id);
        if (to != null) {
            terms.stream().filter(t -> !t.period().from().isBefore(to)).forEach(t -> store.deleteTerms(t.id()));
        }
        EmploymentTerms last = terms.stream()
                .filter(t -> to == null || t.period().from().isBefore(to))
                .reduce((first, second) -> second)
                .orElseThrow();
        store.updateTerms(new EmploymentTerms(last.id(), id, last.period().endingAt(to), last.employmentTypeId(),
                last.gradeId(), last.status(), last.workPercent(), last.attributes()));

        if (to == null) {
            return;
        }
        for (Assignment row : assignments.rowsOfEmployment(id)) {
            // A reporting line cannot outlast its assignment.
            reportingLines.truncate(row.assignmentId(), to);
            if (!row.period().from().isBefore(to)) {
                assignments.delete(row.rowId());
            } else if (row.period().endsAfter(to)) {
                assignments.update(row.withPeriod(row.period().endingAt(to)));
            }
        }
    }

    /**
     * Returns an employment with all its terms over time.
     *
     * @param id the ID
     * @return the employment
     * @throws FuhitoException when the employment does not exist
     */
    public EmploymentView get(String id) {
        Employment employment = store.find(id)
                .orElseThrow(() -> FuhitoException.notFound("employment.not_found", Map.of("id", id)));
        return new EmploymentView(employment, codeOf(employment.endReasonId()), store.terms(id).stream()
                .map(t -> new EmploymentView.TermsView(t, codeOf(t.employmentTypeId()), codeOf(t.gradeId())))
                .toList());
    }

    private RefItem usableItem(RefKind kind, String code, String currentId) {
        if (code == null) {
            return null;
        }
        return refItems.findByCode(kind, code)
                .filter(item -> item.active() || item.id().equals(currentId))
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", kind.name(), "code", code)));
    }

    private String codeOf(String refItemId) {
        return Optional.ofNullable(refItemId).flatMap(refItems::find).map(RefItem::code).orElse(null);
    }

    private static String idOf(RefItem item) {
        return item == null ? null : item.id();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
