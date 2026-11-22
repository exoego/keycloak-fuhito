package net.exoego.keycloak.fuhito.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

import static net.exoego.keycloak.fuhito.service.RestoreRules.all;

/**
 * Checks the free attributes of the rows of an export with every rule of a write. A change or a delete of a definition
 * never leaves a stored value that breaks it, so a realm always meets its definitions, and so does its export. A
 * restore checks the attributes against the definitions and the items of the export, and not against those of the
 * realm that it replaces. It checks every row and every version, as a write checked each of them.
 */
final class RestoreAttributes {

    private RestoreAttributes() {
    }

    /**
     * Requires that the attributes of every row meet the definitions of the export, as in a write. A name has a
     * definition, a value has its type, a list is given only for a definition with {@code multiple}, and the code of
     * the type {@code REF_ITEM} is the code of an item of the export of the kind of the definition. A value of the type
     * {@code STRING} has no spaces around it. A required value is in every row and every version. The attributes are
     * at most 64 KiB as JSON, and a number is one that a double gives back the same. A restore stores the attributes
     * as the export has them, so they must be in the form that a write stores. A name with null is not, because a
     * write leaves it out, and an export never gives one.
     *
     * @param document the export, whose values are checked, whose IDs and keys are unique, and whose definitions have
     *        known choices and kinds
     * @throws FuhitoException when the attributes of a row break a rule of a write or have a name with null. The
     *         error is {@code restore.bad_value} and names the list, the ID of the row, the field, and the error of
     *         the write in {@code rule}. A size names the field {@code attributes} and {@code attribute.too_large}.
     *         Another rule names the attribute in the field, such as {@code attributes.beds}, and
     *         {@code attribute.unknown}, {@code attribute.bad_value}, {@code invalid.surrounding_space}, or
     *         {@code attribute.required}. A name with null and a definition names the attribute in the field and has
     *         no rule.
     */
    static void require(RealmExport document) {
        Map<String, Set<String>> codes = new HashMap<>();
        all(document.refItems()).forEach(i -> codes.computeIfAbsent(i.kind(), kind -> new HashSet<>()).add(i.code()));
        AttributeRules rules = AttributeDefinitionService.of(all(document.attributeDefinitions()).stream()
                .map(RealmExportService::definition).toList(),
                (kind, code) -> codes.getOrDefault(kind, Set.of()).contains(code));
        all(document.refItems()).forEach(i -> row(rules, AttributeDefinition.Target.REF_ITEM, "ref-items", i.id(),
                i.attributes()));
        for (RealmExport.Unit unit : all(document.orgUnits())) {
            all(unit.versions()).forEach(v -> row(rules, AttributeDefinition.Target.ORG_UNIT, "org-unit-versions",
                    v.id(), v.attributes()));
        }
        all(document.people()).forEach(p -> row(rules, AttributeDefinition.Target.PERSON, "people", p.id(),
                p.attributes()));
        for (RealmExport.EmploymentRow employment : all(document.employments())) {
            all(employment.terms()).forEach(t -> row(rules, AttributeDefinition.Target.EMPLOYMENT_TERMS,
                    "employment-terms", t.id(), t.attributes()));
        }
        for (RealmExport.AssignmentRows assignment : all(document.assignments())) {
            all(assignment.periods()).forEach(r -> row(rules, AttributeDefinition.Target.ASSIGNMENT, "assignments",
                    r.rowId(), r.attributes()));
        }
        all(document.qualifications()).forEach(q -> row(rules, AttributeDefinition.Target.QUALIFICATION,
                "qualifications", q.id(), q.attributes()));
    }

    // Only the error of the size names no attribute.
    private static void row(AttributeRules rules, AttributeDefinition.Target target, String list, String id,
            Map<String, Object> attributes) {
        Map<String, Object> stored;
        try {
            stored = rules.check(target, attributes);
        } catch (FuhitoException e) {
            String name = e.params().get("name");
            String field = e.params().getOrDefault("field", name == null ? "attributes" : "attributes." + name);
            throw RestoreRules.broken(list, id, field, e.code()).causedBy(e);
        }
        attributes.keySet().stream().filter(name -> !stored.containsKey(name)).findFirst().ifPresent(name -> {
            throw RestoreRules.broken(list, id, "attributes." + name, null);
        });
    }
}
