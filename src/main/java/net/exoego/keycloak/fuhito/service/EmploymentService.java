package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
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
        EmploymentStatus status = Checks.optionalEnum("status", EmploymentStatus.class, request.status(),
                EmploymentStatus.ACTIVE);
        RefItem employmentType = activeItem(RefKind.EMPLOYMENT_TYPE, request.employmentType());
        RefItem grade = activeItem(RefKind.GRADE, request.grade());
        if (!users.exists(userId)) {
            throw FuhitoException.invalid("employment.user_unknown", Map.of("userId", userId));
        }
        if (staffNumber != null
                && store.findByStaffNumber(staffNumber).stream().anyMatch(e -> !e.userId().equals(userId))) {
            throw FuhitoException.conflict("employment.staff_number_taken", Map.of("staffNumber", staffNumber));
        }

        Employment employment = new Employment(newId(), userId, period, staffNumber, null, request.externalId(),
                request.source());
        EmploymentTerms terms = new EmploymentTerms(newId(), employment.id(), period, idOf(employmentType),
                idOf(grade), status, Checks.optionalPercent("workPercent", request.workPercent()),
                request.attributes() == null ? Map.of() : request.attributes());
        store.insert(employment);
        store.insertTerms(terms);
        return get(employment.id());
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

    private RefItem activeItem(RefKind kind, String code) {
        if (code == null) {
            return null;
        }
        return refItems.findByCode(kind, code)
                .filter(RefItem::active)
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
