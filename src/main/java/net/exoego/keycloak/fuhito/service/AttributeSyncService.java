package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final int USERS_AT_A_TIME = 100;

    private final Work work;
    // The parts of the piece that runs now.
    private Sources from;
    private Today today;
    private Consumer<Write> record;

    private final List<Unmatched> unmatched = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();
    private String employerId;
    private int employmentsStarted;
    private int assignmentsAdded;
    private int assignmentsMoved;
    private int assignmentsChanged;
    private int assignmentsEnded;
    private int reportingLinesChanged;

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
     * The report of the run then has the writes.
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
     * The work is done in pieces. When one fails, the pieces before it stay, and the next run goes on.
     *
     * @return what the run did
     * @throws FuhitoException when the realm did not turn the sync on, or another run is going on
     */
    public SyncReport run() {
        return work.alone(this::runAlone);
    }

    private SyncReport runAlone() {
        Plan plan = piece(this::plan);
        Set<String> withDepartment = new HashSet<>();
        for (List<Values> some : inPieces(plan.users())) {
            piece(() -> {
                for (Values user : some) {
                    withDepartment.add(user.userId());
                    Optional<String> orgUnitId = plan.matcher().orgUnit(user);
                    if (orgUnitId.isPresent()) {
                        // Every change for this user takes effect on the date of the matched org unit.
                        sync(user, orgUnitId.get(), plan.matcher().jobTitle(user), plan.owned().get(user.userId()),
                                today.ofUnit(orgUnitId.get()));
                    }
                }
                return null;
            });
        }
        // A user whose department was removed keeps no assignment from the sync.
        List<Assignment> left = plan.owned().entrySet().stream()
                .filter(entry -> !withDepartment.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        for (List<Assignment> some : inPieces(left)) {
            piece(() -> {
                for (Assignment row : some) {
                    retire(row, today.ofUnit(row.orgUnitId()));
                    assignmentsEnded++;
                }
                return null;
            });
        }
        return new SyncReport(plan.users().size(), employmentsStarted, assignmentsAdded, assignmentsMoved,
                assignmentsChanged, assignmentsEnded, reportingLinesChanged, List.copyOf(unmatched),
                List.copyOf(writes));
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

    private void sync(Values user, String orgUnitId, String jobTitle, Assignment current, LocalDate unitDate) {
        Employment employment = employmentOf(user, unitDate);
        String employmentId = employment.id();
        // An employment that started on the date of another org unit can start after the date of this one.
        // Nothing can be assigned before the employment starts.
        LocalDate today = later(unitDate, employment.period().from());
        String assignmentId;
        if (current == null) {
            assignmentId = add(employmentId, orgUnitId, jobTitle, today);
            assignmentsAdded++;
        } else if (!current.orgUnitId().equals(orgUnitId) || !current.employmentId().equals(employmentId)) {
            retire(current, today);
            assignmentId = add(employmentId, orgUnitId, jobTitle, today);
            assignmentsMoved++;
        } else {
            assignmentId = current.assignmentId();
            today = later(today, current.period().from());
            if (!Objects.equals(jobTitle, codeOf(current.jobTitleId()))) {
                written(from.assignmentService().changeDetails(assignmentId, new AssignmentValues(null, null,
                        today.toString(), null, jobTitle, codeOf(current.occupationId()), codeOf(current.kindId()),
                        current.head(), current.primary(), false, current.allocationPercent(),
                        current.displayOrder(), current.attributes(), null, null)), false, "/details");
                assignmentsChanged++;
            }
        }
        syncManager(user, assignmentId, today);
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? b : a;
    }

    private Employment employmentOf(Values user, LocalDate today) {
        List<Employment> all = from.employments().findByUser(user.userId());
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
            started = start(user, personId, user.employeeNumber(), today);
        } catch (FuhitoException e) {
            // A staff number with a bad form, or one that another user has, must not stop the user.
            started = start(user, personId, null, today);
        }
        employmentsStarted++;
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

    private EmploymentView start(Values user, String personId, String staffNumber, LocalDate today) {
        return from.employmentService().start(new NewEmployment(personId, today.toString(), null, staffNumber,
                null, null, null, null, null, null, user.userId(), SOURCE, employerId));
    }

    // The assignment of the sync is the primary one, unless the user has a primary assignment from elsewhere.
    private String add(String employmentId, String orgUnitId, String jobTitle, LocalDate today) {
        boolean hasPrimary = from.assignments().rowsOfEmployment(employmentId).stream()
                .anyMatch(row -> row.primary() && row.period().overlaps(ValidPeriod.startingAt(today)));
        AssignmentView added = from.assignmentService().add(new AssignmentValues(employmentId, orgUnitId,
                today.toString(), null, jobTitle, null, null, null, !hasPrimary, false, null, null, null, null,
                SOURCE));
        written(added, true, "");
        return added.assignmentId();
    }

    // An assignment that started today never held, so it is removed. Any other ends today.
    private void retire(Assignment row, LocalDate today) {
        List<Assignment> rows = from.assignments().rows(row.assignmentId());
        if (!rows.getFirst().period().from().isBefore(today)) {
            from.lines().findByAssignment(row.assignmentId()).forEach(line -> from.lines().delete(line.id()));
            rows.forEach(each -> from.assignments().delete(each.rowId()));
            return;
        }
        written(from.assignmentService().end(row.assignmentId(), today.toString()), false, "/end");
    }

    private void syncManager(Values user, String assignmentId, LocalDate today) {
        String wanted = user.managerUserId();
        if (wanted != null && (wanted.equals(user.userId()) || !from.users().exists(wanted))) {
            unmatched.add(new Unmatched(user.userId(), "manager", wanted, "unknown"));
            wanted = null;
        }
        Optional<ReportingLine> current = from.lines().findByAssignment(assignmentId).stream()
                .filter(line -> line.kind() == ReportingLine.Kind.SOLID && line.period().contains(today))
                .findFirst();
        if (Objects.equals(wanted, current.map(ReportingLine::managerUserId).orElse(null))) {
            return;
        }
        String managerPersonId = wanted == null ? null : personOf(wanted);
        if (current.isPresent()) {
            if (current.get().period().from().equals(today)) {
                from.lineService().delete(current.get().id());
            } else {
                from.lineService().end(current.get().id(), today.toString());
            }
        }
        if (wanted != null) {
            from.lineService().add(assignmentId, managerPersonId, "SOLID", today.toString(), null);
        }
        reportingLinesChanged++;
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
        private final Map<String, String> titleByCode;
        private final Map<String, String> titleByName;

        Matcher(LocalDate today) {
            for (OrgUnitState unit : new OrgUnitTree(from.orgUnits(), from.refItems(), today).all()) {
                if (unit.version().code() != null) {
                    unitByCode.put(unit.version().code(), unit.unit().id());
                }
                unitsByName.computeIfAbsent(unit.version().name(), name -> new ArrayList<>()).add(unit.unit().id());
            }
            List<RefItem> titles = from.refItems().list(RefKind.JOB_TITLE, false);
            titleByCode = titles.stream().collect(Collectors.toMap(RefItem::code, RefItem::code));
            titleByName = titles.stream().collect(Collectors.toMap(RefItem::name, RefItem::code, (a, b) -> a));
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
            unmatched.add(new Unmatched(user.userId(), "department", department,
                    byName.isEmpty() ? "unknown" : "ambiguous"));
            return Optional.empty();
        }

        String jobTitle(Values user) {
            if (user.title() == null) {
                return null;
            }
            String code = Optional.ofNullable(titleByCode.get(user.title())).orElse(titleByName.get(user.title()));
            if (code == null) {
                unmatched.add(new Unmatched(user.userId(), "title", user.title(), "unknown"));
            }
            return code;
        }
    }
}
