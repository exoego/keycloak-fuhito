package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.MemberCursor;
import net.exoego.keycloak.fuhito.domain.MemberFilter;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * Reads that answer who works where on a date.
 */
public class MembershipService {


    private final AssignmentStore assignments;
    private final EmploymentStore employments;
    private final OrgUnitStore orgUnits;
    private final RefItemStore refItems;
    private final UserDirectory users;
    private final Today today;

    /**
     * Creates the service.
     *
     * @param assignments the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param users the users of the realm
     * @param clock the clock that decides what "today" is
     */
    public MembershipService(AssignmentStore assignments, EmploymentStore employments, OrgUnitStore orgUnits,
            RefItemStore refItems, UserDirectory users, Clock clock) {
        this(assignments, employments, orgUnits, refItems, users, Today.of(clock));
    }

    /**
     * Creates the service for a realm where an org unit can have its own time settings.
     *
     * @param assignments the storage of assignments of the realm
     * @param employments the storage of employments of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param users the users of the realm
     * @param today what today is for the realm and for each org unit
     */
    public MembershipService(AssignmentStore assignments, EmploymentStore employments, OrgUnitStore orgUnits,
            RefItemStore refItems, UserDirectory users, Today today) {
        this.assignments = assignments;
        this.employments = employments;
        this.orgUnits = orgUnits;
        this.refItems = refItems;
        this.users = users;
        this.today = today;
    }

    /**
     * Lists the assignments to an org unit that hold on a date.
     * With descendants, the units come in tree order.
     * Inside one unit, members follow the display order, then the start of the assignment.
     *
     * @param orgUnitId the ID of the org unit
     * @param query the date, the filters, and the page
     * @return the members on the date
     * @throws FuhitoException when the unit does not exist on the date or a filter is bad
     */
    public List<Member> members(String orgUnitId, MemberQuery query) {
        MemberFilter filter = filter(orgUnitId, query);
        LocalDate date = filter.date();
        String hierarchy = filter.hierarchyId();
        Cursor.requireAlone(query.after(), query.first());
        Page page = Page.of(query.first(), query.max());
        MemberCursor after = cursor(query.after());
        requireInTree(filter, after);
        List<Assignment> rows = assignments.membersOn(filter, after, page.first(), page.max());

        return toMembers(rows, date, unitsOn(hierarchy, rows, date)::get, null);
    }

    // With descendants, the next page starts after the place of the unit of the cursor in the tree.
    private void requireInTree(MemberFilter filter, MemberCursor after) {
        if (after == null || !filter.includeDescendants()) {
            return;
        }
        String id = after.orgUnitId();
        if (!orgUnits.ancestorsOn(filter.hierarchyId(), List.of(id), filter.date()).getOrDefault(id, Set.of())
                .contains(filter.orgUnitId())) {
            throw FuhitoException.invalid("invalid.cursor_unit",
                    Map.of("field", "after", "id", id, "date", filter.date().toString()));
        }
    }

    /**
     * Counts the members that {@link #members} lists on all pages together.
     *
     * @param orgUnitId the ID of the org unit
     * @param query the date and the filters. The paging values do not count.
     * @return the number of members
     * @throws FuhitoException when the unit does not exist on the date or a filter is bad
     */
    public long countMembers(String orgUnitId, MemberQuery query) {
        return assignments.countMembersOn(filter(orgUnitId, query));
    }

    private MemberFilter filter(String orgUnitId, MemberQuery query) {
        LocalDate date = query.asOf() == null ? today.ofUnit(orgUnitId)
                : Checks.optionalDate("asOf", query.asOf());
        if (orgUnits.findUnit(orgUnitId).isEmpty()) {
            throw FuhitoException.notFound("org_unit.not_found", Map.of("id", orgUnitId));
        }
        String hierarchy = HierarchyCodes.idOf(orgUnits, query.hierarchy());
        if (orgUnits.placedOn(hierarchy, List.of(orgUnitId), date).isEmpty()) {
            throw FuhitoException.notFound("org_unit.not_valid_on",
                    Map.of("id", orgUnitId, "date", date.toString()));
        }
        String workLocationId = filterId(RefKind.WORK_LOCATION, query.workLocation());
        String costCenterId = filterId(RefKind.COST_CENTER, query.costCenter());
        return new MemberFilter(hierarchy, orgUnitId, query.includeDescendants(), date,
                filterId(RefKind.OCCUPATION, query.occupation()), filterId(RefKind.JOB_TITLE, query.jobTitle()),
                Checks.optionalEnum("status", EmploymentStatus.class, query.status(), null),
                query.seconded() == null ? null
                        : new MemberFilter.Seconded(query.seconded(), orgUnits.defaultHierarchyId()),
                workLocationId == null && costCenterId == null ? null
                        : new MemberFilter.Places(workLocationId, costCenterId, orgUnits.defaultHierarchyId()));
    }

    /**
     * Lists the assignments of a user that hold on a date, across all employments of the user.
     * The primary assignments come first. Within them and within the others, the assignments of the employment
     * that started first come first.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param includePath true to return each org unit with its path from the root
     * @return the assignments of the user on the date
     * @throws FuhitoException when the user does not exist
     */
    public List<Member> assignmentsOf(String userId, String asOf, boolean includePath) {
        LocalDate asked = Checks.optionalDate("asOf", asOf);
        requireUser(userId);
        return assignmentsOf(employments.findByUser(userId), asked, includePath);
    }

    /**
     * Lists the assignments of a person that hold on a date. The primary assignment comes first.
     *
     * @param personId the ID of the person
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today
     * @param includePath true to return each org unit with its path from the root
     * @return the assignments of the person on the date
     * @throws FuhitoException when the date is bad
     */
    public List<Member> assignmentsOfPerson(String personId, String asOf, boolean includePath) {
        return assignmentsOf(employments.findByPerson(personId), Checks.optionalDate("asOf", asOf), includePath);
    }

    private List<Member> assignmentsOf(List<Employment> ofOne, LocalDate asked, boolean includePath) {
        // With no date, each assignment is judged on the date of its own org unit.
        Map<LocalDate, List<Assignment>> rowsByDate = new LinkedHashMap<>();
        ofOne.stream()
                .flatMap(employment -> assignments.rowsOfEmployment(employment.id()).stream())
                .forEach(row -> {
                    LocalDate date = asked == null ? today.ofUnit(row.orgUnitId()) : asked;
                    if (row.period().contains(date)) {
                        rowsByDate.computeIfAbsent(date, d -> new ArrayList<>()).add(row);
                    }
                });
        List<Member> members = new ArrayList<>();
        rowsByDate.forEach((date, rows) ->
        {
            // The ways up from all the org units of the date are read together, with the names of their units.
            Map<String, List<OrgUnitState>> paths = OrgUnitPaths.of(orgUnits, refItems,
                    orgUnits.defaultHierarchyId(), rows.stream().map(Assignment::orgUnitId).distinct().toList(), date);
            members.addAll(toMembers(rows, date, id -> paths.get(id).getLast(), includePath ? paths::get : null));
        });
        // Each employment can have a primary assignment. The one of the oldest employment is the first.
        members.sort(Comparator.comparing((Member member) -> !member.assignment().primary())
                .thenComparing(member -> member.employment().period().from())
                .thenComparing(member -> member.employment().id())
                .thenComparing(member -> member.assignment().period().from())
                .thenComparing(member -> member.assignment().assignmentId()));
        return members;
    }

    /**
     * Returns everything that is stored about a user over time.
     *
     * @param userId the ID of the user
     * @param employmentService the service that builds the view of an employment
     * @param assignmentService the service that builds the view of an assignment
     * @return the employments and the assignments of the user
     * @throws FuhitoException when the user does not exist
     */
    public UserHistory historyOf(String userId, EmploymentService employmentService,
            AssignmentService assignmentService) {
        requireUser(userId);
        List<Employment> ofUser = employments.findByUser(userId);
        return history(ofUser.isEmpty() ? null : ofUser.getFirst().personId(), userId, ofUser, employmentService,
                assignmentService);
    }

    /**
     * Returns every employment and every assignment of a person, with all their periods.
     *
     * @param person the person
     * @param employmentService the reads of employments
     * @param assignmentService the reads of assignments
     * @return the history of the person
     */
    public UserHistory historyOfPerson(Person person, EmploymentService employmentService,
            AssignmentService assignmentService) {
        return history(person.id(), person.userId(), employments.findByPerson(person.id()), employmentService,
                assignmentService);
    }

    private UserHistory history(String personId, String userId, List<Employment> ofUser,
            EmploymentService employmentService, AssignmentService assignmentService) {
        List<AssignmentView> assignmentViews = ofUser.stream()
                .flatMap(employment -> assignments.rowsOfEmployment(employment.id()).stream())
                .map(Assignment::assignmentId)
                .distinct()
                .map(assignmentService::get)
                .sorted(Comparator.comparing((AssignmentView view) -> view.period().from())
                        .thenComparing(AssignmentView::assignmentId))
                .toList();
        return new UserHistory(personId, userId, ofUser.stream().map(e -> employmentService.get(e.id())).toList(),
                assignmentViews);
    }

    private List<Member> toMembers(List<Assignment> rows, LocalDate date, Function<String, OrgUnitState> unitOf,
            Function<String, List<OrgUnitState>> pathOf) {
        List<String> employmentIds = rows.stream().map(Assignment::employmentId).distinct().toList();
        Map<String, Employment> employmentsById = employments.findAll(employmentIds).stream()
                .collect(Collectors.toMap(Employment::id, Function.identity()));
        Map<String, EmploymentTerms> termsByEmployment = employments.termsOn(employmentIds, date).stream()
                .collect(Collectors.toMap(EmploymentTerms::employmentId, Function.identity()));
        // A person works outside the employer when the employer is not on the way up from the org unit.
        Map<String, Set<String>> above = orgUnits.ancestorsOn(orgUnits.defaultHierarchyId(),
                rows.stream().map(Assignment::orgUnitId).distinct().toList(), date);
        Function<String, String> code = UnitPlaces.codes(refItems);
        // The rows hold on the date, so this is one read of the units for all of them.
        List<LocationAndCost> places = UnitPlaces.ofRows(rows, date, orgUnits, code);
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Assignment row = rows.get(i);
            EmploymentTerms terms = termsByEmployment.get(row.employmentId());
            Employment employment = employmentsById.get(row.employmentId());
            boolean seconded = employment.employerId() != null
                    && !above.getOrDefault(row.orgUnitId(), Set.of()).contains(employment.employerId());
            members.add(new Member(row, employment, terms,
                    code.apply(row.jobTitleId()), code.apply(row.occupationId()), code.apply(row.kindId()),
                    code.apply(terms.employmentTypeId()), code.apply(terms.gradeId()),
                    unitOf.apply(row.orgUnitId()), pathOf == null ? null : pathOf.apply(row.orgUnitId()), seconded,
                    places.get(i)));
        }
        return members;
    }

    // The values come from the last row of a page, in the order that the list uses.
    private static MemberCursor cursor(String after) {
        if (after == null) {
            return null;
        }
        List<String> values = Cursor.decode(Cursor.MEMBERS, after);
        try {
            return new MemberCursor(values.get(0), Integer.parseInt(values.get(1)), LocalDate.parse(values.get(2)),
                    values.get(3));
        } catch (NumberFormatException | DateTimeParseException e) {
            throw FuhitoException.invalid("invalid.cursor",
                    Map.of("field", "after", "format", Cursor.MEMBERS.name())).causedBy(e);
        }
    }

    // A filter may name an item that is no longer active, because old rows may still use it.
    /**
     * The values of a list of assignments across the realm. They are not checked yet. A value that is null keeps
     * everything.
     *
     * @param orgUnitId the ID of the org unit
     * @param jobTitle the code of the job title
     * @param occupation the code of the occupation
     * @param kind the code of the assignment kind
     * @param asOf the date as {@code YYYY-MM-DD}, or null for today of the realm
     */
    public record Search(String orgUnitId, String jobTitle, String occupation, String kind, String asOf) {
    }

    /**
     * Lists a page of the assignments of a date across the realm, by the ID of the assignment, in the shape of
     * members.
     *
     * @param search what to keep and the date
     * @param page the part of the list to return
     * @param after the cursor after which the page starts, or null
     * @return the members of the page
     * @throws FuhitoException when a value is bad or a code is unknown
     */
    public List<Member> search(Search search, Page page, String after) {
        AssignmentStore.Search filter = filter(search);
        String afterId = after == null ? null : Cursor.decode(Cursor.ASSIGNMENTS, after).getFirst();
        List<Assignment> rows = assignments.searchOn(filter, afterId, page.first(), page.max());
        String hierarchy = orgUnits.defaultHierarchyId();
        return toMembers(rows, filter.date(), unitsOn(hierarchy, rows, filter.date())::get, null);
    }

    /**
     * Counts the assignments of a date across the realm that match a search.
     *
     * @param search what to keep and the date
     * @return the number of assignments
     * @throws FuhitoException when a value is bad or a code is unknown
     */
    public long count(Search search) {
        return assignments.countSearchOn(filter(search));
    }

    private Map<String, OrgUnitState> unitsOn(String hierarchy, List<Assignment> rows, LocalDate date) {
        Map<String, OrgUnitState> units = new HashMap<>();
        Map<String, Optional<RefItem>> unitTypes = new HashMap<>();
        orgUnits.placedOn(hierarchy, rows.stream().map(Assignment::orgUnitId).distinct().toList(), date)
                .forEach(placed -> units.put(placed.unit().id(), OrgUnitState.of(placed.unit(), placed.version(),
                        placed.version().unitTypeId() == null ? null
                                : unitTypes.computeIfAbsent(placed.version().unitTypeId(), refItems::find)
                                        .orElse(null), placed.parentId())));
        return units;
    }

    private AssignmentStore.Search filter(Search search) {
        LocalDate date = search.asOf() == null ? today.ofRealm() : Checks.optionalDate("asOf", search.asOf());
        return new AssignmentStore.Search(search.orgUnitId(), filterId(RefKind.JOB_TITLE, search.jobTitle()),
                filterId(RefKind.OCCUPATION, search.occupation()), filterId(RefKind.ASSIGNMENT_KIND, search.kind()),
                date);
    }

    private String filterId(RefKind kind, String code) {
        if (code == null) {
            return null;
        }
        return refItems.findByCode(kind, code)
                .map(RefItem::id)
                .orElseThrow(() -> FuhitoException.invalid("ref_item.unknown",
                        Map.of("kind", kind.name(), "code", code)));
    }

    private void requireUser(String userId) {
        if (!users.isKnown(userId)) {
            throw FuhitoException.notFound("user.not_found", Map.of("userId", userId));
        }
    }
}
