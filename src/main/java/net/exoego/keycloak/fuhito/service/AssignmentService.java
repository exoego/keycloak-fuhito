package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

/**
 * Operations on the assignments of one realm.
 */
public class AssignmentService {

    private final AssignmentStore store;
    private final ExternalIdStore externalIdStore;
    private final ExternalIdWrites externalIds;
    private final EmploymentStore employments;
    private final OrgUnitStore orgUnits;
    private final RefItemStore refItems;
    private final ReportingLineStore reportingLines;
    private final Today today;
    private final AttributeRules attributeRules;

    /**
     * Creates the service where today is the date in UTC.
     *
     * @param store the storage of assignments of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param reportingLines the storage of reporting lines of the realm
     */
    public AssignmentService(AssignmentStore store, ExternalIdStore externalIds, EmploymentStore employments,
            OrgUnitStore orgUnits, RefItemStore refItems, ReportingLineStore reportingLines) {
        this(store, externalIds, employments, orgUnits, refItems, reportingLines, Today.of(Clock.systemUTC()),
                AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of assignments of the realm
     * @param externalIds the storage of the IDs that systems have for the resources of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param reportingLines the storage of reporting lines of the realm
     * @param today what today is for each org unit. The values that apply to an assignment are read on it.
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public AssignmentService(AssignmentStore store, ExternalIdStore externalIds, EmploymentStore employments,
            OrgUnitStore orgUnits, RefItemStore refItems, ReportingLineStore reportingLines, Today today,
            AttributeRules attributeRules) {
        this.today = today;
        this.attributeRules = attributeRules;
        this.reportingLines = reportingLines;
        this.store = store;
        this.externalIdStore = externalIds;
        this.externalIds = new ExternalIdWrites(externalIds, ExternalId.Kind.ASSIGNMENT);
        this.employments = employments;
        this.orgUnits = orgUnits;
        this.refItems = refItems;
    }

    /**
     * Returns the same operations that read the definitions of attributes once for each kind of resource. They
     * suit a list of writes that changes no definition.
     *
     * @return the operations
     */
    AssignmentService remembering() {
        return new AssignmentService(store, externalIdStore, employments, orgUnits, refItems, reportingLines, today,
                attributeRules.remembering());
    }

    /**
     * Adds an assignment of an employment to an org unit.
     * One employment can have any number of assignments at the same time, in units under different roots too.
     * With no end date, the assignment lasts as long as the employment.
     *
     * @param request the values of the request. The assignment keeps the source as the system that made it.
     * @return the new assignment
     * @throws FuhitoException when a value is bad, the employment or the org unit does not cover the period, or
     *         the external ID belongs to another assignment
     */
    public AssignmentView add(AssignmentValues request) {
        return add(request, null);
    }

    /**
     * Adds an assignment that continues another assignment of the same person, as a transfer between employers
     * does. The link is written with the new assignment, and it never changes.
     *
     * @param request the values of the request. The assignment keeps the source as the system that made it.
     * @param continuesAssignmentId the ID of the assignment that the new one continues, or null
     * @return the new assignment
     * @throws FuhitoException as {@link #add(AssignmentValues)} does, and when the assignment to continue does not
     *         exist or is of another person
     */
    AssignmentView add(AssignmentValues request, String continuesAssignmentId) {
        NewAssignments adds = new NewAssignments(externalIds);
        String id = adds.add(request, continuesAssignmentId);
        adds.insert();
        return get(id);
    }

    /**
     * Starts new assignments of one list of writes.
     *
     * @param ids finds the assignment that an ID of a system names
     * @return the new assignments
     */
    NewAssignments newAssignments(ExternalIdWrites.Lookup ids) {
        return new NewAssignments(new ExternalIdWrites(externalIdStore, ExternalId.Kind.ASSIGNMENT, ids));
    }

    /**
     * The new assignments of one list of writes. Each one is checked against the stored assignments and the new
     * ones before it. All of them are stored together.
     */
    final class NewAssignments {

        private final ExternalIdWrites ids;
        private final Map<String, Optional<Employment>> employmentsById = new HashMap<>();
        private final Map<String, Optional<ValidPeriod>> lives = new HashMap<>();
        private final Map<String, List<Assignment>> ofEmployment = new HashMap<>();
        private final Map<String, Assignment> added = new LinkedHashMap<>();
        private final List<ExternalId> rows = new ArrayList<>();
        private final List<String> inserted = new ArrayList<>();

        private NewAssignments(ExternalIdWrites ids) {
            this.ids = ids;
        }

        /**
         * Reads some employments with the rows of their assignments, and the lives of some org units, with one read
         * for each.
         *
         * @param employmentIds the IDs of the employments
         * @param orgUnitIds the IDs of the org units
         */
        void read(Collection<String> employmentIds, Collection<String> orgUnitIds) {
            List<String> unread = employmentIds.stream().distinct().filter(id -> !ofEmployment.containsKey(id))
                    .toList();
            if (!unread.isEmpty()) {
                unread.forEach(id -> {
                    employmentsById.put(id, Optional.empty());
                    ofEmployment.put(id, new ArrayList<>());
                });
                employments.findAll(unread).forEach(e -> employmentsById.put(e.id(), Optional.of(e)));
                store.rowsOfEmployments(unread).forEach(row -> ofEmployment.get(row.employmentId()).add(row));
            }
            List<String> units = orgUnitIds.stream().distinct().filter(id -> !lives.containsKey(id)).toList();
            units.forEach(id -> lives.put(id, Optional.empty()));
            if (!units.isEmpty()) {
                orgUnits.livesOf(units).forEach((id, life) -> lives.put(id, Optional.of(life)));
            }
        }

        /**
         * Finds an employment.
         *
         * @param id the ID of the employment
         * @return the employment, or empty when the realm has none with the ID
         */
        Optional<Employment> employment(String id) {
            return employmentsById.computeIfAbsent(id, employments::find);
        }

        /**
         * Lets the next new assignment of an employment read its stored rows again, after a write of them.
         *
         * @param employmentId the ID of the employment
         */
        void forget(String employmentId) {
            ofEmployment.remove(employmentId);
        }

        /**
         * Checks a new assignment as {@link AssignmentService#add(AssignmentValues, String)} does, and keeps it for
         * {@link #insert}.
         *
         * @param request the values of the request
         * @param continuesAssignmentId the ID of the assignment that the new one continues, or null
         * @return the ID of the new assignment
         */
        String add(AssignmentValues request, String continuesAssignmentId) {
            Checks.required("body", request);
            String employmentId = Checks.required("employmentId", request.employmentId());
            String orgUnitId = Checks.required("orgUnitId", request.orgUnitId());
            LocalDate from = Checks.date("validFrom", request.validFrom());
            LocalDate to = Checks.optionalDate("validTo", request.validTo());
            Employment employment = employment(employmentId).orElseThrow(() -> FuhitoException.invalid(
                    "assignment.employment_unknown", Map.of("employmentId", employmentId)));
            ValidPeriod period = Checks.period(from, to == null ? employment.period().to() : to);
            if (!employment.period().encloses(period)) {
                throw FuhitoException.conflict("assignment.outside_employment", Map.of("employmentId", employmentId,
                        "validFrom", period.from().toString()));
            }
            requireOrgUnitCovers(orgUnitId, period, lives.computeIfAbsent(orgUnitId, AssignmentService.this::lifeOf));
            ExternalId.Pair externalId = ids.check(null, request.source(), request.externalId());
            if (continuesAssignmentId != null) {
                List<Assignment> previous = added.containsKey(continuesAssignmentId)
                        ? List.of(added.get(continuesAssignmentId)) : store.rows(continuesAssignmentId);
                if (previous.isEmpty()) {
                    throw FuhitoException.invalid("assignment.continues_unknown", Map.of("id", continuesAssignmentId));
                }
                if (!employment(previous.getFirst().employmentId()).map(Employment::personId)
                        .equals(Optional.of(employment.personId()))) {
                    throw FuhitoException.invalid("assignment.continues_other", Map.of("id", continuesAssignmentId));
                }
            }

            String assignmentId = newId();
            Assignment row = row(newId(), assignmentId, employmentId, orgUnitId, period, request, null,
                    Checks.optionalCode("source", request.source()), continuesAssignmentId);
            if (claimPrimary(row, request, rowsOf(employmentId))) {
                forget(employmentId);
            }
            rowsOf(employmentId).add(row);
            added.put(assignmentId, row);
            ids.forNew(assignmentId, externalId).ifPresent(rows::add);
            return assignmentId;
        }

        private List<Assignment> rowsOf(String employmentId) {
            return ofEmployment.computeIfAbsent(employmentId,
                    id -> new ArrayList<>(store.rowsOfEmployment(id)));
        }

        /**
         * Stores the new assignments that are not stored yet, with their IDs of other systems.
         */
        void insert() {
            store.insertAll(List.copyOf(added.values()));
            externalIdStore.insertAll(List.copyOf(rows));
            inserted.addAll(added.keySet());
            added.clear();
            rows.clear();
        }

        /**
         * Reads the new assignments that {@link #insert} stored, with one read for each kind of record.
         *
         * @return the assignments, by ID
         */
        Map<String, AssignmentView> views() {
            return findAll(inserted);
        }
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
                    current.period(), request, current, current.source(), current.continuesAssignmentId());
            claimPrimary(corrected, request);
            store.update(corrected);
        } else {
            List<ValidPeriod> parts = current.period().splitAt(from);
            // Build the new row first, so that a bad value fails before anything is written.
            Assignment next = row(newId(), id, current.employmentId(), current.orgUnitId(), parts.get(1), request,
                    current, current.source(), current.continuesAssignmentId());
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
     * It keeps the job title unless the request gives one. It does not keep the head flag, the display order, the
     * work location, or the cost center, because those belong to the old org unit. The request can give new ones.
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
                last.occupationId(), last.kindId(), idOf(RefKind.WORK_LOCATION, request.workLocation(), null),
                idOf(RefKind.COST_CENTER, request.costCenter(), null), Boolean.TRUE.equals(request.head()),
                last.primary(), last.allocationPercent(), request.displayOrder() == null ? 0 : request.displayOrder(),
                last.attributes(), null, id, 1);
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
            reportingLines.truncate(List.of(last.assignmentId()), to);
        }
    }

    /**
     * Returns an assignment with its details over time.
     * The work location and the cost center that apply to each period are the ones on its date nearest to today.
     *
     * @param id the ID of the assignment
     * @return the assignment
     * @throws FuhitoException when the assignment does not exist
     */
    public AssignmentView get(String id) {
        return getAll(List.of(id)).getFirst();
    }

    /**
     * Returns several assignments with their details over time, as {@link #get} does for each.
     *
     * @param ids the IDs of the assignments
     * @return the assignments, in the order of the IDs
     * @throws FuhitoException when an assignment does not exist
     */
    public List<AssignmentView> getAll(List<String> ids) {
        Map<String, List<Assignment>> rowsById = store.rowsOf(ids).stream()
                .collect(Collectors.groupingBy(Assignment::assignmentId));
        for (String id : ids) {
            if (!rowsById.containsKey(id)) {
                throw FuhitoException.notFound("assignment.not_found", Map.of("id", id));
            }
        }
        return views(ids, rowsById);
    }

    /**
     * Finds several assignments with their details over time, as {@link #get} returns each.
     *
     * @param ids the IDs of the assignments
     * @return the assignments that the realm has, by ID
     */
    Map<String, AssignmentView> findAll(Collection<String> ids) {
        Map<String, List<Assignment>> rowsById = store.rowsOf(ids).stream()
                .collect(Collectors.groupingBy(Assignment::assignmentId));
        Map<String, AssignmentView> found = new HashMap<>();
        views(List.copyOf(rowsById.keySet()), rowsById).forEach(view -> found.put(view.assignmentId(), view));
        return found;
    }

    private List<AssignmentView> views(List<String> ids, Map<String, List<Assignment>> rowsById) {
        Map<String, LocalDate> dates = new HashMap<>();
        for (String id : ids) {
            dates.put(id, today.ofUnit(rowsById.get(id).getFirst().orgUnitId()));
        }
        Map<String, String> userIds = new HashMap<>();
        employments.findAll(rowsById.values().stream().map(rows -> rows.getFirst().employmentId()).toList())
                .forEach(employment -> userIds.put(employment.id(), employment.userId()));
        Function<String, String> code = UnitPlaces.codes(refItems);
        List<Assignment> all = ids.stream().flatMap(id -> rowsById.get(id).stream()).toList();
        List<LocationAndCost> places = UnitPlaces.ofRows(all, row -> dates.get(row.assignmentId()), orgUnits, code);
        List<AssignmentView> views = new ArrayList<>();
        int at = 0;
        for (String id : ids) {
            List<Assignment> rows = rowsById.get(id);
            List<LocationAndCost> ofRows = places.subList(at, at + rows.size());
            at += rows.size();
            views.add(new AssignmentView(id, userIds.get(rows.getFirst().employmentId()),
                    new ValidPeriod(rows.getFirst().period().from(), rows.getLast().period().to()),
                    rows.getFirst().continuesAssignmentId(), IntStream.range(0, rows.size())
                            .mapToObj(i -> row(rows.get(i), code, ofRows.get(i)))
                            .toList()));
        }
        return views;
    }

    /**
     * Returns the version of an assignment.
     *
     * @param id the ID of the assignment
     * @return the number that each write of the assignment counts up
     * @throws FuhitoException when the assignment does not exist
     */
    public long version(String id) {
        return requireRows(id).getFirst().version();
    }

    private static AssignmentView.Row row(Assignment row, Function<String, String> code, LocationAndCost places) {
        return new AssignmentView.Row(row, code.apply(row.jobTitleId()), code.apply(row.occupationId()),
                code.apply(row.kindId()), places);
    }

    /**
     * Lists a page of the assignments that hold on a date, by ID, each with its details over time.
     * The rows have their own work location and cost center, and not the ones that apply.
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
        Function<String, String> code = UnitPlaces.codes(refItems);
        return ids.stream().map(id -> {
            List<Assignment> rows = rowsById.get(id);
            Employment employment = employmentsById.get(rows.getFirst().employmentId());
            return new AssignmentView(id, employment == null ? null : employment.userId(),
                    new ValidPeriod(rows.getFirst().period().from(), rows.getLast().period().to()),
                    rows.getFirst().continuesAssignmentId(), rows.stream()
                            .map(r -> row(r, code, UnitPlaces.ownOfRow(r, code)))
                            .toList());
        }).toList();
    }

    // An employment has at most one primary assignment on any date.
    private void claimPrimary(Assignment claimant, AssignmentValues request) {
        claimPrimary(claimant, request, store.rowsOfEmployment(claimant.employmentId()));
    }

    // Tells whether the claimant took the flag from rows that held it, which then changed.
    private boolean claimPrimary(Assignment claimant, AssignmentValues request, List<Assignment> ofEmployment) {
        if (!claimant.primary()) {
            return false;
        }
        List<Assignment> holders = ofEmployment.stream()
                .filter(other -> other.primary() && !other.assignmentId().equals(claimant.assignmentId())
                        && other.period().overlaps(claimant.period()))
                .sorted(Comparator.comparing((Assignment other) -> other.period().from())
                        .thenComparing(Assignment::rowId))
                .toList();
        if (holders.isEmpty()) {
            return false;
        }
        if (!Boolean.TRUE.equals(request.switchPrimary())) {
            throw FuhitoException.conflict("assignment.primary_exists", Map.of("employmentId",
                    claimant.employmentId(), "assignmentId", holders.getFirst().assignmentId(), "validFrom",
                    claimant.period().from().toString()));
        }
        holders.forEach(holder -> releasePrimary(holder, claimant.period()));
        return true;
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

    // The org unit must exist on every date of the assignment.
    private void requireOrgUnitCovers(String orgUnitId, ValidPeriod period) {
        requireOrgUnitCovers(orgUnitId, period, lifeOf(orgUnitId));
    }

    private Optional<ValidPeriod> lifeOf(String orgUnitId) {
        return Optional.ofNullable(orgUnits.livesOf(List.of(orgUnitId)).get(orgUnitId));
    }

    private static void requireOrgUnitCovers(String orgUnitId, ValidPeriod period, Optional<ValidPeriod> unitLife) {
        ValidPeriod life = unitLife.orElseThrow(() -> FuhitoException.invalid("assignment.org_unit_unknown",
                Map.of("orgUnitId", orgUnitId)));
        if (!life.encloses(period)) {
            throw FuhitoException.conflict("assignment.org_unit_not_valid",
                    Map.of("orgUnitId", orgUnitId, "validFrom", period.from().toString()));
        }
    }

    // A row may keep a reference item that was deactivated after the row got it.
    private Assignment row(String rowId, String assignmentId, String employmentId, String orgUnitId,
            ValidPeriod period, AssignmentValues values, Assignment current, String source,
            String continuesAssignmentId) {
        return new Assignment(rowId, assignmentId, employmentId, orgUnitId, period,
                idOf(RefKind.JOB_TITLE, values.jobTitle(), current == null ? null : current.jobTitleId()),
                idOf(RefKind.OCCUPATION, values.occupation(), current == null ? null : current.occupationId()),
                idOf(RefKind.ASSIGNMENT_KIND, values.kind(), current == null ? null : current.kindId()),
                idOf(RefKind.WORK_LOCATION, values.workLocation(), current == null ? null : current.workLocationId()),
                idOf(RefKind.COST_CENTER, values.costCenter(), current == null ? null : current.costCenterId()),
                Boolean.TRUE.equals(values.head()),
                Boolean.TRUE.equals(values.primary()),
                Checks.optionalPercent("allocationPercent", values.allocationPercent()),
                values.displayOrder() == null ? 0 : values.displayOrder(),
                attributeRules.check(AttributeDefinition.Target.ASSIGNMENT, values.attributes()),
                source, continuesAssignmentId, current == null ? 1 : current.version());
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

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
