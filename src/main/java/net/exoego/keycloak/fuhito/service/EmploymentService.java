package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
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
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
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
    private final ExternalIdStore externalIds;
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
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param people the storage of people of the realm
     * @param reportingLines the storage of reporting lines of the realm
     * @param unitRoles the storage of roles over org units of the realm
     * @param employers the rule that decides who employs a person
     */
    public EmploymentService(EmploymentStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, PersonStore people, ReportingLineStore reportingLines, UnitRoleStore unitRoles,
            Employers employers) {
        this(store, externalIds, assignments, refItems, people, reportingLines, unitRoles, employers,
                AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of employments of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param assignments the storage of assignments of the realm
     * @param refItems the storage of reference items of the realm
     * @param people the storage of people of the realm
     * @param reportingLines the storage of reporting lines of the realm
     * @param unitRoles the storage of roles over org units of the realm
     * @param employers the rule that decides who employs a person
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public EmploymentService(EmploymentStore store, ExternalIdStore externalIds, AssignmentStore assignments,
            RefItemStore refItems, PersonStore people, ReportingLineStore reportingLines, UnitRoleStore unitRoles,
            Employers employers, AttributeRules attributeRules) {
        this.attributeRules = attributeRules;
        this.employers = employers;
        this.reportingLines = reportingLines;
        this.unitRoles = unitRoles;
        this.store = store;
        this.externalIds = externalIds;
        this.assignments = assignments;
        this.refItems = refItems;
        this.people = people;
    }

    /**
     * Returns the same operations that ask the rule for employers once for each question. They suit a list of rows
     * that changes no org unit.
     *
     * @return the operations
     */
    EmploymentService rememberingEmployers() {
        return new EmploymentService(store, externalIds, assignments, refItems, people, reportingLines, unitRoles,
                new RememberedEmployers(employers), attributeRules);
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
        return start(request, null);
    }

    /**
     * Starts an employment of a person that continues another employment of the person, as a transfer between
     * employers does. The link is written with the new employment, and it never changes.
     *
     * @param request the values of the request
     * @param continuesEmploymentId the ID of the employment that the new one continues, or null
     * @return the new employment with its first terms
     * @throws FuhitoException as {@link #start(NewEmployment)} does, and when the employment to continue does not
     *         exist or is of another person
     */
    EmploymentView start(NewEmployment request, String continuesEmploymentId) {
        Checks.required("body", request);
        String personId = Checks.required("personId", request.personId());
        LocalDate from = Checks.date("validFrom", request.validFrom());
        ValidPeriod period = Checks.period(from, Checks.optionalDate("validTo", request.validTo()));
        String staffNumber = Checks.optionalCode("staffNumber", request.staffNumber());
        TermsChange firstTerms = new TermsChange(request.validFrom(), request.employmentType(), request.grade(),
                request.status(), request.leaveReason(), request.workPercent(), request.contractEndDate(),
                request.attributes());
        EmploymentTerms terms = terms(newId(), null, period, firstTerms, null);
        Person person = people.find(personId).orElseThrow(
                () -> FuhitoException.invalid("employment.person_unknown", Map.of("personId", personId)));
        String employerId = employers.of(request.employerId(), from);
        employers.requireCovers(employerId, period);
        requireFreeStaffNumber(staffNumber, employerId, personId);
        ExternalIdWrites writes = new ExternalIdWrites(externalIds, ExternalId.Kind.EMPLOYMENT);
        ExternalId.Pair externalId = writes.check(null, request.source(), request.externalId());
        if (continuesEmploymentId != null) {
            Employment previous = store.find(continuesEmploymentId).orElseThrow(() -> FuhitoException.invalid(
                    "employment.continues_unknown", Map.of("id", continuesEmploymentId)));
            if (!previous.personId().equals(personId)) {
                throw FuhitoException.invalid("employment.continues_other", Map.of("id", continuesEmploymentId));
            }
        }

        Employment employment = new Employment(newId(), personId, person.userId(), employerId, period, staffNumber,
                null, Checks.optionalCode("source", request.source()), continuesEmploymentId, 1);
        store.insert(employment);
        writes.store(employment.id(), externalId);
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
        LocalDate contractEnd = Checks.optionalDate("contractEndDate", values.contractEndDate());
        if (contractEnd != null && contractEnd.isBefore(period.from())) {
            throw FuhitoException.invalid("employment.contract_end_before_terms",
                    Map.of("contractEndDate", contractEnd.toString(), "validFrom", period.from().toString()));
        }
        return new EmploymentTerms(termsId, employmentId, period, idOf(employmentType), idOf(grade), status,
                idOf(leaveReason), Checks.optionalPercent("workPercent", values.workPercent()), contractEnd,
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
        setEnd(List.of(employment), to, idOf(reason));
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
        return transfer(List.of(new Move(old, request.staffNumber())), employerId, date, request.endReason())
                .getFirst();
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
        if (moving.isEmpty()) {
            return List.of();
        }
        List<Move> moves = moving.stream()
                .map(employment -> new Move(employment, staffNumbers.containsKey(employment.id())
                        ? staffNumbers.get(employment.id())
                        : request.keepStaffNumbers() ? employment.staffNumber() : null))
                .toList();
        return transfer(moves, employers.of(request.toEmployerId(), date), date, request.endReason());
    }

    private record Move(Employment employment, String staffNumber) {
    }

    // What the employments have from the date on, read before the end cuts it and removes what would start later.
    // The rows of an assignment are all there, also the ones that end before the date.
    private record Holding(Map<String, List<EmploymentTerms>> terms, Map<String, List<Assignment>> rows,
            Map<String, List<UnitRole>> roles, Map<String, List<ReportingLine>> lines) {
    }

    // What the new employment of a transfer gets. Gone has each old resource that the end removes, with its copy, by
    // kind.
    private record Copy(Employment started, List<EmploymentTerms> terms, List<Assignment> rows,
            List<String> assignmentIds, List<ReportingLine> lines, List<UnitRole> roles,
            Map<ExternalId.Kind, Map<String, String>> gone) {
    }

    // Every check comes before the first write.
    private List<Transfer> transfer(List<Move> moves, String employerId, LocalDate date, String endReason) {
        Optional<RefItem> reason = Optional.ofNullable(endReason)
                .flatMap(code -> refItems.findByCode(RefKind.END_REASON, code));
        List<String> staffNumbers = check(moves, employerId, endReason, reason);
        List<String> ids = moves.stream().map(move -> move.employment().id()).toList();
        Holding holding = holding(ids, date);
        List<Copy> copies = new ArrayList<>();
        for (int i = 0; i < moves.size(); i++) {
            Employment old = moves.get(i).employment();
            copies.add(copy(old, new Employment(newId(), old.personId(), old.userId(), employerId,
                    from(date, old.period()), staffNumbers.get(i), null, null, old.id(), 1), date, holding));
        }

        // The end removes these old resources, so their copies take their external IDs and an import still finds them.
        Map<ExternalId.Kind, Map<String, String>> gone = new EnumMap<>(ExternalId.Kind.class);
        copies.forEach(copy -> copy.gone().forEach((kind, copyIds) ->
                gone.computeIfAbsent(kind, any -> new HashMap<>()).putAll(copyIds)));
        externalIds.moveAll(gone);
        setEnd(moves.stream().map(Move::employment).toList(), date, reason.map(RefItem::id).orElse(null));
        store.insertAll(copies.stream().map(Copy::started).toList());
        copies.forEach(copy -> copy.terms().forEach(store::insertTerms));
        assignments.insertAll(copies.stream().flatMap(copy -> copy.rows().stream()).toList());
        copies.forEach(copy -> copy.lines().forEach(reportingLines::insert));
        unitRoles.insertAll(copies.stream().flatMap(copy -> copy.roles().stream()).toList());

        List<String> both = new ArrayList<>(ids);
        copies.forEach(copy -> both.add(copy.started().id()));
        Map<String, EmploymentView> views = views(store.findAll(both)).stream()
                .collect(Collectors.toMap(view -> view.employment().id(), Function.identity()));
        Map<String, String> codes = new HashMap<>();
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < copies.size(); i++) {
            Copy copy = copies.get(i);
            transfers.add(new Transfer(views.get(ids.get(i)), views.get(copy.started().id()), copy.assignmentIds(),
                    copy.lines(), copy.roles().stream()
                            .map(role -> new UnitRoleService.View(role, cachedCode(codes, role.roleId())))
                            .toList()));
        }
        return transfers;
    }

    // The checks of each move come in the order of the moves, as if they were made one by one.
    private List<String> check(List<Move> moves, String employerId, String endReason, Optional<RefItem> reason) {
        // Each legal entity gives its own numbers, so two of them may give the same number to two people.
        Map<String, Set<String>> holders = new HashMap<>();
        store.findByStaffNumbers(moves.stream().map(Move::staffNumber).filter(Objects::nonNull).toList()).stream()
                .filter(e -> employerId.equals(e.employerId()))
                .forEach(e -> holders.computeIfAbsent(e.staffNumber(), any -> new HashSet<>()).add(e.personId()));
        List<String> staffNumbers = new ArrayList<>();
        for (Move move : moves) {
            Employment old = move.employment();
            if (employerId.equals(old.employerId())) {
                throw FuhitoException.invalid("employment.same_employer", Map.of("employerId", employerId));
            }
            String staffNumber = Checks.optionalCode("staffNumber", move.staffNumber());
            if (staffNumber != null) {
                Set<String> people = holders.computeIfAbsent(staffNumber, any -> new HashSet<>());
                if (people.stream().anyMatch(personId -> !personId.equals(old.personId()))) {
                    throw FuhitoException.conflict("employment.staff_number_taken",
                            Map.of("staffNumber", staffNumber));
                }
                people.add(old.personId());
            }
            staffNumbers.add(staffNumber);
            if (endReason != null) {
                usable(RefKind.END_REASON, endReason, reason, old.endReasonId());
            }
        }
        return staffNumbers;
    }

    private Holding holding(List<String> employmentIds, LocalDate date) {
        Map<String, List<Assignment>> rows = assignments.rowsOfEmployments(employmentIds).stream()
                .collect(Collectors.groupingBy(Assignment::employmentId));
        Set<String> assignmentIds = rows.values().stream()
                .flatMap(List::stream)
                .filter(row -> row.period().endsAfter(date))
                .map(Assignment::assignmentId)
                .collect(Collectors.toSet());
        return new Holding(
                store.termsOf(employmentIds).stream()
                        .filter(t -> t.period().endsAfter(date))
                        .collect(Collectors.groupingBy(EmploymentTerms::employmentId)),
                rows,
                unitRoles.findByEmployments(employmentIds).stream()
                        .filter(role -> role.period().endsAfter(date))
                        .collect(Collectors.groupingBy(UnitRole::employmentId)),
                reportingLines.findByAssignments(assignmentIds).stream()
                        .filter(line -> line.period().endsAfter(date))
                        .collect(Collectors.groupingBy(ReportingLine::assignmentId)));
    }

    private Copy copy(Employment old, Employment started, LocalDate date, Holding holding) {
        List<EmploymentTerms> terms = holding.terms().getOrDefault(old.id(), List.of()).stream()
                .map(t -> t.copy(newId(), started.id(), from(date, t.period())))
                .toList();
        List<Assignment> all = holding.rows().getOrDefault(old.id(), List.of());
        List<Assignment> moved = all.stream().filter(row -> row.period().endsAfter(date)).toList();
        // An assignment that started before the date stays with its history. The end removes any other one.
        Set<String> kept = all.stream()
                .filter(row -> row.period().from().isBefore(date))
                .map(Assignment::assignmentId)
                .collect(Collectors.toSet());
        // Each old assignment becomes one new assignment, with every period from the date on.
        Map<String, String> newAssignmentIds = new LinkedHashMap<>();
        moved.forEach(row -> newAssignmentIds.computeIfAbsent(row.assignmentId(), oldId -> newId()));
        Map<String, String> goneAssignments = new HashMap<>(newAssignmentIds);
        goneAssignments.keySet().removeAll(kept);
        List<Assignment> rows = moved.stream().map(row -> {
            boolean stays = kept.contains(row.assignmentId());
            // A copy continues the old assignment that stays. A copy of one that the end removed continues the
            // copy of what the old one continued, and takes the system that made it.
            String continues = stays ? row.assignmentId()
                    : newAssignmentIds.getOrDefault(row.continuesAssignmentId(), row.continuesAssignmentId());
            return new Assignment(newId(), newAssignmentIds.get(row.assignmentId()), started.id(),
                    row.orgUnitId(), from(date, row.period()), row.jobTitleId(), row.occupationId(), row.kindId(),
                    row.workLocationId(), row.costCenterId(), row.head(), row.primary(), row.allocationPercent(),
                    row.displayOrder(), row.attributes(), stays ? null : row.source(), continues, 1);
        }).toList();
        List<ReportingLine> oldLines = newAssignmentIds.keySet().stream()
                .flatMap(oldId -> holding.lines().getOrDefault(oldId, List.of()).stream())
                .toList();
        List<ReportingLine> lines = oldLines.stream()
                .map(line -> new ReportingLine(newId(), newAssignmentIds.get(line.assignmentId()),
                        line.managerPersonId(), line.managerUserId(), line.kind(), from(date, line.period()), 1))
                .toList();
        List<UnitRole> oldRoles = holding.roles().getOrDefault(old.id(), List.of());
        List<UnitRole> roles = oldRoles.stream()
                .map(role -> new UnitRole(newId(), started.id(), started.personId(), started.userId(),
                        role.orgUnitId(), role.roleId(), role.includesDescendants(), from(date, role.period()), 1))
                .toList();
        return new Copy(started, terms, rows, List.copyOf(newAssignmentIds.values()), lines, roles, Map.of(
                ExternalId.Kind.ASSIGNMENT, goneAssignments,
                ExternalId.Kind.REPORTING_LINE, gone(oldLines, lines, ReportingLine::id, ReportingLine::period, date),
                ExternalId.Kind.UNIT_ROLE, gone(oldRoles, roles, UnitRole::id, UnitRole::period, date)));
    }

    // The end removes what starts on the date or later. A line and a role that hold before it stay, with their
    // external IDs.
    private static <T> Map<String, String> gone(List<T> old, List<T> copies, Function<T, String> idOf,
            Function<T, ValidPeriod> periodOf, LocalDate date) {
        Map<String, String> gone = new HashMap<>();
        for (int i = 0; i < old.size(); i++) {
            if (!periodOf.apply(old.get(i)).from().isBefore(date)) {
                gone.put(idOf.apply(old.get(i)), idOf.apply(copies.get(i)));
            }
        }
        return gone;
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
        setEnd(List.of(get(id).employment()), null, null);
        return get(id);
    }

    private void setEnd(List<Employment> employments, LocalDate to, String endReasonId) {
        // Only the end of the period counts, and it is the same for every employment.
        Set<String> checked = new HashSet<>();
        for (Employment employment : employments) {
            if (checked.add(employment.employerId())) {
                employers.requireCovers(employment.employerId(), employment.period().endingAt(to));
            }
        }
        for (Employment employment : employments) {
            store.update(new Employment(employment.id(), employment.personId(), employment.userId(),
                    employment.employerId(), employment.period().endingAt(to), employment.staffNumber(), endReasonId,
                    employment.source(), employment.continuesEmploymentId(), employment.version()));
        }
        List<String> ids = employments.stream().map(Employment::id).toList();
        endTerms(ids, to);
        if (to == null) {
            return;
        }
        List<String> removedRoles = new ArrayList<>();
        List<UnitRole> endedRoles = new ArrayList<>();
        for (UnitRole role : unitRoles.findByEmployments(ids)) {
            if (!role.period().from().isBefore(to)) {
                removedRoles.add(role.id());
            } else if (role.period().endsAfter(to)) {
                endedRoles.add(role.withPeriod(role.period().endingAt(to)));
            }
        }
        unitRoles.deleteAll(removedRoles);
        unitRoles.updateAll(endedRoles);
        List<Assignment> rows = assignments.rowsOfEmployments(ids);
        // A reporting line cannot outlast its assignment.
        reportingLines.truncate(rows.stream().map(Assignment::assignmentId).distinct().toList(), to);
        List<String> removedRows = new ArrayList<>();
        List<Assignment> endedRows = new ArrayList<>();
        for (Assignment row : rows) {
            if (!row.period().from().isBefore(to)) {
                removedRows.add(row.rowId());
            } else if (row.period().endsAfter(to)) {
                endedRows.add(row.withPeriod(row.period().endingAt(to)));
            }
        }
        assignments.deleteAll(removedRows);
        assignments.updateAll(endedRows);
    }

    private void endTerms(List<String> employmentIds, LocalDate to) {
        Map<String, List<EmploymentTerms>> terms = store.termsOf(employmentIds).stream()
                .collect(Collectors.groupingBy(EmploymentTerms::employmentId));
        List<String> removed = new ArrayList<>();
        List<EmploymentTerms> ended = new ArrayList<>();
        for (String id : employmentIds) {
            List<EmploymentTerms> of = terms.getOrDefault(id, List.of());
            if (to != null) {
                of.stream().filter(t -> !t.period().from().isBefore(to)).forEach(t -> removed.add(t.id()));
            }
            EmploymentTerms last = of.stream()
                    .filter(t -> to == null || t.period().from().isBefore(to))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            ended.add(last.withPeriod(last.period().endingAt(to)));
        }
        store.deleteAllTerms(removed);
        store.updateAllTerms(ended);
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
     * @param contractEndFrom the first date of a range as {@code YYYY-MM-DD}. The contract end in the terms of
     *        {@code asOf} is on it or later.
     * @param contractEndTo the first date after that range as {@code YYYY-MM-DD}
     * @param today the date of the terms when {@code asOf} is null
     */
    public record Search(String staffNumber, String employerId, String asOf, String contractEndFrom,
            String contractEndTo, LocalDate today) {

        /**
         * Makes a search that does not look at contracts.
         *
         * @param staffNumber the staff number
         * @param employerId the ID of the org unit that employs
         * @param asOf a date as {@code YYYY-MM-DD} on which the employment holds
         */
        public Search(String staffNumber, String employerId, String asOf) {
            this(staffNumber, employerId, asOf, null, null, null);
        }

        EmploymentStore.Search toStore() {
            LocalDate date = Checks.optionalDate("asOf", asOf);
            LocalDate from = Checks.optionalDate("contractEndFrom", contractEndFrom);
            LocalDate to = Checks.optionalDate("contractEndTo", contractEndTo);
            if (from != null && to != null && !to.isAfter(from)) {
                throw FuhitoException.invalid("invalid.period", Map.of("validFrom", contractEndFrom,
                        "validTo", contractEndTo));
            }
            return new EmploymentStore.Search(staffNumber, employerId, date, date == null ? today : date, from, to);
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
        return views(store.search(search.toStore(), afterId, page.first(), page.max()));
    }

    private List<EmploymentView> views(List<Employment> employments) {
        Map<String, List<EmploymentTerms>> terms = store.termsOf(employments.stream().map(Employment::id).toList())
                .stream()
                .collect(Collectors.groupingBy(EmploymentTerms::employmentId));
        Map<String, String> codes = new HashMap<>();
        return employments.stream()
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
        return code == null ? null : usable(kind, code, refItems.findByCode(kind, code), currentId);
    }

    private static RefItem usable(RefKind kind, String code, Optional<RefItem> found, String currentId) {
        return found.filter(item -> item.active() || item.id().equals(currentId))
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
