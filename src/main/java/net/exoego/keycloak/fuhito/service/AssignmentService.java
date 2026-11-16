package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
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
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

/**
 * Operations on the assignments of one realm.
 */
public class AssignmentService {

    private final AssignmentStore store;
    private final EmploymentStore employments;
    private final OrgUnitStore orgUnits;
    private final RefItemStore refItems;
    private final ReportingLineStore reportingLines;
    private final AttributeRules attributeRules;

    /**
     * Creates the service.
     *
     * @param store the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param reportingLines the storage of reporting lines of the realm
     */
    public AssignmentService(AssignmentStore store, EmploymentStore employments, OrgUnitStore orgUnits,
            RefItemStore refItems, ReportingLineStore reportingLines) {
        this(store, employments, orgUnits, refItems, reportingLines, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param reportingLines the storage of reporting lines of the realm
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public AssignmentService(AssignmentStore store, EmploymentStore employments, OrgUnitStore orgUnits,
            RefItemStore refItems, ReportingLineStore reportingLines, AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.reportingLines = reportingLines;
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
     * Links an assignment to the one that it continues, as a transfer between employers does.
     *
     * @param id the ID of the assignment
     * @param previousId the ID of the assignment that it continues
     * @return the assignment
     * @throws FuhitoException when an assignment does not exist, or the two are not of the same person
     */
    public AssignmentView continueFrom(String id, String previousId) {
        List<Assignment> rows = store.rows(id);
        List<Assignment> previous = store.rows(previousId);
        if (rows.isEmpty()) {
            throw FuhitoException.notFound("assignment.not_found", Map.of("id", id));
        }
        if (previous.isEmpty()) {
            throw FuhitoException.invalid("assignment.continues_unknown", Map.of("id", previousId));
        }
        Optional<String> person = employments.find(rows.getFirst().employmentId()).map(Employment::personId);
        if (previousId.equals(id) || !person.equals(employments.find(previous.getFirst().employmentId())
                .map(Employment::personId))) {
            throw FuhitoException.invalid("assignment.continues_other", Map.of("id", previousId));
        }
        rows.forEach(row -> store.update(new Assignment(row.rowId(), id, row.employmentId(), row.orgUnitId(),
                row.period(), row.jobTitleId(), row.occupationId(), row.kindId(), row.head(), row.primary(),
                row.allocationPercent(), row.displayOrder(), row.attributes(), row.externalId(), row.source(),
                previousId)));
        return get(id);
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
            store.update(current.withPeriod(parts.getFirst()));
            store.insert(next);
        }
        return get(id);
    }

    /**
     * Sets the end of an assignment. Its history stays readable.
     * The end can move earlier or later. A later end must stay inside the employment and the life of the org unit.
     *
     * @param id the ID of the assignment
     * @param validTo the first date on which the assignment no longer holds, as {@code YYYY-MM-DD}
     * @return the assignment with all its details
     * @throws FuhitoException when the date would cut off a stored change, or the new period is not covered
     */
    public AssignmentView end(String id, String validTo) {
        LocalDate to = Checks.date("validTo", validTo);
        List<Assignment> rows = requireRows(id);
        if (!to.isAfter(rows.getFirst().period().from())) {
            throw FuhitoException.notFound("assignment.not_valid_on", Map.of("id", id, "date", validTo));
        }
        Assignment last = rows.getLast();
        if (!to.isAfter(last.period().from())) {
            throw FuhitoException.conflict("assignment.later_changes_exist", Map.of("id", id, "validTo", validTo));
        }
        setEnd(last, to);
        return get(id);
    }

    /**
     * What a move wrote.
     *
     * @param ended the assignment to the old org unit, with its end
     * @param started the assignment to the new org unit
     */
    public record Move(AssignmentView ended, AssignmentView started) {
    }

    /**
     * Moves a person to another org unit on a date, in one write.
     * The assignment ends on the date, and a new assignment of the same employment to the new org unit starts on
     * the same date. The new one lasts as long as the old one would have, and names the old one as the one that
     * it continues. It keeps the occupation, the kind, the primary flag, the allocation, and the attributes.
     * It keeps the job title unless the request gives one. It does not keep the head flag or the display order,
     * because those belong to the old org unit.
     * The reporting lines of the old assignment end with it. The new assignment has none.
     *
     * @param id the ID of the assignment to end
     * @param request the new org unit, the date, and what changes with the move
     * @return the two assignments
     * @throws FuhitoException when the assignment does not hold on the day before and on the date, has a change
     *         that is stored for the date or later, or the new org unit is the old one or does not cover the
     *         period
     */
    public Move move(String id, MoveRequest request) {
        Checks.required("body", request);
        String orgUnitId = Checks.required("orgUnitId", request.orgUnitId());
        LocalDate date = Checks.date("validFrom", request.validFrom());
        List<Assignment> rows = requireRows(id);
        Assignment last = rows.getLast();
        if (!date.isAfter(rows.getFirst().period().from()) || !last.period().endsAfter(date)) {
            throw FuhitoException.notFound("assignment.not_valid_on", Map.of("id", id, "date", request.validFrom()));
        }
        if (orgUnitId.equals(last.orgUnitId())) {
            throw FuhitoException.invalid("assignment.same_org_unit", Map.of("orgUnitId", orgUnitId));
        }
        ValidPeriod period = new ValidPeriod(date, last.period().to());
        requireOrgUnitCovers(orgUnitId, period);
        String jobTitleId = request.jobTitle() == null ? last.jobTitleId()
                : idOf(RefKind.JOB_TITLE, request.jobTitle(), last.jobTitleId());
        Assignment next = new Assignment(newId(), newId(), last.employmentId(), orgUnitId, period, jobTitleId,
                last.occupationId(), last.kindId(), Boolean.TRUE.equals(request.head()), last.primary(),
                last.allocationPercent(), request.displayOrder() == null ? 0 : request.displayOrder(),
                last.attributes(), null, null, id);
        // The end rejects a change that is stored for the date or later, and ends the reporting lines.
        AssignmentView ended = end(id, request.validFrom());
        store.insert(next);
        return new Move(ended, get(next.assignmentId()));
    }

    /**
     * Removes the end of an assignment. The assignment then lasts as long as its employment.
     *
     * @param id the ID of the assignment
     * @return the assignment with all its details
     * @throws FuhitoException when the org unit does not cover the new period
     */
    public AssignmentView removeEnd(String id) {
        List<Assignment> rows = requireRows(id);
        Assignment last = rows.getLast();
        LocalDate employmentEnd = employments.find(last.employmentId()).orElseThrow().period().to();
        setEnd(last, employmentEnd);
        return get(id);
    }

    private void setEnd(Assignment last, LocalDate to) {
        Assignment changed = last.withPeriod(last.period().endingAt(to));
        boolean longer = last.period().to() != null && (to == null || to.isAfter(last.period().to()));
        if (longer) {
            Employment employment = employments.find(last.employmentId()).orElseThrow();
            if (!employment.period().encloses(changed.period())) {
                throw FuhitoException.conflict("assignment.outside_employment", Map.of("employmentId",
                        employment.id(), "validFrom", changed.period().from().toString()));
            }
            requireOrgUnitCovers(last.orgUnitId(), changed.period());
            if (changed.primary()) {
                claimPrimary(changed, new AssignmentValues(null, null, null, null, null, null, null, null, true,
                        false, null, null, null, null, null));
            }
        }
        store.update(changed);
        if (to != null) {
            // A reporting line cannot outlast its assignment.
            reportingLines.truncate(last.assignmentId(), to);
        }
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
        String userId = employments.find(rows.getFirst().employmentId()).map(Employment::userId).orElse(null);
        ValidPeriod period = new ValidPeriod(rows.getFirst().period().from(), rows.getLast().period().to());
        return new AssignmentView(id, userId, period, rows.getFirst().continuesAssignmentId(), rows.stream()
                .map(r -> new AssignmentView.Row(r, codeOf(r.jobTitleId()), codeOf(r.occupationId()),
                        codeOf(r.kindId())))
                .toList());
    }

    /**
     * Lists a page of the assignments that hold on a date, by ID, each with its details over time.
     *
     * @param date the date
     * @param afterId the ID of the assignment after which the page starts, or null for the start
     * @param max the largest number of assignments to return
     * @return the assignments of the page
     */
    public List<AssignmentView> listOn(LocalDate date, String afterId, int max) {
        List<String> ids = store.searchOn(new AssignmentStore.Search(null, null, null, null, date), afterId, 0, max)
                .stream()
                .map(Assignment::assignmentId)
                .toList();
        Map<String, List<Assignment>> rowsById = store.rowsOf(ids).stream()
                .collect(Collectors.groupingBy(Assignment::assignmentId));
        Map<String, Employment> employmentsById = employments.findAll(rowsById.values().stream()
                        .map(rows -> rows.getFirst().employmentId()).distinct().toList()).stream()
                .collect(Collectors.toMap(Employment::id, Function.identity()));
        Map<String, String> codes = new HashMap<>();
        Function<String, String> code = id -> id == null ? null : codes.computeIfAbsent(id, this::codeOf);
        return ids.stream().map(id -> {
            List<Assignment> rows = rowsById.get(id);
            Employment employment = employmentsById.get(rows.getFirst().employmentId());
            return new AssignmentView(id, employment == null ? null : employment.userId(),
                    new ValidPeriod(rows.getFirst().period().from(), rows.getLast().period().to()),
                    rows.getFirst().continuesAssignmentId(), rows.stream()
                            .map(r -> new AssignmentView.Row(r, code.apply(r.jobTitleId()),
                                    code.apply(r.occupationId()), code.apply(r.kindId())))
                            .toList());
        }).toList();
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
                    claimant.employmentId(), "assignmentId", holders.getFirst().assignmentId(), "validFrom",
                    claimant.period().from().toString()));
        }
        holders.forEach(holder -> releasePrimary(holder, claimant.period()));
    }

    // Splits the holder so that it stays primary before and after the period, and is not primary inside it.
    private void releasePrimary(Assignment holder, ValidPeriod period) {
        ValidPeriod shared = holder.period().intersection(period).orElseThrow();
        boolean startsBefore = holder.period().from().isBefore(shared.from());
        boolean endsAfter = shared.to() != null
                && holder.period().endsAfter(shared.to());
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
        ValidPeriod life = new ValidPeriod(versions.getFirst().period().from(),
                versions.getLast().period().to());
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
                attributeRules.check(AttributeDefinition.Target.ASSIGNMENT, values.attributes()),
                externalId, source, current == null ? null : current.continuesAssignmentId());
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
        return Optional.ofNullable(refItemId).flatMap(refItems::find).map(RefItem::code).orElse(null);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
