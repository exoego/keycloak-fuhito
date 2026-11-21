package net.exoego.keycloak.fuhito.service;

import java.util.Collection;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.JsonNumbers;

import static net.exoego.keycloak.fuhito.service.RestoreRules.all;

/**
 * Checks the free attributes of the rows of an export. A change or a delete of a definition does not rewrite the
 * stored values, so a realm can keep values that its definitions no longer allow, and its export has them. A restore
 * brings them back, and they are checked at their next write. So a restore checks only the rules that every stored
 * value keeps. These are the size, and a number that a double gives back the same.
 */
final class RestoreAttributes {

    private RestoreAttributes() {
    }

    /**
     * Requires attributes of at most 64 KiB as JSON in every row, as in a write, and only numbers that a double gives
     * back the same, as a write of the type {@code NUMBER} stores.
     *
     * @param document the export, whose values are checked
     * @throws FuhitoException when the attributes of a row are larger, or have another number. The error is
     *         {@code restore.bad_value} and names the list and the ID of the row. A size names the field
     *         {@code attributes} and {@code attribute.too_large} in {@code rule}. A number names the attribute in the
     *         field, such as {@code attributes.beds}, and {@code attribute.bad_value} in {@code rule}.
     */
    static void require(RealmExport document) {
        all(document.refItems()).forEach(i -> row("ref-items", i.id(), i.attributes()));
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            all(unit.versions()).forEach(v -> row("org-unit-versions", v.id(), v.attributes()));
        }
        all(document.people()).forEach(p -> row("people", p.id(), p.attributes()));
        for (RealmExport.EmploymentRow employment : all(document.employments())) {
            all(employment.terms()).forEach(t -> row("employment-terms", t.id(), t.attributes()));
        }
        for (RealmExport.AssignmentRows assignment : all(document.assignments())) {
            all(assignment.periods()).forEach(r -> row("assignments", r.rowId(), r.attributes()));
        }
        all(document.qualifications()).forEach(q -> row("qualifications", q.id(), q.attributes()));
    }

    private static void row(String list, String id, Map<String, Object> attributes) {
        RestoreRules.check(list, id, "attributes", () -> AttributeSize.require(attributes));
        if (attributes != null) {
            attributes.forEach((name, value) -> {
                if (!fitsDouble(value)) {
                    throw RestoreRules.broken(list, id, "attributes." + name, "attribute.bad_value");
                }
            });
        }
    }

    private static boolean fitsDouble(Object value) {
        return switch (value) {
            case Number number -> JsonNumbers.fitsDouble(number);
            case Collection<?> values -> values.stream().allMatch(RestoreAttributes::fitsDouble);
            case Map<?, ?> values -> values.values().stream().allMatch(RestoreAttributes::fitsDouble);
            case null, default -> true;
        };
    }
}
