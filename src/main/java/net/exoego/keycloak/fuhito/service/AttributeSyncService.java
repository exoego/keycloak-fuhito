package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.service.SyncReport.Failed;
import net.exoego.keycloak.fuhito.service.SyncReport.Unmatched;
import net.exoego.keycloak.fuhito.store.AssignmentStore;
import net.exoego.keycloak.fuhito.store.EmploymentStore;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;
import net.exoego.keycloak.fuhito.store.UserAttributeSource;
import net.exoego.keycloak.fuhito.store.UserAttributeSource.Settings;
import net.exoego.keycloak.fuhito.store.UserAttributeSource.Values;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.jboss.logging.Logger;

/**
 * Builds one assignment and one solid reporting line for each user from user attributes.
 * An identity provider fills the attributes, for example over SCIM.
 * The sync owns the assignments that it made. It marks them with the source "scim" and touches no others.
 */
public class AttributeSyncService {

    /**
     * The source of the employments and assignments that the sync makes.
     */
    public static final String SOURCE = "scim";

    /**
     * What the sync reads and writes.
     *
     * @param attributes the user attributes and the settings of the realm
     * @param users the users of the realm
     * @param orgUnits the storage of org units of the realm
     * @param refItems the storage of reference items of the realm
     * @param employments the storage of employments of the realm
     * @param employmentService the operations on employments of the realm
     * @param assignments the storage of assignments of the realm
     * @param assignmentService the operations on assignments of the realm
     * @param lines the storage of reporting lines of the realm
     * @param lineService the operations on reporting lines of the realm
     */
    public record Sources(UserAttributeSource attributes, UserDirectory users, OrgUnitStore orgUnits,
            RefItemStore refItems, EmploymentStore employments, EmploymentService employmentService,
            AssignmentStore assignments, AssignmentService assignmentService, ReportingLineStore lines,
            ReportingLineService lineService, PersonService personService) {
    }

    /**
     * What one piece of a run works with. Every piece gets its own, bound to its own transaction.
     *
     * @param from what the sync reads and writes
     * @param today what today is for the realm and for each org unit
     * @param record takes each write of the piece, for the event log and the change log
     */
    public record Parts(Sources from, Today today, Consumer<Write> record) {
    }

    /**
     * Runs the pieces of a run.
     * A run for a large realm writes too much for one transaction. Keycloak ends a transaction after some
     * minutes. A run can go again after a failure, so its pieces need not succeed or fail together.
     */
    public interface Work {

        /**
         * Runs one piece in a transaction of its own.
         *
         * @param <T> the type of the result
         * @param piece the piece
         * @return the result of the piece
         */
        <T> T inNewTransaction(Function<Parts, T> piece);

        /**
         * Runs a whole run, unless another run for the realm is going on.
         *
         * @param <T> the type of the result
         * @param run the run
         * @return the result of the run
         * @throws FuhitoException when another run is going on
         */
        default <T> T alone(Supplier<T> run) {
            return run.get();
        }
    }

    private record Plan(Matcher matcher, Map<String, Assignment> owned, List<Values> users) {
    }

    private static final class Tally {
        private int employmentsStarted;
        private int assignmentsAdded;
        private int assignmentsMoved;
        private int assignmentsChanged;
        private int assignmentsEnded;
        private int reportingLinesChanged;
        private final List<Unmatched> unmatched = new ArrayList<>();

        void add(Tally piece) {
            employmentsStarted += piece.employmentsStarted;
            assignmentsAdded += piece.assignmentsAdded;
            assignmentsMoved += piece.assignmentsMoved;
            assignmentsChanged += piece.assignmentsChanged;
            assignmentsEnded += piece.assignmentsEnded;
            reportingLinesChanged += piece.reportingLinesChanged;
            unmatched.addAll(piece.unmatched);
        }
    }

    private static final Logger LOG = Logger.getLogger(AttributeSyncService.class);

    private static final int USERS_AT_A_TIME = 100;

    private final Work work;
    // The parts of the piece that runs now.
    private Sources from;
    private Today today;
    private Consumer<Write> record;
    private Tally tally;
    // What the users of the piece that runs now need, read for all of them at once.
    private Map<String, List<Employment>> employmentsOfUsers = Map.of();
    private Map<String, List<ReportingLine>> linesOfAssignments = Map.of();
    private Set<String> usersFound = Set.of();

    private final Tally total = new Tally();
    private final List<Failed> failed = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();
    private String employerId;
    // The end of each org unit that the run placed a user in, read once for each unit. Empty for no end.
    private final Map<String, Optional<LocalDate>> unitEnds = new HashMap<>();

    /**
     * Creates the service for one run that works in pieces.
     *
     * @param work runs each piece in a transaction of its own
     */
    public AttributeSyncService(Work work) {
        this.work = work;
    }

    /**
     * Creates the service for one run in the transaction of the caller.
     * The report of the run then has the writes. Nothing undoes the writes of a user that fails.
     *
     * @param from what the sync reads and writes
     * @param today what today is for the realm and for each org unit
     */
    public AttributeSyncService(Sources from, Today today) {
        Parts parts = new Parts(from, today, writes::add);
        this.work = new Work() {
            @Override
            public <T> T inNewTransaction(Function<Parts, T> piece) {
                return piece.apply(parts);
            }
        };
    }

    private <T> T piece(Supplier<T> body) {
        return work.inNewTransaction(parts -> {
            from = parts.from();
            today = parts.today();
            record = parts.record();
            return body.get();
        });
    }

    /**
     * Makes the assignments and reporting lines of the sync match the user attributes of now.
     * Every change takes effect today. Running it again with no change in between changes nothing.
     * The work is done in pieces. A user that fails is reported, and the run goes on with the other users.
     *
     * @return what the run did
     * @throws FuhitoException when the realm did not turn the sync on, or another run is going on
     */
    public SyncReport run() {
        return work.alone(this::runAlone);
    }

    private SyncReport runAlone() {
        Plan plan = piece(this::plan);
        eachUser(plan.users(), Values::userId, some -> {
            readAhead(some, plan.owned());
            for (Values user : some) {
                Optional<String> orgUnitId = plan.matcher().orgUnit(user);
                if (orgUnitId.isPresent()) {
                    // Every change for this user takes effect on the date of the matched org unit.
                    sync(user, orgUnitId.get(), plan.matcher().jobTitle(user), plan.owned().get(user.userId()),
                            today.ofUnit(orgUnitId.get()));
                }
            }
        });
        // A user whose department was removed keeps no assignment from the sync.
        Set<String> withDepartment = plan.users().stream().map(Values::userId).collect(Collectors.toSet());
        List<Map.Entry<String, Assignment>> left = plan.owned().entrySet().stream()
                .filter(entry -> !withDepartment.contains(entry.getKey()))
                .toList();
        eachUser(left, Map.Entry::getKey, some -> some.forEach(entry -> {
            retire(entry.getValue(), today.ofUnit(entry.getValue().orgUnitId()));
            tally.assignmentsEnded++;
        }));
        return new SyncReport(plan.users().size(), total.employmentsStarted, total.assignmentsAdded,
                total.assignmentsMoved, total.assignmentsChanged, total.assignmentsEnded, total.reportingLinesChanged,
                List.copyOf(total.unmatched), List.copyOf(failed), List.copyOf(writes));
    }

    // An error of the database can spoil the transaction of the whole piece. So after a failure, each user of the
    // piece runs again in a transaction of its own, and only the users that fail there are left out.
    private <E> void eachUser(List<E> all, Function<E, String> userOf, Consumer<List<E>> body) {
        for (List<E> some : inPieces(all)) {
            try {
                total.add(tallied(some, body));
            } catch (RuntimeException pieceFailed) {
                for (E one : some) {
                    try {
                        total.add(tallied(List.of(one), body));
                    } catch (RuntimeException e) {
                        failed.add(failure(userOf.apply(one), e));
                    }
                }
            }
        }
    }

    // The tally of a piece comes back only when its transaction succeeded.
    private <E> Tally tallied(List<E> some, Consumer<List<E>> body) {
        return piece(() -> {
            tally = new Tally();
            body.accept(some);
            return tally;
        });
    }

    private static Failed failure(String userId, RuntimeException e) {
        if (e instanceof FuhitoException known) {
            return new Failed(userId, known.code());
        }
        LOG.errorf(e, "The sync from user attributes failed for the user with the ID %s.", userId);
        return new Failed(userId, "internal");
    }

    private Plan plan() {
        Settings settings = from.attributes().settings();
        if (!settings.enabled()) {
            throw FuhitoException.conflict("sync.disabled", Map.of());
        }
        // With no setting, the rule for a new employment takes the only legal entity of the realm.
        employerId = settings.employerCode() == null ? null : from.orgUnits()
                .findUnitByCode(settings.employerCode(), today.ofRealm())
                .map(OrgUnit::id)
                .orElseThrow(() -> FuhitoException.conflict("sync.employer_unknown",
                        Map.of("code", settings.employerCode())));
        return new Plan(new Matcher(today.ofRealm()), ownedByUser(), from.attributes().usersWithDepartment(settings));
    }

    private void readAhead(List<Values> users, Map<String, Assignment> owned) {
        employmentsOfUsers = from.employments().findByUsers(users.stream().map(Values::userId).toList()).stream()
                .collect(Collectors.groupingBy(Employment::userId));
        linesOfAssignments = from.lines().findByAssignments(users.stream()
                        .map(user -> owned.get(user.userId()))
                        .filter(Objects::nonNull)
                        .map(Assignment::assignmentId)
                        .toList()).stream()
                .collect(Collectors.groupingBy(ReportingLine::assignmentId));
        usersFound = from.attributes().usersAmong(users.stream()
                .map(Values::managerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()));
    }

    private static <E> List<List<E>> inPieces(List<E> all) {
        List<List<E>> pieces = new ArrayList<>();
        for (int start = 0; start < all.size(); start += USERS_AT_A_TIME) {
            pieces.add(all.subList(start, Math.min(start + USERS_AT_A_TIME, all.size())));
        }
        return pieces;
    }

    // An assignment counts when it holds somewhere in the realm today, and did not end on the date of its own
    // org unit. So one that the sync wrote from the date of another unit is found again.
    private Map<String, Assignment> ownedByUser() {
        Map<String, Assignment> byAssignment = new LinkedHashMap<>();
        for (LocalDate date : today.everywhere()) {
            for (Assignment row : from.assignments().rowsBySourceOn(SOURCE, date)) {
                LocalDate unitDate = today.ofUnit(row.orgUnitId());
                if (!row.period().endsAfter(unitDate)) {
                    continue;
                }
                // The row of the date of the unit wins. Without one, the earliest row wins.
                Comparator<Assignment> best = Comparator
                        .comparing((Assignment candidate) -> !candidate.period().contains(unitDate))
                        .thenComparing(candidate -> candidate.period().from());
                byAssignment.merge(row.assignmentId(), row, BinaryOperator.minBy(best));
            }
        }
        List<Assignment> rows = List.copyOf(byAssignment.values());
        // A person with no Keycloak user has no attributes to follow, so the sync leaves their rows alone.
        Map<String, String> userByEmployment = from.employments()
                .findAll(rows.stream().map(Assignment::employmentId).distinct().toList()).stream()
                .filter(employment -> employment.userId() != null)
                .collect(Collectors.toMap(Employment::id, Employment::userId));
        Map<String, Assignment> result = new HashMap<>();
        rows.stream()
                .filter(row -> userByEmployment.containsKey(row.employmentId()))
                .forEach(row -> result.put(userByEmployment.get(row.employmentId()), row));
        return result;
    }

    private void sync(Values user, String orgUnitId, RefItem title, Assignment current, LocalDate unitDate) {
        Employment employment = employmentOf(user, unitDate);
        String employmentId = employment.id();
        // An employment that started on the date of another org unit can start after the date of this one.
        // Nothing can be assigned before the employment starts.
        LocalDate today = later(unitDate, employment.period().from());
        String assignmentId;
        if (current == null) {
            assignmentId = add(employment, orgUnitId, titleCode(user, title, null), today);
            tally.assignmentsAdded++;
        } else if (!current.orgUnitId().equals(orgUnitId) || !current.employmentId().equals(employmentId)) {
            retire(current, today);
            assignmentId = add(employment, orgUnitId, titleCode(user, title, null), today);
            tally.assignmentsMoved++;
        } else {
            assignmentId = current.assignmentId();
            today = later(today, current.period().from());
            String jobTitle = titleCode(user, title, current.jobTitleId());
            if (!Objects.equals(jobTitle, codeOf(current.jobTitleId()))) {
                written(from.assignmentService().changeDetails(assignmentId, new AssignmentValues(null, null,
                        today.toString(), null, jobTitle, codeOf(current.occupationId()), codeOf(current.kindId()),
                        codeOf(current.workLocationId()), codeOf(current.costCenterId()), current.head(),
                        current.primary(), false, current.allocationPercent(), current.displayOrder(),
                        current.attributes(), null, null)), false, "/details");
                tally.assignmentsChanged++;
            }
        }
        syncManager(user, assignmentId, today);
    }

    // A deactivated title stays on an assignment that has it. Any other assignment gets no title, as when nothing
    // has the title.
    private String titleCode(Values user, RefItem title, String currentId) {
        if (title != null && (title.active() || title.id().equals(currentId))) {
            return title.code();
        }
        if (user.title() != null) {
            tally.unmatched.add(new Unmatched(user.userId(), "title", user.title(), "unknown"));
        }
        return null;
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? b : a;
    }

    private Employment employmentOf(Values user, LocalDate today) {
        List<Employment> all = employmentsOfUsers.getOrDefault(user.userId(), List.of());
        Optional<Employment> active = all.stream()
                .filter(employment -> employment.period().contains(today))
                .findFirst()
                // One that holds today for another org unit is the same employment, a few hours early.
                .or(() -> all.stream()
                        .filter(employment -> this.today.everywhere().stream().anyMatch(employment.period()::contains))
                        .findFirst());
        if (active.isPresent()) {
            return active.get();
        }
        String personId = personOf(user.userId());
        EmploymentView started;
        try {
            started = start(personId, user.employeeNumber(), today);
        } catch (FuhitoException e) {
            // A staff number with a bad form, or one that another user has, must not stop the user.
            started = start(personId, null, today);
        }
        tally.employmentsStarted++;
        record.accept(new Write.OfEmployment(true, "", started));
        return started.employment();
    }

    // The person of a user. It is created when the user has none.
    private String personOf(String userId) {
        PersonService.Resolved person = from.personService().forUser(userId);
        if (person.created() != null) {
            record.accept(new Write.OfPerson(person.created()));
        }
        return person.person().id();
    }

    // The sync finds an employment by its user, so it needs no external ID. An ID would block a user who comes back,
    // because the old employment keeps it.
    private EmploymentView start(String personId, String staffNumber, LocalDate today) {
        return from.employmentService().start(new NewEmployment(personId, today.toString(), null, staffNumber,
                null, null, null, null, null, null, null, SOURCE, employerId));
    }

    // The assignment of the sync is the primary one, unless the user has a primary assignment from elsewhere.
    // It ends with its org unit, because the unit must exist on every date of the assignment.
    private String add(Employment employment, String orgUnitId, String jobTitle, LocalDate today) {
        boolean hasPrimary = from.assignments().rowsOfEmployment(employment.id()).stream()
                .anyMatch(row -> row.primary() && row.period().overlaps(ValidPeriod.startingAt(today)));
        LocalDate end = firstEnd(endOf(orgUnitId), employment.period().to());
        AssignmentView added = from.assignmentService().add(new AssignmentValues(employment.id(), orgUnitId,
                today.toString(), Objects.toString(end, null), jobTitle, null, null, null, !hasPrimary, false, null,
                null, null, null, SOURCE));
        written(added, true, "");
        return added.assignmentId();
    }

    private LocalDate endOf(String orgUnitId) {
        return unitEnds.computeIfAbsent(orgUnitId,
                id -> Optional.ofNullable(from.orgUnits().versions(id).getLast().period().to())).orElse(null);
    }

    // Null is no end.
    private static LocalDate firstEnd(LocalDate a, LocalDate b) {
        return a == null || (b != null && b.isBefore(a)) ? b : a;
    }

    // An assignment that started today never held, so it is removed. Any other ends today.
    private void retire(Assignment row, LocalDate today) {
        List<Assignment> rows = from.assignments().rows(row.assignmentId());
        if (!rows.getFirst().period().from().isBefore(today)) {
            from.lines().findByAssignment(row.assignmentId()).forEach(line -> {
                from.lines().delete(line.id());
                record.accept(Write.Removed.line(line.id()));
            });
            rows.forEach(each -> from.assignments().delete(each.rowId()));
            record.accept(Write.Removed.assignment(row.assignmentId()));
            return;
        }
        written(from.assignmentService().end(row.assignmentId(), today.toString()), false, "/end");
    }

    private void syncManager(Values user, String assignmentId, LocalDate today) {
        String wanted = user.managerUserId();
        if (wanted != null && (wanted.equals(user.userId()) || !exists(wanted))) {
            tally.unmatched.add(new Unmatched(user.userId(), "manager", wanted, "unknown"));
            wanted = null;
        }
        // A new assignment has no lines, and the read ahead has none for it.
        Optional<ReportingLine> current = linesOfAssignments.getOrDefault(assignmentId, List.of()).stream()
                .filter(line -> line.kind() == ReportingLine.Kind.SOLID && line.period().contains(today))
                .findFirst();
        if (Objects.equals(wanted, current.map(ReportingLine::managerUserId).orElse(null))) {
            return;
        }
        String managerPersonId = wanted == null ? null : personOf(wanted);
        if (current.isPresent()) {
            String id = current.get().id();
            if (current.get().period().from().equals(today)) {
                from.lineService().delete(id);
                record.accept(Write.Removed.line(id));
            } else {
                record.accept(new Write.OfReportingLine(false, "/end", from.lineService().end(id, today.toString())));
            }
        }
        if (wanted != null) {
            record.accept(new Write.OfReportingLine(true, "",
                    from.lineService().add(assignmentId, managerPersonId, "SOLID", today.toString(), null)));
        }
        tally.reportingLinesChanged++;
    }

    // A user of a user storage provider is not in the tables of Keycloak, so the API looks for one that is missing.
    private boolean exists(String userId) {
        return usersFound.contains(userId) || from.users().exists(userId);
    }

    private void written(AssignmentView view, boolean created, String suffix) {
        record.accept(new Write.OfAssignment(created, suffix, view));
    }

    private String codeOf(String refItemId) {
        return Optional.ofNullable(refItemId).flatMap(from.refItems()::find).map(RefItem::code).orElse(null);
    }

    /**
     * Matches the text of an identity provider to org units and job titles.
     */
    private final class Matcher {

        private final Map<String, String> unitByCode = new HashMap<>();
        private final Map<String, List<String>> unitsByName = new HashMap<>();
        private final Map<String, RefItem> titles = new HashMap<>();

        Matcher(LocalDate today) {
            for (OrgUnitState unit : new OrgUnitTree(from.orgUnits(), from.refItems(), today).all()) {
                if (unit.version().code() != null) {
                    unitByCode.put(unit.version().code(), unit.unit().id());
                }
                unitsByName.computeIfAbsent(unit.version().name(), name -> new ArrayList<>()).add(unit.unit().id());
            }
            // The code wins over the name, and an active title wins over a deactivated one.
            List<RefItem> all = from.refItems().list(RefKind.JOB_TITLE, true);
            for (boolean active : List.of(true, false)) {
                List<RefItem> some = all.stream().filter(item -> item.active() == active).toList();
                some.forEach(item -> titles.putIfAbsent(item.code(), item));
                some.forEach(item -> titles.putIfAbsent(item.name(), item));
            }
        }

        // The code wins over the name. A name that two units share matches neither.
        Optional<String> orgUnit(Values user) {
            String department = user.department();
            String byCode = unitByCode.get(department);
            if (byCode != null) {
                return Optional.of(byCode);
            }
            List<String> byName = unitsByName.getOrDefault(department, List.of());
            if (byName.size() == 1) {
                return Optional.of(byName.getFirst());
            }
            tally.unmatched.add(new Unmatched(user.userId(), "department", department,
                    byName.isEmpty() ? "unknown" : "ambiguous"));
            return Optional.empty();
        }

        // A deactivated title matches too, so that an assignment that has it can keep it.
        RefItem jobTitle(Values user) {
            return user.title() == null ? null : titles.get(user.title());
        }
    }
}
