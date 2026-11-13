package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the assignments of one realm.
 */
public class AssignmentService {

    private final AssignmentStore store;
    private final EmploymentStore employments;
    private final OrgUnitStore orgUnits;
    private final RefItemStore refItems;

    /**
     * Creates the service.
     *
     * @param store the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     */
    public AssignmentService(AssignmentStore store, EmploymentStore employments, OrgUnitStore orgUnits,
            RefItemStore refItems) {
        this.store = store;
        this.employments = employments;
        this.orgUnits = orgUnits;
        this.refItems = refItems;
    }

    /**
     * Adds an assignment of an employment to an org unit.
     * One employment can have any number of assignments at the same time, in units under different roots too.
     * With no end date, the assignment lasts as long as the employment.
     *
     * @param request the values of the request
     * @return the new assignment
     * @throws FuhitoException when a value is bad, or the employment or the org unit does not cover the period
     */
    public AssignmentView add(AssignmentValues request) {
        Checks.required("body", request);
        String employmentId = Checks.required("employmentId", request.employmentId());
        String orgUnitId = Checks.required("orgUnitId", request.orgUnitId());
        LocalDate from = Checks.date("validFrom", request.validFrom());
        LocalDate to = Checks.optionalDate("validTo", request.validTo());
        Employment employment = employments.find(employmentId).orElseThrow(
                () -> FuhitoException.invalid("assignment.employment_unknown", Map.of("employmentId", employmentId)));
        ValidPeriod period = Checks.period(from, to == null ? employment.period().to() : to);
        if (!employment.period().encloses(period)) {
            throw FuhitoException.conflict("assignment.outside_employment", Map.of("employmentId", employmentId,
                    "validFrom", period.from().toString()));
        }
        requireOrgUnitCovers(orgUnitId, period);

        String assignmentId = newId();
        Assignment row = row(newId(), assignmentId, employmentId, orgUnitId, period, request, null,
                request.externalId(), request.source());
        claimPrimary(row, request);
        store.insert(row);
        return get(assignmentId);
    }

    /**
     * Replaces the details of an assignment from a date. The assignment keeps its ID.
     * The change lasts until the next change that is already stored, or until the assignment ends.
     * A change on the first date of stored details corrects those details.
     * The employment and the org unit do not change. To move a person, end the assignment and add another.
     *
     * @param id the ID of the assignment
     * @param request the new details and their first date
     * @return the assignment with all its details
     * @throws FuhitoException when the assignment does not exist on the date or a value is bad
     */
    public AssignmentView changeDetails(String id, AssignmentValues request) {
        Checks.required("body", request);
        LocalDate from = Checks.date("validFrom", request.validFrom());
        Assignment current = requireRows(id).stream()
                .filter(r -> r.period().contains(from))
                .findFirst()
                .orElseThrow(() -> FuhitoException.notFound("assignment.not_valid_on",
                        Map.of("id", id, "date", from.toString())));

        if (current.period().from().equals(from)) {
            Assignment corrected = row(current.rowId(), id, current.employmentId(), current.orgUnitId(),
                    current.period(), request, current, current.externalId(), current.source());
            claimPrimary(corrected, request);
            store.update(corrected);
        } else {
            List<ValidPeriod> parts = current.period().splitAt(from);
            // Build the new row first, so that a bad value fails before anything is written.
            Assignment next = row(newId(), id, current.employmentId(), current.orgUnitId(), parts.get(1), request,
                    current, current.externalId(), current.source());
            claimPrimary(next, request);
            store.update(current.withPeriod(parts.get(0)));
            store.insert(next);
        }
        return get(id);
    }

    /**
     * Ends an assignment. Its history stays readable.
     *
     * @param id the ID of the assignment
     * @param validTo the first date on which the assignment no longer holds, as {@code YYYY-MM-DD}
     * @return the assignment with all its details
     * @throws FuhitoException when the date is not inside the last details of the assignment
     */
    public AssignmentView end(String id, String validTo) {
        LocalDate to = Checks.date("validTo", validTo);
        List<Assignment> rows = requireRows(id);
        Assignment last = rows.get(rows.size() - 1);
        if (!to.isAfter(rows.get(0).period().from())) {
            throw FuhitoException.notFound("assignment.not_valid_on", Map.of("id", id, "date", validTo));
        }
        if (!to.isAfter(last.period().from())) {
            throw FuhitoException.conflict("assignment.later_changes_exist", Map.of("id", id, "validTo", validTo));
        }
        if (last.period().to() != null && to.isAfter(last.period().to())) {
            throw FuhitoException.conflict("assignment.already_ended",
                    Map.of("id", id, "validTo", last.period().to().toString()));
        }
        store.update(last.withPeriod(last.period().endingAt(to)));
        return get(id);
    }

    /**
     * Returns an assignment with its details over time.
     *
     * @param id the ID of the assignment
     * @return the assignment
     * @throws FuhitoException when the assignment does not exist
     */
    public AssignmentView get(String id) {
        List<Assignment> rows = requireRows(id);
        String userId = employments.find(rows.get(0).employmentId()).map(Employment::userId).orElse(null);
        ValidPeriod period = new ValidPeriod(rows.get(0).period().from(), rows.get(rows.size() - 1).period().to());
        return new AssignmentView(id, userId, period, rows.stream()
                .map(r -> new AssignmentView.Row(r, codeOf(r.jobTitleId()), codeOf(r.occupationId()),
                        codeOf(r.kindId())))
                .toList());
    }

    // An employment has at most one primary assignment on any date.
    private void claimPrimary(Assignment claimant, AssignmentValues request) {
        if (!claimant.primary()) {
            return;
        }
        List<Assignment> holders = store.rowsOfEmployment(claimant.employmentId()).stream()
                .filter(other -> other.primary() && !other.assignmentId().equals(claimant.assignmentId())
                        && other.period().overlaps(claimant.period()))
                .toList();
        if (holders.isEmpty()) {
            return;
        }
        if (!Boolean.TRUE.equals(request.switchPrimary())) {
            throw FuhitoException.conflict("assignment.primary_exists", Map.of("employmentId",
                    claimant.employmentId(), "assignmentId", holders.get(0).assignmentId(), "validFrom",
                    claimant.period().from().toString()));
        }
        holders.forEach(holder -> releasePrimary(holder, claimant.period()));
    }

    // Splits the holder so that it stays primary before and after the period, and is not primary inside it.
    private void releasePrimary(Assignment holder, ValidPeriod period) {
        ValidPeriod shared = holder.period().intersection(period).orElseThrow();
        boolean startsBefore = holder.period().from().isBefore(shared.from());
        boolean endsAfter = shared.to() != null
                && (holder.period().to() == null || holder.period().to().isAfter(shared.to()));
        if (startsBefore) {
            store.update(holder.withPeriod(new ValidPeriod(holder.period().from(), shared.from())));
            store.insert(holder.part(newId(), shared, false));
        } else {
            store.update(holder.part(holder.rowId(), shared, false));
        }
        if (endsAfter) {
            store.insert(holder.part(newId(), new ValidPeriod(shared.to(), holder.period().to()), true));
        }
    }

    private List<Assignment> requireRows(String id) {
        List<Assignment> rows = store.rows(id);
        if (rows.isEmpty()) {
            throw FuhitoException.notFound("assignment.not_found", Map.of("id", id));
        }
        return rows;
    }

    // The org unit must exist on every date of the assignment. Versions of one unit have no gaps.
    private void requireOrgUnitCovers(String orgUnitId, ValidPeriod period) {
        List<OrgUnitVersion> versions = orgUnits.versions(orgUnitId);
        if (versions.isEmpty()) {
            throw FuhitoException.invalid("assignment.org_unit_unknown", Map.of("orgUnitId", orgUnitId));
        }
        ValidPeriod life = new ValidPeriod(versions.get(0).period().from(),
                versions.get(versions.size() - 1).period().to());
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("assignment.org_unit_not_valid",
                    Map.of("orgUnitId", orgUnitId, "validFrom", period.from().toString()));
        }
    }

    // A row may keep a reference item that was deactivated after the row got it.
    private Assignment row(String rowId, String assignmentId, String employmentId, String orgUnitId,
            ValidPeriod period, AssignmentValues values, Assignment current, String externalId, String source) {
        return new Assignment(rowId, assignmentId, employmentId, orgUnitId, period,
                idOf(RefKind.JOB_TITLE, values.jobTitle(), current == null ? null : current.jobTitleId()),
                idOf(RefKind.OCCUPATION, values.occupation(), current == null ? null : current.occupationId()),
                idOf(RefKind.ASSIGNMENT_KIND, values.kind(), current == null ? null : current.kindId()),
                Boolean.TRUE.equals(values.head()),
                Boolean.TRUE.equals(values.primary()),
                Checks.optionalPercent("allocationPercent", values.allocationPercent()),
                values.displayOrder() == null ? 0 : values.displayOrder(),
                values.attributes() == null ? Map.of() : values.attributes(),
                externalId, source);
    }

    private String idOf(RefKind kind, String code, String currentId) {
        if (code == null) {
            return null;
        }
        return refItems.findByCode(kind, code)
                .filter(item -> item.active() || item.id().equals(currentId))
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", kind.name(), "code", code)));
    }

    private String codeOf(String refItemId) {
        return refItemId == null ? null : refItems.find(refItemId).map(RefItem::code).orElse(null);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
