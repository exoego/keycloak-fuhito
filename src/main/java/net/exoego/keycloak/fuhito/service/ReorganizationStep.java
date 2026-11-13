package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * One operation of a reorganization. The values are not checked yet.
 *
 * @param op {@code create}, {@code change}, {@code move}, or {@code end}
 * @param ref a name that later steps of the same request can use for the unit that a create step makes, or null
 * @param id the ID of the unit to change, move, or end, or null
 * @param unitRef the {@code ref} of an earlier create step, in place of {@code id}, or null
 * @param parentId the ID of the parent for a create or a move, or null
 * @param parentRef the {@code ref} of an earlier create step, in place of {@code parentId}, or null
 * @param code the code of a new unit, or null
 * @param name the default name, for a create or a change
 * @param names the names in other languages, or null
 * @param shortName the default short name, or null
 * @param shortNames the short names in other languages, or null
 * @param phoneticName the reading of the name for sorting, or null
 * @param unitType the code of the unit type, or null
 * @param displayOrder the position among siblings, or null for 0
 * @param attributes free values, or null
 */
public record ReorganizationStep(String op, String ref, String id, String unitRef, String parentId,
        String parentRef, String code, String name, Map<String, String> names, String shortName,
        Map<String, String> shortNames, String phoneticName, String unitType, Integer displayOrder,
        Map<String, Object> attributes) {
}
