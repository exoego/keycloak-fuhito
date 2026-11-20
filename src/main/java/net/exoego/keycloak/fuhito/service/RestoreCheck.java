package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Checks an export before a restore writes it. A row may name only rows of the same export, so that a restore
 * never links to the rows of another realm, and fails with a clear error and not in the database. A row has no empty
 * value, as a row of an import.
 */
final class RestoreCheck {

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
     * empty text, a text of only spaces, or an empty list, as in a row of an import. A row inside a row, such as a
     * version of an org unit, is named by the list and the ID that {@code restore.id_taken} gives it.
     *
     * @param document the export
     * @throws FuhitoException when a row or a needed value is missing, or a value is empty. The error names the list,
     *         the ID of the row when it has one, and the field.
     */
    static void requireValues(RealmExport document) {
        values("ref-kinds", document.refKinds(), "id", "code");
        values("ref-items", document.refItems(), "id", "kind", "code", "attributes");
        all(document.refItems()).forEach(i -> values("ref-item-names", i.versions(), "id"));
        values("attribute-definitions", document.attributeDefinitions(), "id", "target", "name", "type");
        values("hierarchies", document.hierarchies(), "id", "code", "name");
        values("org-units", document.orgUnits(), "id");
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            values("org-unit-versions", unit.versions(), "id", "validFrom", "attributes");
            values("org-unit-parents", unit.parents(), "id", "hierarchy", "parentId", "validFrom");
            values("org-unit-successions", unit.successors(), "id", "successorId", "effectiveDate");
        }
        values("people", document.people(), "id", "attributes");
        values("employments", document.employments(), "id", "personId", "validFrom");
        all(document.employments()).forEach(e -> values("employment-terms", e.terms(), "id", "validFrom", "status",
                "attributes"));
        values("assignments", document.assignments(), "assignmentId");
        all(document.assignments()).forEach(a -> values("assignments", a.periods(), "rowId", "employmentId",
                "orgUnitId", "validFrom", "attributes"));
        values("reporting-lines", document.reportingLines(), "id", "assignmentId", "managerPersonId", "kind",
                "validFrom");
        values("unit-roles", document.unitRoles(), "id", "employmentId", "orgUnitId", "roleId", "validFrom");
        values("qualifications", document.qualifications(), "id", "personId", "kindId", "validFrom", "attributes");
        values("external-ids", document.externalIds(), "id", "resourceType", "resourceId", "source", "externalId");
        values("change-orders", document.changeOrders(), "id", "title", "effectiveDate", "createdAt");
        values("erasures", document.erasures(), "personId", "erasedAt");
    }

    // The needed fields have columns that are not null, and the first one is the ID of the row. A list in a row holds
    // the rows of the row, and a row may have none. Each of them is checked as a row.
    private static <T extends Record> void values(String list, List<T> rows, String... needed) {
        for (T row : all(rows)) {
            if (row == null) {
                throw FuhitoException.invalid("restore.bad_value", Map.of("list", list));
            }
            Map<String, Object> values = new HashMap<>();
            for (RecordComponent component : row.getClass().getRecordComponents()) {
                values.put(component.getName(), EmptyValues.valueOf(component, row));
            }
            Map<String, String> params = new HashMap<>(Map.of("list", list));
            Optional.ofNullable(values.get(needed[0])).ifPresent(id -> params.put("id", id.toString()));
            for (String field : needed) {
                if (values.get(field) == null) {
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

    /**
     * Returns the IDs of every list that a restore writes, by the name of the table.
     *
     * @param document the export
     * @return the IDs by list
     */
    static Map<String, Collection<String>> ids(RealmExport document) {
        Map<String, Collection<String>> ids = new LinkedHashMap<>();
        ids.put("ref-kinds", ids(document.refKinds(), RealmExport.Kind::id));
        ids.put("ref-items", ids(document.refItems(), RealmExport.Item::id));
        ids.put("ref-item-names", ids(all(document.refItems()).stream().flatMap(i -> all(i.versions()).stream())
                .toList(), RealmExport.ItemNames::id));
        ids.put("attribute-definitions", ids(document.attributeDefinitions(), RealmExport.Definition::id));
        ids.put("hierarchies", ids(document.hierarchies(), RealmExport.HierarchyRow::id));
        List<RealmExport.Unit> units = all(document.orgUnits());
        ids.put("org-units", ids(units, RealmExport.Unit::id));
        ids.put("org-unit-versions", ids(units.stream().flatMap(u -> all(u.versions()).stream()).toList(),
                RealmExport.Version::id));
        ids.put("org-unit-parents", ids(units.stream().flatMap(u -> all(u.parents()).stream()).toList(),
                RealmExport.Parent::id));
        ids.put("org-unit-successions", ids(units.stream().flatMap(u -> all(u.successors()).stream()).toList(),
                RealmExport.Succession::id));
        ids.put("people", ids(document.people(), RealmExport.PersonRow::id));
        List<RealmExport.EmploymentRow> rows = all(document.employments());
        ids.put("employments", ids(rows, RealmExport.EmploymentRow::id));
        ids.put("employment-terms", ids(rows.stream().flatMap(e -> all(e.terms()).stream()).toList(),
                RealmExport.Terms::id));
        ids.put("assignments", ids(all(document.assignments()).stream().flatMap(a -> all(a.periods()).stream())
                .toList(), RealmExport.Period::rowId));
        ids.put("reporting-lines", ids(document.reportingLines(), RealmExport.Line::id));
        ids.put("unit-roles", ids(document.unitRoles(), RealmExport.Role::id));
        ids.put("qualifications", ids(document.qualifications(), RealmExport.QualificationRow::id));
        ids.put("external-ids", ids(document.externalIds(), RealmExport.ExternalIdRow::id));
        ids.put("change-orders", ids(document.changeOrders(), RealmExport.Order::id));
        return ids;
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
        Set<List<String>> values = new HashSet<>();
        Set<List<String>> systems = new HashSet<>();
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
            unique(x, values, systems);
        }
    }

    // The table of IDs has the same two rules. They are checked here to fail with a clear error.
    private static void unique(RealmExport.ExternalIdRow x, Set<List<String>> values, Set<List<String>> systems) {
        String source = String.valueOf(x.source());
        if (!values.add(List.of(x.resourceType(), source, String.valueOf(x.externalId())))) {
            throw FuhitoException.invalid("restore.external_id_taken", Map.of("resourceType", x.resourceType(),
                    "source", source, "externalId", String.valueOf(x.externalId())));
        }
        if (!systems.add(List.of(x.resourceType(), x.resourceId(), source))) {
            throw FuhitoException.invalid("restore.external_id_twice",
                    Map.of("resourceId", x.resourceId(), "source", source));
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
