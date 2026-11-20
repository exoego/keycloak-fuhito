package net.exoego.keycloak.fuhito.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefKind;

import static net.exoego.keycloak.fuhito.service.RestoreRules.all;
import static net.exoego.keycloak.fuhito.service.RestoreRules.broken;
import static net.exoego.keycloak.fuhito.service.RestoreRules.check;

/**
 * Checks the kinds of reference data in an export. An item has a fixed kind or the code of a kind of the export. An
 * attribute definition names such a kind for the type {@code REF_ITEM}, and only for it. A field that names an item
 * names one of the kind that a write takes for the field. A write finds an item by its kind and its code, so it never
 * takes an item of another kind. An item has only the marks of its kind.
 */
final class RestoreKinds {

    // The error of a write that finds no item of the kind. The other kinds give ref_item.unknown.
    private static final Map<RefKind, String> RULES = Map.of(
            RefKind.UNIT_TYPE, "org_unit.unit_type_unknown",
            RefKind.EXTERNAL_EMPLOYER, "employment.external_employer_unknown");

    // The mark that breaks each rule of the marks.
    private static final Map<String, String> MARKS = Map.of(
            "ref_item.legal_entity_kind", "legalEntity",
            "ref_item.acting_head_kind", "actingHead",
            "ref_item.lateral_kind", "lateral",
            "ref_item.rollup_kind", "rollup",
            "ref_item.lateral_legal_entity", "lateral",
            "ref_item.rollup_lateral", "rollup",
            "ref_item.rollup_legal_entity", "rollup");

    private final Map<String, String> kinds = new HashMap<>();

    private RestoreKinds(RealmExport document) {
        all(document.refItems()).forEach(item -> kinds.put(item.id(), item.kind()));
    }

    /**
     * Requires known kinds of reference data, the marks of the kind of each item, and items of the right kind in every
     * field that names an item.
     *
     * @param document the export, whose values are checked, whose references name rows of the export, and whose IDs
     *        are unique
     * @throws FuhitoException when an item or a definition has an unknown kind, an item has a mark that its kind
     *         cannot have or two marks that exclude each other, a definition has a kind with a type other than
     *         {@code REF_ITEM} or none with it, or a field names an item of another kind. The error is
     *         {@code restore.bad_value} and names the list, the ID of the row, the field, and the error of the write
     *         in {@code rule}.
     */
    static void require(RealmExport document) {
        Set<String> codes = new HashSet<>();
        all(document.refKinds()).forEach(kind -> codes.add(kind.code()));
        Predicate<String> known = code -> codes.contains(code)
                || Arrays.stream(RefKind.values()).anyMatch(kind -> kind.name().equals(code));
        for (RealmExport.Item item : all(document.refItems())) {
            check("ref-items", item.id(), "kind", () -> RefItemService.knownKind(item.kind(), known));
            try {
                RefItemMarks.require(item.kind(), item.code(), new RefItemService.Marks(item.legalEntity(),
                        item.actingHead(), item.lateral(), item.rollup()));
            } catch (FuhitoException e) {
                throw broken("ref-items", item.id(), MARKS.get(e.code()), e.code()).causedBy(e);
            }
        }
        for (RealmExport.Definition definition : all(document.attributeDefinitions())) {
            check("attribute-definitions", definition.id(), "refKind", () -> AttributeDefinitionService
                    .requireRefKind(AttributeDefinition.Type.valueOf(definition.type()), definition.refKind(), known));
        }
        new RestoreKinds(document).requireItems(document);
    }

    private void requireItems(RealmExport document) {
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            for (RealmExport.Version v : all(unit.versions())) {
                item("org-unit-versions", v.id(), "unitTypeId", v.unitTypeId(), RefKind.UNIT_TYPE);
                item("org-unit-versions", v.id(), "workLocationId", v.workLocationId(), RefKind.WORK_LOCATION);
                item("org-unit-versions", v.id(), "costCenterId", v.costCenterId(), RefKind.COST_CENTER);
            }
        }
        for (RealmExport.EmploymentRow e : all(document.employments())) {
            item("employments", e.id(), "externalEmployerId", e.externalEmployerId(), RefKind.EXTERNAL_EMPLOYER);
            item("employments", e.id(), "endReasonId", e.endReasonId(), RefKind.END_REASON);
            for (RealmExport.Terms t : all(e.terms())) {
                item("employment-terms", t.id(), "employmentTypeId", t.employmentTypeId(), RefKind.EMPLOYMENT_TYPE);
                item("employment-terms", t.id(), "gradeId", t.gradeId(), RefKind.GRADE);
                item("employment-terms", t.id(), "leaveReasonId", t.leaveReasonId(), RefKind.LEAVE_REASON);
            }
        }
        for (RealmExport.AssignmentRows assignment : all(document.assignments())) {
            for (RealmExport.Period r : all(assignment.periods())) {
                item("assignments", r.rowId(), "jobTitleId", r.jobTitleId(), RefKind.JOB_TITLE);
                item("assignments", r.rowId(), "occupationId", r.occupationId(), RefKind.OCCUPATION);
                item("assignments", r.rowId(), "kindId", r.kindId(), RefKind.ASSIGNMENT_KIND);
                item("assignments", r.rowId(), "workLocationId", r.workLocationId(), RefKind.WORK_LOCATION);
                item("assignments", r.rowId(), "costCenterId", r.costCenterId(), RefKind.COST_CENTER);
            }
        }
        all(document.unitRoles()).forEach(r -> item("unit-roles", r.id(), "roleId", r.roleId(), RefKind.UNIT_ROLE));
        all(document.qualifications())
                .forEach(q -> item("qualifications", q.id(), "kindId", q.kindId(), RefKind.QUALIFICATION));
    }

    private void item(String list, String id, String field, String itemId, RefKind kind) {
        if (itemId != null && !kind.name().equals(kinds.get(itemId))) {
            throw broken(list, id, field, RULES.getOrDefault(kind, "ref_item.unknown"));
        }
    }
}
