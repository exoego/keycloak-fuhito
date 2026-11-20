package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ImportDocument.AssignmentRow;
import net.exoego.keycloak.fuhito.service.ImportReport.Outcome;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * The list of assignments of one document. The writes of the list come into the report in the order in which its
 * rows are applied, once the new assignments are stored.
 */
final class AssignmentImport {

    private static final String ASSIGNMENTS = "assignments";

    private record Link(String id, String continues, LocalDate from) {
    }

    private final PeopleImport document;
    private final ListIds ids;
    private final AssignmentService service;
    private final AssignmentService.NewAssignments added;
    private final Map<String, Optional<AssignmentView>> stored = new HashMap<>();
    private final Map<String, Boolean> units = new HashMap<>();
    private final List<Supplier<Write>> writes = new ArrayList<>();
    private final Map<String, AssignmentView> views = new HashMap<>();
    // The new assignments that continue another, by row. The rows that set an end, by assignment.
    private final Map<Integer, Link> continuing = new HashMap<>();
    private final Map<String, Integer> ends = new HashMap<>();

    AssignmentImport(PeopleImport document) {
        this.document = document;
        this.ids = new ListIds(document.externalIds);
        this.service = document.people.assignmentService().remembering();
        this.added = service.newAssignments(
                (source, value) -> ids.resourceOf(ExternalId.Kind.ASSIGNMENT, source, value));
    }

    void run(List<AssignmentRow> rows) {
        // A row that ends or gives up the primary flag is applied before a row that takes the flag.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> rows.get(i) != null && rows.get(i).validTo() == null
                && Boolean.TRUE.equals(rows.get(i).primary())));
        document.report.startList(ASSIGNMENTS);
        read(rows);
        Set<String> seen = new HashSet<>();
        Map<Integer, Outcome> outcomes = new HashMap<>();
        for (int i : order) {
            try {
                AssignmentRow row = EmptyValues.checked(rows.get(i));
                document.requireFirst(seen, row.source(), row.externalId(), row.id());
                outcomes.put(i, apply(row, i));
            } catch (FuhitoException e) {
                document.report.error(ASSIGNMENTS, i, e);
            }
            document.rowDone.run();
        }
        added.insert();
        unended().forEach((row, e) -> {
            outcomes.remove(row);
            document.report.error(ASSIGNMENTS, row, e);
        });
        outcomes.values().forEach(outcome -> document.report.count(ASSIGNMENTS, outcome));
        views.putAll(added.views());
        writes.forEach(write -> document.report.write(write.get()));
        document.rowDone.run();
    }

    // The stored assignments that the rows name. The employments with the rows of their assignments, and the org
    // units, of the new ones.
    private void read(List<AssignmentRow> rows) {
        List<AssignmentRow> given = rows.stream().filter(EmptyValues::passes).toList();
        for (AssignmentRow row : given) {
            String source = document.sourceOf(row.source());
            ids.want(ExternalId.Kind.ASSIGNMENT, source, row.externalId())
                    .want(ExternalId.Kind.ASSIGNMENT, source, row.continuesAssignmentExternalId())
                    .want(ExternalId.Kind.EMPLOYMENT, source, row.employmentExternalId())
                    .want(ExternalId.Kind.ORG_UNIT, source, row.orgUnitExternalId());
        }
        ids.read();
        List<Set<String>> named = given.stream()
                .map(row -> document.named(ids, row.id(), ExternalId.Kind.ASSIGNMENT, row.source(), row.externalId()))
                .toList();
        Set<String> assignmentIds = new HashSet<>();
        named.forEach(assignmentIds::addAll);
        Map<String, AssignmentView> found = service.findAll(assignmentIds);
        assignmentIds.forEach(id -> stored.put(id, Optional.ofNullable(found.get(id))));
        Set<String> employmentIds = new HashSet<>();
        Set<String> orgUnitIds = new HashSet<>();
        for (int i = 0; i < given.size(); i++) {
            AssignmentRow row = given.get(i);
            if (row.employmentId() != null) {
                employmentIds.add(row.employmentId());
            }
            if (named.get(i).stream().noneMatch(found::containsKey)) {
                employmentIds.addAll(document.named(ids, null, ExternalId.Kind.EMPLOYMENT, row.source(),
                        row.employmentExternalId()));
                orgUnitIds.addAll(document.named(ids, row.orgUnitId(), ExternalId.Kind.ORG_UNIT, row.source(),
                        row.orgUnitExternalId()));
            }
        }
        added.read(employmentIds, orgUnitIds);
    }

    private Optional<AssignmentView> find(String id) {
        return stored.computeIfAbsent(id, key -> Optional.ofNullable(service.findAll(List.of(key)).get(key)));
    }

    private Outcome apply(AssignmentRow row, int index) {
        String source = Checks.code("source", row.source() == null ? document.defaultSource : row.source());
        String name = row.externalId() == null ? row.id() : row.externalId();
        Optional<String> storedId = row.id() == null
                ? ids.resourceOf(ExternalId.Kind.ASSIGNMENT, source, row.externalId())
                : Optional.of(byId(row, source));
        Optional<Assignment> firstRow = storedId.map(id -> find(id).orElseThrow().rows().getFirst().row());
        String employmentId = employmentId(row, source, firstRow);
        String orgUnitId = orgUnitId(row, source, firstRow);
        String continues = continued(row, source);
        if (storedId.isEmpty()) {
            AssignmentValues values = new AssignmentValues(employmentId, orgUnitId,
                    row.validFrom() == null ? document.effectiveDate : row.validFrom(), row.validTo(), row.jobTitle(),
                    row.occupation(), row.kind(), row.workLocation(), row.costCenter(), row.head(), row.primary(),
                    false, row.allocationPercent(), row.displayOrder(), Merged.attributes(row, row.attributes(), null),
                    row.externalId(), source);
            String id = ids.create(ExternalId.Kind.ASSIGNMENT, source, row.externalId(),
                    () -> added.add(values, continues), Function.identity());
            writes.add(() -> new Write.OfAssignment(true, "", views.get(id)));
            if (continues != null) {
                continuing.put(index, new Link(id, continues, Checks.date("validFrom", values.validFrom())));
            }
            return Outcome.CREATED;
        }
        String id = storedId.get();
        AssignmentView before = find(id).orElseThrow();
        Assignment first = before.rows().getFirst().row();
        if (!first.employmentId().equals(employmentId) || !first.orgUnitId().equals(orgUnitId)) {
            throw FuhitoException.conflict("import.assignment_mismatch", Map.of("externalId", name));
        }
        boolean givesContinued = row.gives("continuesAssignmentExternalId", row.continuesAssignmentExternalId())
                || row.gives("continuesAssignmentId", row.continuesAssignmentId());
        if (givesContinued && !Objects.equals(continues, first.continuesAssignmentId())) {
            throw FuhitoException.conflict("import.continues_mismatch", Map.of("externalId", name));
        }
        ImportMatch.start(row, id, before.period().from(), row.validFrom());
        boolean changed = false;
        LocalDate date = Checks.date("effectiveDate", document.effectiveDate);
        boolean endsBefore = !before.period().endsAfter(date);
        if (!endsBefore) {
            boolean later = before.period().from().isAfter(date);
            LocalDate on = later ? before.period().from() : date;
            AssignmentView.Row current = before.rows().stream()
                    .filter(r -> r.row().period().contains(on))
                    .findFirst()
                    .orElseThrow(() -> FuhitoException.notFound("assignment.not_valid_on",
                            Map.of("id", id, "date", document.effectiveDate)));
            AssignmentValues wanted = details(row, current);
            if (!sameDetails(wanted, current)) {
                if (later) {
                    throw FuhitoException.notFound("assignment.not_valid_on",
                            Map.of("id", id, "date", document.effectiveDate));
                }
                written("/details", first, () -> service.changeDetails(id, wanted));
                changed = true;
            }
        }
        String storedEnd = Objects.toString(before.period().to(), null);
        String validTo = row.or("validTo", row.validTo(), storedEnd);
        // With no end of its own, an assignment ends with its employment.
        if (validTo == null && !Objects.equals(storedEnd, Objects.toString(document.people.employments()
                .find(first.employmentId()).orElseThrow().period().to(), null))) {
            written("/end", first, () -> service.removeEnd(id));
            changed = true;
        } else if (validTo != null && !validTo.equals(storedEnd)) {
            written("/end", first, () -> service.end(id, validTo, false));
            ends.put(id, index);
            changed = true;
        }
        return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
    }

    private AssignmentValues details(AssignmentRow row, AssignmentView.Row current) {
        Assignment stored = current.row();
        return new AssignmentValues(null, null, document.effectiveDate, null,
                row.or("jobTitle", row.jobTitle(), current.jobTitle()),
                row.or("occupation", row.occupation(), current.occupation()),
                row.or("kind", row.kind(), current.kind()),
                row.or("workLocation", row.workLocation(), current.places().workLocation()),
                row.or("costCenter", row.costCenter(), current.places().costCenter()),
                row.or("head", row.head(), stored.head()), row.or("primary", row.primary(), stored.primary()), false,
                row.or("allocationPercent", row.allocationPercent(), stored.allocationPercent()),
                row.or("displayOrder", row.displayOrder(), stored.displayOrder()),
                Merged.attributes(row, row.attributes(), stored.attributes()), null, null);
    }

    // An assignment that another one continues must end by the start of that one. Its rows end when its employment
    // ends, so they give its end. The row that ends it may come after the row of the new one, so the check looks at
    // what the whole list made. It also keeps a later end from opening such an assignment again.
    private Map<Integer, FuhitoException> unended() {
        if (continuing.isEmpty() && ends.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = new HashSet<>(ends.keySet());
        continuing.values().forEach(link -> ids.add(link.continues()));
        Map<String, List<Assignment>> found = document.people.assignments().rowsOf(ids).stream()
                .collect(Collectors.groupingBy(Assignment::assignmentId));
        Map<Integer, FuhitoException> failed = new TreeMap<>();
        continuing.forEach((row, link) -> holds(found.get(link.continues()), link.from())
                .ifPresent(e -> failed.put(row, e)));
        if (ends.isEmpty()) {
            return failed;
        }
        // A new one is checked on its own row.
        Set<String> newIds = continuing.values().stream().map(Link::id).collect(Collectors.toSet());
        List<Assignment> ended = ends.keySet().stream().map(id -> found.get(id).getFirst()).toList();
        for (Assignment next : service.continuing(ended)) {
            if (!newIds.contains(next.assignmentId())) {
                holds(found.get(next.continuesAssignmentId()), next.period().from())
                        .ifPresent(e -> failed.putIfAbsent(ends.get(next.continuesAssignmentId()), e));
            }
        }
        return failed;
    }

    private static Optional<FuhitoException> holds(List<Assignment> previous, LocalDate from) {
        Assignment last = previous.getLast();
        return AssignmentService.stillHolds(last.assignmentId(), last.period(), from);
    }

    private void written(String suffix, Assignment first, Supplier<AssignmentView> write) {
        added.insert();
        AssignmentView after = write.get();
        stored.remove(first.assignmentId());
        added.forget(first.employmentId());
        writes.add(() -> new Write.OfAssignment(false, suffix, after));
    }

    // A row with an ID changes that assignment. An external ID beside it must be one of that assignment.
    private String byId(AssignmentRow row, String source) {
        if (find(row.id()).isEmpty()) {
            throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.id()));
        }
        PeopleImport.requireNames(ids, ExternalId.Kind.ASSIGNMENT, row.id(), source, row.externalId());
        return row.id();
    }

    private String continued(AssignmentRow row, String source) {
        if (row.continuesAssignmentExternalId() != null && row.continuesAssignmentId() != null) {
            throw FuhitoException.invalid("import.continues_twice", Map.of());
        }
        if (row.continuesAssignmentExternalId() == null) {
            return row.continuesAssignmentId();
        }
        return ids.resourceOf(ExternalId.Kind.ASSIGNMENT, source, row.continuesAssignmentExternalId())
                .orElseThrow(() -> FuhitoException.invalid("import.assignment_unknown",
                        Map.of("assignmentExternalId", row.continuesAssignmentExternalId())));
    }

    // A row of a stored assignment that leaves out the employment keeps the stored one.
    private String employmentId(AssignmentRow row, String source, Optional<Assignment> stored) {
        if (row.employmentExternalId() != null && row.employmentId() != null) {
            throw FuhitoException.invalid("import.employment_twice",
                    Map.of("employmentExternalId", row.employmentExternalId(), "employmentId", row.employmentId()));
        }
        if (row.employmentId() != null) {
            if (added.employment(row.employmentId()).isEmpty()) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.employmentId()));
            }
            return row.employmentId();
        }
        if (!row.gives("employmentExternalId", row.employmentExternalId()) && !row.isNull("employmentId")
                && stored.isPresent()) {
            return stored.get().employmentId();
        }
        String externalId = Checks.required("employmentExternalId", row.employmentExternalId());
        return ids.resourceOf(ExternalId.Kind.EMPLOYMENT, source, externalId)
                .orElseThrow(() -> FuhitoException.invalid("import.employment_unknown",
                        Map.of("employmentExternalId", externalId)));
    }

    // A row of a stored assignment that leaves out the org unit keeps the stored one.
    private String orgUnitId(AssignmentRow row, String source, Optional<Assignment> stored) {
        if (row.orgUnitExternalId() != null && row.orgUnitId() != null) {
            throw FuhitoException.invalid("import.org_unit_twice",
                    Map.of("orgUnitExternalId", row.orgUnitExternalId(), "orgUnitId", row.orgUnitId()));
        }
        if (row.orgUnitId() != null) {
            if (!units.computeIfAbsent(row.orgUnitId(), id -> document.orgUnits.findUnit(id).isPresent())) {
                throw FuhitoException.invalid("import.id_unknown", Map.of("id", row.orgUnitId()));
            }
            return row.orgUnitId();
        }
        if (!row.gives("orgUnitExternalId", row.orgUnitExternalId()) && !row.isNull("orgUnitId")
                && stored.isPresent()) {
            return stored.get().orgUnitId();
        }
        String externalId = Checks.required("orgUnitExternalId", row.orgUnitExternalId());
        return ids.resourceOf(ExternalId.Kind.ORG_UNIT, source, externalId)
                .orElseThrow(() -> FuhitoException.invalid("import.org_unit_unknown",
                        Map.of("orgUnitExternalId", externalId)));
    }

    private static boolean sameDetails(AssignmentValues wanted, AssignmentView.Row current) {
        Assignment stored = current.row();
        return Objects.equals(wanted.jobTitle(), current.jobTitle())
                && Objects.equals(wanted.occupation(), current.occupation())
                && Objects.equals(wanted.kind(), current.kind())
                && Objects.equals(wanted.workLocation(), current.places().workLocation())
                && Objects.equals(wanted.costCenter(), current.places().costCenter())
                && Boolean.TRUE.equals(wanted.head()) == stored.head()
                && Boolean.TRUE.equals(wanted.primary()) == stored.primary()
                && PeopleImport.sameNumber(wanted.allocationPercent(), stored.allocationPercent())
                && (wanted.displayOrder() == null ? 0 : wanted.displayOrder()) == stored.displayOrder()
                && (wanted.attributes() == null ? Map.of() : wanted.attributes()).equals(stored.attributes());
    }
}
