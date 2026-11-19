package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;
import java.util.Map;

/**
 * One entry of a list that a realm defines, for example one unit type.
 *
 * @param id the ID
 * @param kind the name of a fixed kind, or the code of a kind that the realm added
 * @param code the code, which is unique in the realm for the kind and never changes
 * @param names the names on the date of the read, by language tag
 * @param displayOrder the position in lists, lowest first
 * @param attributes free values that the realm defines
 * @param active false when the item must not be used for new data
 * @param legalEntity true for a unit type whose org units are legal entities, such as a company
 * @param actingHead true for an assignment kind whose heads stand in for an absent head
 * @param lateral true for a unit type whose org units are lateral units outside the line organization, such as a
 *        committee
 * @param version the number that each write of the item counts up. A new item has 1.
 * @param validFrom the first date of the names, or null when they hold from the start
 * @param validTo the first date after the names, or null when they have no end
 */
public record RefItem(String id, String kind, String code, Localized<RefItemName> names, int displayOrder,
        Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead,
        boolean lateral, long version, LocalDate validFrom, LocalDate validTo) {

    /**
     * Makes a new item of a fixed kind.
     *
     * @param id the ID
     * @param kind the kind
     * @param code the code
     * @param names the names by language tag
     * @param displayOrder the position in lists, lowest first
     * @param attributes free values that the realm defines
     * @param active false when the item must not be used for new data
     * @param legalEntity true for a unit type whose org units are legal entities, such as a company
     * @param actingHead true for an assignment kind whose heads stand in for an absent head
     * @param lateral true for a unit type whose org units are lateral units outside the line organization
     */
    public RefItem(String id, RefKind kind, String code, Localized<RefItemName> names, int displayOrder,
            Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead, boolean lateral) {
        this(id, kind.name(), code, names, displayOrder, attributes, active, legalEntity, actingHead, lateral,
                1, null, null);
    }

    /**
     * Returns the item with the names of a period.
     *
     * @param period the names and their dates
     * @return the item with those names
     */
    public RefItem withNames(RefItemPeriod period) {
        return new RefItem(id, kind, code, period.names(), displayOrder, attributes, active,
                legalEntity, actingHead, lateral, version, period.validFrom(), period.validTo());
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
     * Makes an item that is no unit type of lateral units.
     *
     * @param id the ID
     * @param kind the kind
     * @param code the code
     * @param names the names by language tag
     * @param displayOrder the position in lists, lowest first
     * @param attributes free values that the realm defines
     * @param active false when the item must not be used for new data
     * @param legalEntity true for a unit type whose org units are legal entities, such as a company
     * @param actingHead true for an assignment kind whose heads stand in for an absent head
     */
    public RefItem(String id, RefKind kind, String code, Localized<RefItemName> names, int displayOrder,
            Map<String, Object> attributes, boolean active, boolean legalEntity, boolean actingHead) {
        this(id, kind, code, names, displayOrder, attributes, active, legalEntity, actingHead, false);
    }

    /**
     * Makes an item that is no assignment kind of acting heads.
     *
     * @param id the ID
     * @param kind the kind
     * @param code the code
     * @param names the names by language tag
     * @param displayOrder the position in lists, lowest first
     * @param attributes free values that the realm defines
     * @param active false when the item must not be used for new data
     * @param legalEntity true for a unit type whose org units are legal entities, such as a company
     */
    public RefItem(String id, RefKind kind, String code, Localized<RefItemName> names, int displayOrder,
            Map<String, Object> attributes, boolean active, boolean legalEntity) {
        this(id, kind, code, names, displayOrder, attributes, active, legalEntity, false, false);
    }

    /**
     * Makes an item that is not a unit type of legal entities.
     *
     * @param id the ID
     * @param kind the kind
     * @param code the code
     * @param names the names by language tag
     * @param displayOrder the position in lists, lowest first
     * @param attributes free values that the realm defines
     * @param active false when the item must not be used for new data
     */
    public RefItem(String id, RefKind kind, String code, Localized<RefItemName> names, int displayOrder,
            Map<String, Object> attributes, boolean active) {
        this(id, kind, code, names, displayOrder, attributes, active, false, false, false);
    }
}
