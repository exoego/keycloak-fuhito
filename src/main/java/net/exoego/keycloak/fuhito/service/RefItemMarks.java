package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefKind;

/**
 * The rules of the marks of a reference item, which a write and a restore both check.
 */
final class RefItemMarks {

    private RefItemMarks() {
    }

    /**
     * Requires marks that the kind of an item can have. Only a unit type has {@code legalEntity}, {@code lateral},
     * and {@code rollup}, and it has one of them at most. Only an assignment kind has {@code actingHead}.
     *
     * @param kind the kind of the item
     * @param code the code of the item
     * @param marks the marks
     * @throws FuhitoException when the kind cannot have a mark, or a unit type has two marks
     */
    static void require(String kind, String code, RefItemService.Marks marks) {
        boolean legalEntity = mark(kind, RefKind.UNIT_TYPE, marks.legalEntity(), "ref_item.legal_entity_kind");
        mark(kind, RefKind.ASSIGNMENT_KIND, marks.actingHead(), "ref_item.acting_head_kind");
        boolean lateral = mark(kind, RefKind.UNIT_TYPE, marks.lateral(), "ref_item.lateral_kind");
        boolean rollup = mark(kind, RefKind.UNIT_TYPE, marks.rollup(), "ref_item.rollup_kind");
        if (legalEntity && lateral) {
            throw FuhitoException.invalid("ref_item.lateral_legal_entity", Map.of("code", code));
        }
        // A lateral unit sits in the default hierarchy, and so does a legal entity for the check of secondments.
        if (rollup && lateral) {
            throw FuhitoException.invalid("ref_item.rollup_lateral", Map.of("code", code));
        }
        if (rollup && legalEntity) {
            throw FuhitoException.invalid("ref_item.rollup_legal_entity", Map.of("code", code));
        }
    }

    private static boolean mark(String kind, RefKind allowed, Boolean value, String error) {
        boolean marked = Boolean.TRUE.equals(value);
        if (marked && !kind.equals(allowed.name())) {
            throw FuhitoException.invalid(error, Map.of("kind", kind));
        }
        return marked;
    }
}
