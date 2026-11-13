package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * One entry of a list that a realm defines, for example one unit type.
 *
 * @param id the ID
 * @param kind the kind
 * @param code the code, which is unique in the realm for the kind and never changes
 * @param name the default name
 * @param names the names in other languages, by language tag
 * @param displayOrder the position in lists, lowest first
 * @param attributes free values that the realm defines
 * @param active false when the item must not be used for new data
 */
public record RefItem(String id, RefKind kind, String code, String name, Map<String, String> names,
        int displayOrder, Map<String, Object> attributes, boolean active) {
}
