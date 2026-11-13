package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentStatus;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
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

    private static final int DEFAULT_MAX = 100;

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
        LocalDate date = query.asOf() == null ? today.ofUnit(orgUnitId)
                : Checks.optionalDate("asOf", query.asOf());
        if (orgUnits.findUnit(orgUnitId).isEmpty()) {
            throw FuhitoException.notFound("org_unit.not_found", Map.of("id", orgUnitId));
        }
        OrgUnitTree tree = new OrgUnitTree(orgUnits, refItems, date);
        List<OrgUnitState> units = query.includeDescendants() ? tree.subtree(orgUnitId)
                : List.of(tree.subtree(orgUnitId).get(0));
        String occupationId = filterId(RefKind.OCCUPATION, query.occupation());
        String jobTitleId = filterId(RefKind.JOB_TITLE, query.jobTitle());
        EmploymentStatus status = Checks.optionalEnum("status", EmploymentStatus.class, query.status(), null);
        int first = query.first() == null ? 0 : Math.max(0, query.first());
        int max = query.max() == null ? DEFAULT_MAX : Math.max(0, query.max());

        Map<String, Integer> unitOrder = new HashMap<>();
        units.forEach(unit -> unitOrder.put(unit.unit().id(), unitOrder.size()));
        List<Assignment> rows = assignments.rowsOn(unitOrder.keySet(), date).stream()
                .filter(row -> occupationId == null || occupationId.equals(row.occupationId()))
                .filter(row -> jobTitleId == null || jobTitleId.equals(row.jobTitleId()))
                .sorted(Comparator.comparing((Assignment row) -> unitOrder.get(row.orgUnitId()))
                        .thenComparingInt(Assignment::displayOrder)
                        .thenComparing(row -> row.period().from())
                        .thenComparing(Assignment::assignmentId))
                .toList();
        return toMembers(rows, date, tree, false).stream()
                .filter(member -> status == null || member.terms().status() == status)
                .skip(first)
                .limit(max)
                .toList();
    }

    /**
     * Lists the assignments of a user that hold on a date, across all employments of the user.
     * The primary assignment comes first.
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
        // With no date, each assignment is judged on the date of its own org unit.
        Map<LocalDate, List<Assignment>> rowsByDate = new LinkedHashMap<>();
        employments.findByUser(userId).stream()
                .flatMap(employment -> assignments.rowsOfEmployment(employment.id()).stream())
                .forEach(row -> {
                    LocalDate date = asked == null ? today.ofUnit(row.orgUnitId()) : asked;
                    if (row.period().contains(date)) {
                        rowsByDate.computeIfAbsent(date, d -> new ArrayList<>()).add(row);
                    }
                });
        List<Member> members = new ArrayList<>();
        rowsByDate.forEach((date, rows) ->
                members.addAll(toMembers(rows, date, new OrgUnitTree(orgUnits, refItems, date), includePath)));
        members.sort(Comparator.comparing((Member member) -> !member.assignment().primary())
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
        List<AssignmentView> assignmentViews = ofUser.stream()
                .flatMap(employment -> assignments.rowsOfEmployment(employment.id()).stream())
                .map(Assignment::assignmentId)
                .distinct()
                .map(assignmentService::get)
                .sorted(Comparator.comparing((AssignmentView view) -> view.period().from())
                        .thenComparing(AssignmentView::assignmentId))
                .toList();
        return new UserHistory(userId, ofUser.stream().map(e -> employmentService.get(e.id())).toList(),
                assignmentViews);
    }

    private List<Member> toMembers(List<Assignment> rows, LocalDate date, OrgUnitTree tree, boolean includePath) {
        List<String> employmentIds = rows.stream().map(Assignment::employmentId).distinct().toList();
        Map<String, Employment> employmentsById = employments.findAll(employmentIds).stream()
                .collect(Collectors.toMap(Employment::id, Function.identity()));
        Map<String, EmploymentTerms> termsByEmployment = employments.termsOn(employmentIds, date).stream()
                .collect(Collectors.toMap(EmploymentTerms::employmentId, Function.identity()));
        Map<String, String> codes = new HashMap<>();
        List<Member> members = new ArrayList<>();
        for (Assignment row : rows) {
            EmploymentTerms terms = termsByEmployment.get(row.employmentId());
            List<OrgUnitState> path = tree.path(row.orgUnitId());
            members.add(new Member(row, employmentsById.get(row.employmentId()), terms,
                    codeOf(codes, row.jobTitleId()), codeOf(codes, row.occupationId()), codeOf(codes, row.kindId()),
                    codeOf(codes, terms.employmentTypeId()), codeOf(codes, terms.gradeId()),
                    path.get(path.size() - 1), includePath ? path : null));
        }
        return members;
    }

    private String codeOf(Map<String, String> cache, String refItemId) {
        if (refItemId == null) {
            return null;
        }
        return cache.computeIfAbsent(refItemId, id -> refItems.find(id).map(RefItem::code).orElse(""))
                .transform(code -> code.isEmpty() ? null : code);
    }

    // A filter may name an item that is no longer active, because old rows may still use it.
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
