package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.PersonStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;
import net.exoego.keycloak.fuhito.store.UnitRoleStore;

/**
 * Operations on the employments of one realm.
 */
public class EmploymentService {

    private final AttributeRules attributeRules;
    private final EmploymentStore store;
    private final AssignmentStore assignments;
    private final RefItemStore refItems;
    private final PersonStore people;
    private final Employers employers;
    private final ReportingLineStore reportingLines;
    private final UnitRoleStore unitRoles;

    /**
     * Creates the service.
     *
     * @param store the storage of employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param people the storage of people of the realm
     * @param reportingLines the storage of reporting lines of the realm
     * @param unitRoles the storage of roles over org units of the realm
     * @param employers the rule that decides who employs a person
     */
    public EmploymentService(EmploymentStore store, AssignmentStore assignments, RefItemStore refItems,
            PersonStore people, ReportingLineStore reportingLines, UnitRoleStore unitRoles, Employers employers) {
        this(store, assignments, refItems, people, reportingLines, unitRoles, employers, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param people the storage of people of the realm
     * @param reportingLines the storage of reporting lines of the realm
     * @param unitRoles the storage of roles over org units of the realm
     * @param employers the rule that decides who employs a person
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public EmploymentService(EmploymentStore store, AssignmentStore assignments, RefItemStore refItems,
            PersonStore people, ReportingLineStore reportingLines, UnitRoleStore unitRoles, Employers employers,
            AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.employers = employers;
        this.reportingLines = reportingLines;
        this.unitRoles = unitRoles;
        this.store = store;
        this.assignments = assignments;
        this.refItems = refItems;
        this.people = people;
    }

    /**
     * Starts an employment of a person.
     * A person can have several employments. A new one after an old one ended is a rehire.
     * Two employments of one person may overlap, for a person with two contracts.
     *
     * @param request the values of the request
     * @return the new employment with its first terms
     * @throws FuhitoException when a value is bad, the person does not exist, the employer is not a legal entity,
     *         or another person has the staff number at the same employer
     */
    public EmploymentView start(NewEmployment request) {
        Checks.required("body", request);
        String personId = Checks.required("personId", request.personId());
        LocalDate from = Checks.date("validFrom", request.validFrom());
        ValidPeriod period = Checks.period(from, Checks.optionalDate("validTo", request.validTo()));
        String staffNumber = Checks.optionalCode("staffNumber", request.staffNumber());
        TermsChange firstTerms = new TermsChange(request.validFrom(), request.employmentType(), request.grade(),
                request.status(), request.leaveReason(), request.workPercent(), request.attributes());
        EmploymentTerms terms = terms(newId(), null, period, firstTerms, null);
        Person person = people.find(personId).orElseThrow(
                () -> FuhitoException.invalid("employment.person_unknown", Map.of("personId", personId)));
        String employerId = employers.of(request.employerId(), from);
        employers.requireCovers(employerId, period);
        requireFreeStaffNumber(staffNumber, employerId, personId);

        Employment employment = new Employment(newId(), personId, person.userId(), employerId, period, staffNumber,
                null, request.externalId(), request.source(), null);
        store.insert(employment);
        store.insertTerms(terms.copy(terms.id(), employment.id(), terms.period()));
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
            store.updateTerms(current.withPeriod(parts.getFirst()));
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
        RefItem leaveReason = usableItem(RefKind.LEAVE_REASON, values.leaveReason(),
                current == null ? null : current.leaveReasonId());
        if (leaveReason != null && status != EmploymentStatus.ON_LEAVE) {
            throw FuhitoException.invalid("employment.leave_reason_not_on_leave",
                    Map.of("leaveReason", values.leaveReason(), "status", status.name()));
        }
        return new EmploymentTerms(termsId, employmentId, period, idOf(employmentType), idOf(grade), status,
                idOf(leaveReason), Checks.optionalPercent("workPercent", values.workPercent()),
                attributeRules.check(AttributeDefinition.Target.EMPLOYMENT_TERMS, values.attributes()));
    }

    /**
     * Sets the end of an employment.
     * An earlier end is how a person leaves. It also ends the terms, the assignments, and the roles over org units
     * on that date, and removes stored changes that would start on or after it.
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
     * What a transfer wrote.
     *
     * @param ended the employment at the old employer, with its end
     * @param started the employment at the new employer
     * @param assignmentIds the IDs of the assignments that the new employment got
     * @param reportingLines the reporting lines that the new assignments got
     * @param unitRoles the roles over org units that the new employment got
     */
    public record Transfer(EmploymentView ended, EmploymentView started, List<String> assignmentIds,
            List<ReportingLine> reportingLines, List<UnitRoleService.View> unitRoles) {
    }

    /**
     * Moves a person to another employer on a date, in one write.
     * The employment ends on the date, and a new employment at the new employer starts on the same date.
     * The new employment gets the terms, the assignments, the reporting lines, and the roles over org units of the
     * old one from the date on. These are the ones that hold on the date and the ones that were stored for a later
     * date.
     * It names the old employment as the one that it continues.
     *
     * @param id the ID of the employment to end
     * @param request the new employer, the date, and the new staff number
     * @return the two employments and what the new one got
     * @throws FuhitoException when the employment does not hold on the day before and on the date, the new
     *         employer is not a legal entity or is the old one, or a value is bad
     */
    public Transfer transfer(String id, TransferRequest request) {
        Checks.required("body", request);
        LocalDate date = Checks.date("validFrom", request.validFrom());
        Employment old = get(id).employment();
        if (!old.period().contains(date) || date.equals(old.period().from())) {
            throw FuhitoException.notFound("employment.not_valid_on", Map.of("id", id, "date", request.validFrom()));
        }
        String employerId = employers.of(Checks.required("employerId", request.employerId()), date);
        if (employerId.equals(old.employerId())) {
            throw FuhitoException.invalid("employment.same_employer", Map.of("employerId", employerId));
        }
        String staffNumber = Checks.optionalCode("staffNumber", request.staffNumber());
        requireFreeStaffNumber(staffNumber, employerId, old.personId());

        // These are read before the end, because the end cuts them and removes what would start later.
        List<EmploymentTerms> terms = store.terms(id).stream().filter(t -> t.period().endsAfter(date)).toList();
        List<Assignment> rows = assignments.rowsOfEmployment(id).stream()
                .filter(row -> row.period().endsAfter(date))
                .toList();
        List<UnitRole> roles = unitRoles.findByEmployment(id).stream()
                .filter(role -> role.period().endsAfter(date))
                .toList();
        Map<String, List<ReportingLine>> lines = new LinkedHashMap<>();
        rows.forEach(row -> lines.computeIfAbsent(row.assignmentId(),
                assignmentId -> reportingLines.findByAssignment(assignmentId).stream()
                        .filter(line -> line.period().endsAfter(date))
                        .toList()));

        EmploymentView ended = end(id, request.validFrom(), request.endReason());

        Employment started = new Employment(newId(), old.personId(), old.userId(), employerId,
                from(date, old.period()), staffNumber, null, null, null, id);
        store.insert(started);
        terms.forEach(t -> store.insertTerms(t.copy(newId(), started.id(), from(date, t.period()))));
        // Each old assignment becomes one new assignment, with every period from the date on.
        Map<String, String> newAssignmentIds = new LinkedHashMap<>();
        for (Assignment row : rows) {
            String assignmentId = newAssignmentIds.computeIfAbsent(row.assignmentId(), oldId -> newId());
            // The external ID stays with the old assignment, so that an import still finds that one.
            assignments.insert(new Assignment(newId(), assignmentId, started.id(), row.orgUnitId(),
                    from(date, row.period()), row.jobTitleId(), row.occupationId(), row.kindId(), row.head(),
                    row.primary(), row.allocationPercent(), row.displayOrder(), row.attributes(), null, null,
                    row.assignmentId()));
        }
        List<ReportingLine> newLines = new ArrayList<>();
        lines.forEach((oldAssignmentId, ofAssignment) -> ofAssignment.forEach(line -> {
            ReportingLine next = new ReportingLine(newId(), newAssignmentIds.get(oldAssignmentId),
                    line.managerPersonId(), line.managerUserId(), line.kind(), from(date, line.period()));
            reportingLines.insert(next);
            newLines.add(next);
        }));
        List<UnitRoleService.View> newRoles = new ArrayList<>();
        for (UnitRole role : roles) {
            UnitRole next = new UnitRole(newId(), started.id(), started.personId(), started.userId(),
                    role.orgUnitId(), role.roleId(), role.includesDescendants(), from(date, role.period()));
            unitRoles.insert(next);
            newRoles.add(new UnitRoleService.View(next, refItems.find(role.roleId()).map(RefItem::code).orElse(null)));
        }
        List<String> assignmentIds = List.copyOf(newAssignmentIds.values());
        return new Transfer(ended, get(started.id()), assignmentIds, newLines, newRoles);
    }

    /**
     * Moves every person of one employer to another employer on a date, in one write.
     * It is a transfer of each employment that holds on the day before and on the date.
     * An employment that starts on the date or later is not moved.
     *
     * @param request the two employers, the date, and the staff numbers
     * @return what each transfer wrote, in the order of the first dates of the old employments
     * @throws FuhitoException when a value is bad, a staff number names an employment that is not moved, or one
     *         transfer cannot happen
     */
    public List<Transfer> transferAll(TransferAllRequest request) {
        Checks.required("body", request);
        String fromEmployerId = Checks.required("fromEmployerId", request.fromEmployerId());
        Checks.required("toEmployerId", request.toEmployerId());
        LocalDate date = Checks.date("validFrom", request.validFrom());
        List<Employment> moving = store.findByEmployer(fromEmployerId).stream()
                .filter(e -> e.period().contains(date) && e.period().from().isBefore(date))
                .toList();
        Map<String, String> staffNumbers = request.staffNumbers() == null ? Map.of() : request.staffNumbers();
        Set<String> movingIds = moving.stream().map(Employment::id).collect(Collectors.toSet());
        for (String id : staffNumbers.keySet()) {
            if (!movingIds.contains(id)) {
                throw FuhitoException.invalid("employment.not_transferred",
                        Map.of("id", id, "date", request.validFrom()));
            }
        }
        List<Transfer> transfers = new ArrayList<>();
        for (Employment employment : moving) {
            String staffNumber = staffNumbers.containsKey(employment.id()) ? staffNumbers.get(employment.id())
                    : request.keepStaffNumbers() ? employment.staffNumber() : null;
            transfers.add(transfer(employment.id(), new TransferRequest(request.toEmployerId(), request.validFrom(),
                    staffNumber, request.endReason())));
        }
        return transfers;
    }

    // What holds on the date starts on the date. What was stored for later keeps its own start.
    private static ValidPeriod from(LocalDate date, ValidPeriod period) {
        return new ValidPeriod(period.from().isAfter(date) ? period.from() : date, period.to());
    }

    // Each legal entity gives its own numbers, so two of them may give the same number to two people.
    private void requireFreeStaffNumber(String staffNumber, String employerId, String personId) {
        if (staffNumber != null && store.findByStaffNumber(staffNumber).stream()
                .anyMatch(e -> employerId.equals(e.employerId()) && !e.personId().equals(personId))) {
            throw FuhitoException.conflict("employment.staff_number_taken", Map.of("staffNumber", staffNumber));
        }
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
        employers.requireCovers(employment.employerId(), employment.period().endingAt(to));
        String id = employment.id();
        store.update(new Employment(id, employment.personId(), employment.userId(), employment.employerId(),
                employment.period().endingAt(to),
                employment.staffNumber(), endReasonId, employment.externalId(), employment.source(),
                employment.continuesEmploymentId()));

        List<EmploymentTerms> terms = store.terms(id);
        if (to != null) {
            terms.stream().filter(t -> !t.period().from().isBefore(to)).forEach(t -> store.deleteTerms(t.id()));
        }
        EmploymentTerms last = terms.stream()
                .filter(t -> to == null || t.period().from().isBefore(to))
                .reduce((first, second) -> second)
                .orElseThrow();
        store.updateTerms(last.withPeriod(last.period().endingAt(to)));

        if (to == null) {
            return;
        }
        for (UnitRole role : unitRoles.findByEmployment(id)) {
            if (!role.period().from().isBefore(to)) {
                unitRoles.delete(role.id());
            } else if (role.period().endsAfter(to)) {
                unitRoles.update(role.withPeriod(role.period().endingAt(to)));
            }
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
                .map(t -> new EmploymentView.TermsView(t, codeOf(t.employmentTypeId()), codeOf(t.gradeId()),
                        codeOf(t.leaveReasonId())))
                .toList());
    }

    /**
     * The values of a list of employments. They are not checked yet. A value that is null keeps everything.
     *
     * @param staffNumber the staff number
     * @param employerId the ID of the org unit that employs
     * @param asOf a date as {@code YYYY-MM-DD} on which the employment holds
     */
    public record Search(String staffNumber, String employerId, String asOf) {

        EmploymentStore.Search toStore() {
            return new EmploymentStore.Search(staffNumber, employerId, Checks.optionalDate("asOf", asOf));
        }
    }

    /**
     * Lists a page of the employments that match a search, by ID, each with all its terms.
     *
     * @param search what to keep
     * @param page the part of the list to return
     * @param after the cursor after which the page starts, or null
     * @return the employments of the page
     * @throws FuhitoException when a value is bad, or the request gives a cursor and {@code first}
     */
    public List<EmploymentView> list(Search search, Page page, String after) {
        String afterId = after == null ? null : Cursor.decode(Cursor.EMPLOYMENTS, after).getFirst();
        List<Employment> found = store.search(search.toStore(), afterId, page.first(), page.max());
        Map<String, List<EmploymentTerms>> terms = store.termsOf(found.stream().map(Employment::id).toList())
                .stream()
                .collect(Collectors.groupingBy(EmploymentTerms::employmentId));
        Map<String, String> codes = new HashMap<>();
        return found.stream()
                .map(employment -> new EmploymentView(employment, cachedCode(codes, employment.endReasonId()),
                        terms.getOrDefault(employment.id(), List.of()).stream()
                                .map(t -> new EmploymentView.TermsView(t, cachedCode(codes, t.employmentTypeId()),
                                        cachedCode(codes, t.gradeId()), cachedCode(codes, t.leaveReasonId())))
                                .toList()))
                .toList();
    }

    /**
     * Counts the employments that match a search.
     *
     * @param search what to keep
     * @return the number of employments
     * @throws FuhitoException when a value is bad
     */
    public long count(Search search) {
        return store.count(search.toStore());
    }

    private String cachedCode(Map<String, String> codes, String refItemId) {
        if (refItemId == null) {
            return null;
        }
        return codes.computeIfAbsent(refItemId, this::codeOf);
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
