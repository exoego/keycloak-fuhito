package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * One entry of a list that a realm defines, for example one unit type.
 *
 * @param id the ID
 * @param kind the name of a fixed kind, or the code of a kind that the realm added
 * @param code the code, which is unique in the realm for the kind and never changes
 * @param name the default name
 * @param names the names in other languages, by language tag
 * @param displayOrder the position in lists, lowest first
 * @param attributes free values that the realm defines
 * @param active false when the item must not be used for new data
 * @param legalEntity true for a unit type whose org units are legal entities, such as a company
 * @param actingHead true for an assignment kind whose heads stand in for an absent head
 * @param group true for a unit type whose org units are groups outside the line organization, such as a committee
 */
public record RefItem(String id, String kind, String code, String name, Map<String, String> names,
        int displayOrder, Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead,
        boolean group) {

    /**
     * Makes an item of a fixed kind.
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
     * @param actingHead true for an assignment kind whose heads stand in for an absent head
     * @param group true for a unit type whose org units are groups outside the line organization
     */
    public RefItem(String id, RefKind kind, String code, String name, Map<String, String> names, int displayOrder,
            Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead, boolean group) {
        this(id, kind.name(), code, name, names, displayOrder, attributes, active, legalEntity, actingHead, group);
    }

    /**
     * Tells whether the item is of a fixed kind.
     *
     * @param fixed the fixed kind
     * @return true when the item is of that kind
     */
    public boolean is(RefKind fixed) {
        return kind.equals(fixed.name());
    }

    /**
     * Makes an item that is no unit type of groups.
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
     * @param actingHead true for an assignment kind whose heads stand in for an absent head
     */
    public RefItem(String id, RefKind kind, String code, String name, Map<String, String> names, int displayOrder,
            Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead) {
        this(id, kind, code, name, names, displayOrder, attributes, active, legalEntity, actingHead, false);
    }

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
        this(id, kind, code, name, names, displayOrder, attributes, active, legalEntity, false, false);
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
        this(id, kind, code, name, names, displayOrder, attributes, active, false, false, false);
    }
}
