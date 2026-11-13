package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Operations on the employments of one realm.
 */
public class EmploymentService {

    private final EmploymentStore store;
    private final RefItemStore refItems;
    private final UserDirectory users;

    /**
     * Creates the service.
     *
     * @param store the storage of employments of the realm
     * @param refItems the storage of reference items of the realm
     * @param users the users of the realm
     */
    public EmploymentService(EmploymentStore store, RefItemStore refItems, UserDirectory users) {
        this.store = store;
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
            store.updateTerms(new EmploymentTerms(current.id(), id, parts.get(0), current.employmentTypeId(),
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
        return refItemId == null ? null : refItems.find(refItemId).map(RefItem::code).orElse(null);
    }

    private static String idOf(RefItem item) {
        return item == null ? null : item.id();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
