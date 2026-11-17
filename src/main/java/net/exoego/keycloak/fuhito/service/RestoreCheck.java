package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
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
 * never links to the rows of another realm, and fails with a clear error and not in the database.
 */
final class RestoreCheck {

    private final Set<String> refItems;
    private final Set<String> orgUnits;
    private final Set<String> people;
    private final Set<String> employments;
    private final Set<String> assignments;
    private final Set<String> qualifications;

    private RestoreCheck(RealmExport document) {
        refItems = ids(document.refItems(), RealmExport.Item::id);
        orgUnits = ids(document.orgUnits(), RealmExport.Unit::id);
        people = ids(document.people(), RealmExport.PersonRow::id);
        employments = ids(document.employments(), RealmExport.EmploymentRow::id);
        assignments = ids(document.assignments(), RealmExport.AssignmentRows::assignmentId);
        qualifications = ids(document.qualifications(), RealmExport.QualificationRow::id);
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
            required("employments", "employerId", e.employerId(), orgUnits);
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
