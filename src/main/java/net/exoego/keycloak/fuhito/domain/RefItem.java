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
 * @param legalEntity true for a unit type whose org units are legal entities, such as a company
 * @param actingHead true for an assignment kind whose heads stand in for an absent head
 */
public record RefItem(String id, RefKind kind, String code, String name, Map<String, String> names,
        int displayOrder, Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead) {

    /**
     * Makes an item that is no assignment kind of acting heads.
     *
     * @param id the ID
     * @param kind the kind
     * @param code the code
     * @param name the default name
     * @param names the names in other languages, by language tag
     * @param displayOrder the position in lists, lowest first
     * @param attributes free values that the realm defines
     * @param active false when the item must not be used for new data
     * @param legalEntity true for a unit type whose org units are legal entities, such as a company
     */
    public RefItem(String id, RefKind kind, String code, String name, Map<String, String> names, int displayOrder,
            Map<String, Object> attributes, boolean active, boolean legalEntity) {
        this(id, kind, code, name, names, displayOrder, attributes, active, legalEntity, false);
    }

    /**
     * Makes an item that is not a unit type of legal entities.
     *
     * @param id the ID
     * @param kind the kind
     * @param code the code
     * @param name the default name
     * @param names the names in other languages, by language tag
     * @param displayOrder the position in lists, lowest first
     * @param attributes free values that the realm defines
     * @param active false when the item must not be used for new data
     */
    public RefItem(String id, RefKind kind, String code, String name, Map<String, String> names, int displayOrder,
            Map<String, Object> attributes, boolean active) {
        this(id, kind, code, name, names, displayOrder, attributes, active, false, false);
    }
}
