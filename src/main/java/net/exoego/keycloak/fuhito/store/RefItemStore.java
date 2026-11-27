package net.exoego.keycloak.fuhito.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemPeriod;
import net.exoego.keycloak.fuhito.domain.RefKind;

/**
 * Storage of the reference items of one realm.
 * A read with a date gives each item the names of that date, and the first names for a date before them.
 * A read with no date gives the names of the last period, which is for code that does not show them.
 */
public interface RefItemStore {

    /**
     * Lists every item of a kind, ordered by display order and then by code. This is for code that looks items up.
     *
     * @param kind the code of a fixed kind or of a kind of the realm
     * @param includeInactive true to also return inactive items
     * @return the items
     */
    List<RefItem> list(String kind, boolean includeInactive);

    /**
     * Lists every item of a fixed kind, ordered by display order and then by code.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @return the items
     */
    default List<RefItem> list(RefKind kind, boolean includeInactive) {
        return list(kind.name(), includeInactive);
    }

    /**
     * Lists every item of a fixed kind with the names of a date, ordered by display order and then by code.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @param date the date of the names
     * @return the items
     */
    default List<RefItem> list(RefKind kind, boolean includeInactive, LocalDate date) {
        return list(kind.name(), includeInactive, date, 0, Integer.MAX_VALUE);
    }

    /**
     * Lists a page of the items of a kind with the names of a date, ordered by display order and then by code.
     *
     * @param kind the code of a fixed kind or of a kind of the realm
     * @param includeInactive true to also return inactive items
     * @param date the date of the names
     * @param first the number of items to skip
     * @param max the largest number of items to return
     * @return the items of the page
     */
    List<RefItem> list(String kind, boolean includeInactive, LocalDate date, int first, int max);

    /**
     * Counts the items that {@link #list(String, boolean, LocalDate, int, int)} lists on all pages together. Every
     * item has names on every date, so the count has no date.
     *
     * @param kind the code of a fixed kind or of a kind of the realm
     * @param includeInactive true to also count inactive items
     * @return the number of items
     */
    long count(String kind, boolean includeInactive);

    /**
     * Finds an item by ID.
     *
     * @param id the ID
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> find(String id);

    /**
     * Gives the code of each item with the given IDs, with one read.
     *
     * @param ids the IDs of the items
     * @return the code by the ID of the item. An item that the realm does not have is left out.
     */
    default Map<String, String> codesOf(Collection<String> ids) {
        Map<String, String> codes = new HashMap<>();
        ids.forEach(id -> find(id).ifPresent(item -> codes.put(id, item.code())));
        return codes;
    }

    /**
     * Finds an item by ID, with the names of a date.
     *
     * @param id the ID
     * @param date the date of the names
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> find(String id, LocalDate date);

    /**
     * Finds an item by kind and code.
     *
     * @param kind the code of a fixed kind or of a kind of the realm
     * @param code the code
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> findByCode(String kind, String code);

    /**
     * Finds an item of a fixed kind by code.
     *
     * @param kind the kind
     * @param code the code
     * @return the item, or empty when the realm has no such item
     */
    default Optional<RefItem> findByCode(RefKind kind, String code) {
        return findByCode(kind.name(), code);
    }

    /**
     * Finds an item by kind and code, with the names of a date.
     *
     * @param kind the code of a fixed kind or of a kind of the realm
     * @param code the code
     * @param date the date of the names
     * @return the item, or empty when the realm has no such item
     */
    Optional<RefItem> findByCode(String kind, String code, LocalDate date);

    /**
     * Lists every period of the names of an item.
     *
     * @param id the ID of the item
     * @return the names, oldest first, or an empty list when the realm has no such item
     */
    List<RefItemPeriod> names(String id);

    /**
     * Tells whether an employment names an org unit that had this unit type on the first date of the employment.
     *
     * @param id the ID of the unit type
     * @return true when the type makes the employer of an employment a legal entity
     */
    boolean isTypeOfAnEmployer(String id);

    /**
     * Tells whether other data refers to the item.
     *
     * @param id the ID
     * @return true when the item is in use
     */
    boolean isInUse(String id);

    /**
     * Tells whether a row of the kind of resource of a definition has the code of an item in that attribute, as
     * the value or as an element of a list. Every version counts, also past and future ones. The item itself does
     * not count, because its own attributes go away with it.
     *
     * @param item the item
     * @param definition a definition of the type {@code REF_ITEM} for the kind of the item
     * @return true when such a value has the code
     */
    boolean isInAttribute(RefItem item, AttributeDefinition definition);

    /**
     * Stores a new item with every period of its names. The item keeps its version.
     *
     * @param item the item
     * @param names the names, oldest first
     */
    void insert(RefItem item, List<RefItemPeriod> names);

    /**
     * Stores the values of an existing item that have no dates, and counts up its version. The names stay.
     *
     * @param item the item
     */
    void update(RefItem item);

    /**
     * Stores the names of a new period of an item, and counts up the version of the item.
     *
     * @param names the names
     */
    void insertNames(RefItemPeriod names);

    /**
     * Stores new names or new dates of a stored period, and counts up the version of the item.
     *
     * @param names the names
     */
    void updateNames(RefItemPeriod names);

    /**
     * Removes an item with its names.
     *
     * @param id the ID
     */
    void delete(String id);
}
