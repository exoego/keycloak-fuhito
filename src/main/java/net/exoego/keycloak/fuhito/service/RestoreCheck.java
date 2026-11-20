package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Checks an export before a restore writes it. A row may name only rows of the same export, so that a restore
 * never links to the rows of another realm, and fails with a clear error and not in the database. A row has no empty
 * value and no text that cannot be stored, as a row of an import, each of its texts fits its column, and its period
 * ends after it starts.
 */
final class RestoreCheck {

    // Any rule will do for the check of the time settings of a unit.
    private static final BusinessDay.Rule ANY_DAY = new BusinessDay.Rule(ZoneOffset.UTC, LocalTime.MIDNIGHT);

    private static final Set<String> DATES = Set.of("validFrom", "validTo", "effectiveDate", "orderDate",
            "contractEndDate");

    // A time must be on a date that a write takes, in UTC.
    private static final Set<String> TIMES = Set.of("createdAt", "announcedAt", "maskedAt", "erasedAt");

    private final Set<String> refItems;
    private final Set<String> orgUnits;
    private final Set<String> people;
    private final Set<String> employments;
    private final Set<String> assignments;
    private final Set<String> qualifications;
    private final Set<String> lines;
    private final Set<String> roles;
    private final Set<String> orders;
    private final Set<String> refKinds;
    private final Set<String> hierarchies;
    private final Set<String> definitions;

    private RestoreCheck(RealmExport document) {
        refItems = ids(document.refItems(), RealmExport.Item::id);
        orgUnits = ids(document.orgUnits(), RealmExport.Unit::id);
        people = ids(document.people(), RealmExport.PersonRow::id);
        employments = ids(document.employments(), RealmExport.EmploymentRow::id);
        assignments = ids(document.assignments(), RealmExport.AssignmentRows::assignmentId);
        qualifications = ids(document.qualifications(), RealmExport.QualificationRow::id);
        lines = ids(document.reportingLines(), RealmExport.Line::id);
        roles = ids(document.unitRoles(), RealmExport.Role::id);
        orders = ids(document.changeOrders(), RealmExport.Order::id);
        refKinds = ids(document.refKinds(), RealmExport.Kind::id);
        hierarchies = ids(document.hierarchies(), RealmExport.HierarchyRow::id);
        definitions = ids(document.attributeDefinitions(), RealmExport.Definition::id);
    }

    /**
     * Requires every row of an export and every value that the table of a row needs, and requires that no value is an
     * empty text, a text of only spaces, an empty list, or a text that cannot be stored, as in a row of an import. A
     * text must fit its column, and a value that a write checks by a rule of its own, such as a code or a date, must
     * pass the rule. The {@code validTo} of a row must be after its {@code validFrom}, as in a write. A row inside a
     * row, such as a version of an org unit, is named by the list and the ID that {@code restore.id_taken} gives it.
     *
     * @param document the export
     * @param lengths gives the longest text of each field of a row of a list
     * @throws FuhitoException when a row or a needed value is missing, a value is empty, a text cannot be stored or
     *         is too long for its column, a value breaks its rule, or a period does not end after it starts. The
     *         error names the list, the ID of the row when it has one, and the field.
     */
    static void requireValues(RealmExport document, Function<String, Map<String, Integer>> lengths) {
        Values values = new Values(lengths);
        values.check("ref-kinds", document.refKinds(), "id", "code");
        values.check("ref-items", document.refItems(), "id", "kind", "code", "attributes");
        all(document.refItems()).forEach(i -> values.check("ref-item-names", i.versions(), "id"));
        values.check("attribute-definitions", document.attributeDefinitions(), "id", "target", "name", "type");
        values.check("hierarchies", document.hierarchies(), "id", "code", "name");
        values.check("org-units", document.orgUnits(), "id");
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            values.check("org-unit-versions", unit.versions(), "id", "validFrom", "attributes");
            values.check("org-unit-parents", unit.parents(), "id", "hierarchy", "parentId", "validFrom");
            values.check("org-unit-successions", unit.successors(), "id", "successorId", "effectiveDate");
        }
        values.check("people", document.people(), "id", "attributes");
        values.check("employments", document.employments(), "id", "personId", "validFrom");
        all(document.employments()).forEach(e -> values.check("employment-terms", e.terms(), "id", "validFrom",
                "status", "attributes"));
        values.check("assignments", document.assignments(), "assignmentId");
        all(document.assignments()).forEach(a -> values.check("assignments", a.periods(), "rowId", "employmentId",
                "orgUnitId", "validFrom", "attributes"));
        values.check("reporting-lines", document.reportingLines(), "id", "assignmentId", "managerPersonId", "kind",
                "validFrom");
        values.check("unit-roles", document.unitRoles(), "id", "employmentId", "orgUnitId", "roleId", "validFrom");
        values.check("qualifications", document.qualifications(), "id", "personId", "kindId", "validFrom",
                "attributes");
        values.check("external-ids", document.externalIds(), "id", "resourceType", "resourceId", "source",
                "externalId");
        values.check("change-orders", document.changeOrders(), "id", "title", "effectiveDate", "createdAt");
        values.check("erasures", document.erasures(), "personId", "erasedAt");
    }

    private record Values(Function<String, Map<String, Integer>> lengths) {

        // The needed fields have columns that are not null, and the first one is the ID of the row. A list in a row
        // holds the rows of the row, and a row may have none. Each of them is checked as a row.
        <T extends Record> void check(String list, List<T> rows, String... needed) {
            Map<String, Integer> longest = lengths.apply(list);
            for (T row : all(rows)) {
                if (row == null) {
                    throw FuhitoException.invalid("restore.bad_value", Map.of("list", list));
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                for (RecordComponent component : row.getClass().getRecordComponents()) {
                    fields.put(component.getName(), EmptyValues.valueOf(component, row));
                }
                Map<String, String> params = new HashMap<>(Map.of("list", list));
                Optional.ofNullable(fields.get(needed[0])).ifPresent(id -> params.put("id", id.toString()));
                for (String field : needed) {
                    if (fields.get(field) == null) {
                        params.put("field", field);
                        throw FuhitoException.invalid("restore.bad_value", params);
                    }
                }
                try {
                    EmptyValues.withoutEmptyValues(row, component -> component.getType() != List.class);
                } catch (FuhitoException e) {
                    Map<String, String> named = new HashMap<>(e.params());
                    named.putAll(params);
                    throw new FuhitoException(e.reason(), e.code(), named).causedBy(e);
                }
                fields.forEach((field, value) -> requireFit(params, field, value, longest.get(field)));
                requireOrder(params, (String) fields.get("validFrom"), (String) fields.get("validTo"));
            }
        }
    }

    // Names with no start hold from the start, so any end fits them.
    private static void requireOrder(Map<String, String> params, String from, String to) {
        if (from == null || to == null) {
            return;
        }
        try {
            Checks.period(LocalDate.parse(from), LocalDate.parse(to));
        } catch (FuhitoException e) {
            Map<String, String> named = new HashMap<>(params);
            named.put("field", "validTo");
            throw FuhitoException.invalid("restore.bad_value", named).causedBy(e);
        }
    }

    // A text must fit its column, and a value that a write checks by a rule of its own must pass the rule.
    private static void requireFit(Map<String, String> params, String field, Object value, Integer length) {
        Map<String, String> named = new HashMap<>(params);
        named.put("field", field);
        if (value instanceof String text && length != null && !Checks.fits(text, length)) {
            throw FuhitoException.invalid("restore.bad_value", named);
        }
        try {
            rule(params.get("list"), field, value);
        } catch (FuhitoException | DateTimeException e) {
            throw FuhitoException.invalid("restore.bad_value", named).causedBy(e);
        }
    }

    private static void rule(String list, String field, Object value) {
        if (value == null) {
            return;
        }
        if (DATES.contains(field)) {
            Checks.optionalDate(field, (String) value);
        } else if (TIMES.contains(field)) {
            Checks.inRange(LocalDate.ofInstant(Instant.parse((String) value), ZoneOffset.UTC));
        }
        switch (list + "." + field) {
            case "ref-kinds.code", "ref-items.code", "hierarchies.code", "org-unit-versions.code", "org-units.source",
                    "people.source", "employments.source", "employments.staffNumber", "assignments.source",
                    "qualifications.source", "external-ids.source", "change-orders.number", "change-orders.kind" ->
                    Checks.code(field, (String) value);
            case "hierarchies.name", "qualifications.number", "change-orders.title" ->
                    Checks.name(field, (String) value);
            case "attribute-definitions.name" -> AttributeDefinitionService.name((String) value);
            case "external-ids.externalId" -> ExternalIdService.value((String) value);
            case "employment-terms.workPercent", "assignments.allocationPercent" ->
                    Checks.optionalPercent(field, (BigDecimal) value);
            case "org-units.timeZone" -> ANY_DAY.with((String) value, null);
            case "org-units.dayStart" -> ANY_DAY.with(null, (String) value);
            default -> {
            }
        }
    }

    /**
     * Requires that every reference of an export names a row of the same export.
     *
     * @param document the export
     * @throws FuhitoException when a reference names a row that the export does not have
     */
    static void requireReferences(RealmExport document) {
        new RestoreCheck(document).check(document);
    }

    private void check(RealmExport document) {
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            for (RealmExport.Version v : all(unit.versions())) {
                optional("org-units", "unitTypeId", v.unitTypeId(), refItems);
                optional("org-units", "workLocationId", v.workLocationId(), refItems);
                optional("org-units", "costCenterId", v.costCenterId(), refItems);
            }
            all(unit.parents()).forEach(p -> required("org-units", "parentId", p.parentId(), orgUnits));
            all(unit.successors()).forEach(s -> required("org-units", "successorId", s.successorId(), orgUnits));
        }
        for (RealmExport.EmploymentRow e : all(document.employments())) {
            required("employments", "personId", e.personId(), people);
            if (e.externalEmployerId() == null) {
                required("employments", "employerId", e.employerId(), orgUnits);
            } else if (e.employerId() != null) {
                throw FuhitoException.invalid("employment.employer_twice",
                        Map.of("externalEmployer", e.externalEmployerId()));
            } else {
                required("employments", "externalEmployerId", e.externalEmployerId(), refItems);
            }
            optional("employments", "endReasonId", e.endReasonId(), refItems);
            optional("employments", "continuesEmploymentId", e.continuesEmploymentId(), employments);
            for (RealmExport.Terms t : all(e.terms())) {
                optional("employments", "employmentTypeId", t.employmentTypeId(), refItems);
                optional("employments", "gradeId", t.gradeId(), refItems);
                optional("employments", "leaveReasonId", t.leaveReasonId(), refItems);
            }
        }
        for (RealmExport.AssignmentRows a : all(document.assignments())) {
            for (RealmExport.Period p : all(a.periods())) {
                required("assignments", "employmentId", p.employmentId(), employments);
                required("assignments", "orgUnitId", p.orgUnitId(), orgUnits);
                optional("assignments", "jobTitleId", p.jobTitleId(), refItems);
                optional("assignments", "occupationId", p.occupationId(), refItems);
                optional("assignments", "kindId", p.kindId(), refItems);
                optional("assignments", "workLocationId", p.workLocationId(), refItems);
                optional("assignments", "costCenterId", p.costCenterId(), refItems);
                optional("assignments", "continuesAssignmentId", p.continuesAssignmentId(), assignments);
            }
        }
        for (RealmExport.Line l : all(document.reportingLines())) {
            required("reporting-lines", "assignmentId", l.assignmentId(), assignments);
            required("reporting-lines", "managerPersonId", l.managerPersonId(), people);
        }
        for (RealmExport.Role r : all(document.unitRoles())) {
            required("unit-roles", "employmentId", r.employmentId(), employments);
            required("unit-roles", "orgUnitId", r.orgUnitId(), orgUnits);
            required("unit-roles", "roleId", r.roleId(), refItems);
        }
        for (RealmExport.QualificationRow q : all(document.qualifications())) {
            required("qualifications", "personId", q.personId(), people);
            required("qualifications", "kindId", q.kindId(), refItems);
        }
        for (RealmExport.ExternalIdRow x : all(document.externalIds())) {
            Set<String> named = switch (Optional.ofNullable(x.resourceType()).orElse("")) {
                case "ORG_UNIT" -> orgUnits;
                case "PERSON" -> people;
                case "EMPLOYMENT" -> employments;
                case "ASSIGNMENT" -> assignments;
                case "QUALIFICATION" -> qualifications;
                case "REPORTING_LINE" -> lines;
                case "UNIT_ROLE" -> roles;
                case "CHANGE_ORDER" -> orders;
                case "REF_ITEM" -> refItems;
                case "REF_KIND" -> refKinds;
                case "HIERARCHY" -> hierarchies;
                case "ATTRIBUTE_DEFINITION" -> definitions;
                default -> Set.of();
            };
            required("external-ids", "resourceId", x.resourceId(), named);
        }
    }

    private static void optional(String list, String field, String value, Set<String> known) {
        if (value != null) {
            required(list, field, value, known);
        }
    }

    private static void required(String list, String field, String value, Set<String> known) {
        if (value == null || !known.contains(value)) {
            throw FuhitoException.invalid("restore.reference_unknown",
                    Map.of("list", list, "field", field, "value", String.valueOf(value)));
        }
    }

    private static <T> Set<String> ids(List<T> rows, Function<T, String> id) {
        Set<String> ids = new HashSet<>();
        all(rows).forEach(row -> ids.add(id.apply(row)));
        return ids;
    }

    private static <T> List<T> all(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}
