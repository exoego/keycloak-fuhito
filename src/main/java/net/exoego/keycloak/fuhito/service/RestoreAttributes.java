package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

import static net.exoego.keycloak.fuhito.service.RestoreRules.all;

/**
 * Checks the free attributes of the rows of an export. A change or a delete of a definition does not rewrite the
 * stored values, so a realm can keep values that its definitions no longer allow, and its export has them. A restore
 * brings them back, and they are checked at their next write. So a restore checks only the rule that every stored
 * value keeps, which is the size.
 */
final class RestoreAttributes {

    private RestoreAttributes() {
    }

    /**
     * Requires attributes of at most 64 KiB as JSON in every row, as in a write.
     *
     * @param document the export, whose values are checked
     * @throws FuhitoException when the attributes of a row are larger. The error is {@code restore.bad_value} and
     *         names the list, the ID of the row, the field {@code attributes}, and {@code attribute.too_large} in
     *         {@code rule}.
     */
    static void require(RealmExport document) {
        all(document.refItems()).forEach(i -> size("ref-items", i.id(), i.attributes()));
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            all(unit.versions()).forEach(v -> size("org-unit-versions", v.id(), v.attributes()));
        }
        all(document.people()).forEach(p -> size("people", p.id(), p.attributes()));
        for (RealmExport.EmploymentRow employment : all(document.employments())) {
            all(employment.terms()).forEach(t -> size("employment-terms", t.id(), t.attributes()));
        }
        for (RealmExport.AssignmentRows assignment : all(document.assignments())) {
            all(assignment.periods()).forEach(r -> size("assignments", r.rowId(), r.attributes()));
        }
        all(document.qualifications()).forEach(q -> size("qualifications", q.id(), q.attributes()));
    }

    private static void size(String list, String id, Map<String, Object> attributes) {
        RestoreRules.check(list, id, "attributes", () -> AttributeSize.require(attributes));
    }
}
