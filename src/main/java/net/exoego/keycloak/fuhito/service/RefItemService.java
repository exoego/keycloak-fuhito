package net.exoego.keycloak.fuhito.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefItemPeriod;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the reference items of one realm.
 * The names of an item have valid periods. The other values have no dates.
 */
public class RefItemService {

    /**
     * Every period of the names of an item.
     *
     * @param item the item with the names of today
     * @param names the names, oldest first
     */
    public record History(RefItem item, List<RefItemPeriod> names) {
    }

    private final RefItemStore store;
    private final AttributeRules attributeRules;
    private final Predicate<String> knownKinds;
    private final Today today;

    /**
     * Creates the service.
     *
     * @param store the storage of the realm
     */
    public RefItemService(RefItemStore store) {
        this(store, AttributeRules.NONE);
    }

    /**
     * Creates the service with a check of free attributes.
     *
     * @param store the storage of the realm
     * @param attributeRules the check of free attributes against the definitions of the realm
     */
    public RefItemService(RefItemStore store, AttributeRules attributeRules) {
        this(store, attributeRules, code -> Arrays.stream(RefKind.values()).anyMatch(kind -> kind.name().equals(code)));
    }

    /**
     * Creates the service with a check of free attributes and the kinds of the realm. Today is the date in UTC.
     *
     * @param store the storage of the realm
     * @param attributeRules the check of free attributes against the definitions of the realm
     * @param knownKinds tells whether a code is a fixed kind or a kind of the realm
     */
    public RefItemService(RefItemStore store, AttributeRules attributeRules, Predicate<String> knownKinds) {
        this(store, attributeRules, knownKinds, Today.of(Clock.systemUTC()));
    }

    /**
     * Creates the service with a check of free attributes, the kinds of the realm, and the date of the realm.
     *
     * @param store the storage of the realm
     * @param attributeRules the check of free attributes against the definitions of the realm
     * @param knownKinds tells whether a code is a fixed kind or a kind of the realm
     * @param today what today is for the realm
     */
    public RefItemService(RefItemStore store, AttributeRules attributeRules, Predicate<String> knownKinds,
            Today today) {
        this.store = store;
        this.attributeRules = attributeRules;
        this.knownKinds = knownKinds;
        this.today = today;
    }

    /**
     * Returns the check of free attributes of these operations, so that an import checks the names of a row with it.
     *
     * @return the check
     */
    AttributeRules attributeRules() {
        return attributeRules;
    }

    /**
     * Lists a page of the items of a kind, with the names of today.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @param page the part of the list to return
     * @return the items of the page, ordered by display order and then by code
     */
    public List<RefItem> list(RefKind kind, boolean includeInactive, Page page) {
        return list(Checks.required("kind", kind).name(), includeInactive, null, page);
    }

    /**
     * Lists a page of the items of a fixed kind or of a kind of the realm, with the names of a date.
     *
     * @param kind the code of the kind
     * @param includeInactive true to also return inactive items
     * @param asOf the date of the names as {@code YYYY-MM-DD}, or null for today
     * @param page the part of the list to return
     * @return the items of the page, ordered by display order and then by code
     * @throws FuhitoException when the kind is unknown or the date is bad
     */
    public List<RefItem> list(String kind, boolean includeInactive, String asOf, Page page) {
        return store.list(knownKind(kind, knownKinds), includeInactive, dateOrToday(asOf), page.first(), page.max());
    }

    /**
     * Counts the items that {@link #list(String, boolean, String, Page)} lists on all pages together. Every item
     * has names on every date, so the count is the same for every date.
     *
     * @param kind the code of the kind
     * @param includeInactive true to also count inactive items
     * @return the number of items
     * @throws FuhitoException when the kind is unknown
     */
    public long count(String kind, boolean includeInactive) {
        return store.count(knownKind(kind, knownKinds), includeInactive);
    }

    /**
     * Requires a kind that items can have.
     *
     * @param kind the code of the kind
     * @param knownKinds tells whether a code is a fixed kind or a kind of the realm
     * @return the same code
     * @throws FuhitoException when the kind is missing or unknown
     */
    static String knownKind(String kind, Predicate<String> knownKinds) {
        Checks.required("kind", kind);
        if (!knownKinds.test(kind)) {
            throw FuhitoException.invalid("invalid.kind", Map.of("value", kind));
        }
        return kind;
    }

    private LocalDate dateOrToday(String asOf) {
        return asOf == null ? today.ofRealm() : Checks.optionalDate("asOf", asOf);
    }

    /**
     * Returns an item with the names of today.
     *
     * @param id the ID
     * @return the item
     * @throws FuhitoException when the realm has no such item
     */
    public RefItem get(String id) {
        return get(id, null);
    }

    /**
     * Returns an item with the names of a date. A date before the first names gets the first names.
     *
     * @param id the ID
     * @param asOf the date of the names as {@code YYYY-MM-DD}, or null for today
     * @return the item
     * @throws FuhitoException when the date is bad or the realm has no such item
     */
    public RefItem get(String id, String asOf) {
        LocalDate date = dateOrToday(asOf);
        return store.find(id, date).orElseThrow(() -> notFound(id));
    }

    /**
     * Returns the version of an item.
     *
     * @param id the ID
     * @return the number that each write of the item counts up
     * @throws FuhitoException when the realm has no such item
     */
    public long version(String id) {
        return stored(id).version();
    }

    /**
     * Returns every period of the names of an item.
     *
     * @param id the ID
     * @return the item and its names, oldest first
     * @throws FuhitoException when the realm has no such item
     */
    public History history(String id) {
        return new History(get(id), store.names(id));
    }

    private RefItem stored(String id) {
        return store.find(id).orElseThrow(() -> notFound(id));
    }

    private static FuhitoException notFound(String id) {
        return FuhitoException.notFound("ref_item.not_found", Map.of("id", id));
    }

    /**
     * The marks of an item. Each kind of item can have only its own marks.
     *
     * @param legalEntity true for a unit type whose org units are legal entities, or null for false
     * @param actingHead true for an assignment kind whose heads stand in for an absent head, or null for false
     * @param lateral true for a unit type whose org units are lateral units outside the line organization, or null for
     *        false
     * @param rollup true for a unit type whose org units are rollup units, or null for false
     */
    public record Marks(Boolean legalEntity, Boolean actingHead, Boolean lateral, Boolean rollup) {

        /** No mark. */
        public static final Marks NONE = new Marks(null, null, null, null);

        /**
         * Makes the marks of an item that is no unit type of rollup units.
         *
         * @param legalEntity true for a unit type whose org units are legal entities, or null for false
         * @param actingHead true for an assignment kind whose heads stand in for an absent head, or null for false
         * @param lateral true for a unit type whose org units are lateral units, or null for false
         */
        public Marks(Boolean legalEntity, Boolean actingHead, Boolean lateral) {
            this(legalEntity, actingHead, lateral, null);
        }

        static Marks of(RefItem item) {
            return new Marks(item.legalEntity(), item.actingHead(), item.lateral(), item.rollup());
        }
    }

    /**
     * Creates an item with no mark.
     *
     * @param kind the kind
     * @param code the code, which must be new for the kind
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @return the new item
     * @throws FuhitoException when a value is bad or the code is taken
     */
    public RefItem create(RefKind kind, String code, String primaryLanguage, Map<String, RefItemName> names,
            Integer displayOrder, Map<String, Object> attributes) {
        return create(kind, code, primaryLanguage, names, displayOrder, attributes, Marks.NONE);
    }

    /**
     * Creates an item with its marks.
     *
     * @param kind the kind
     * @param code the code
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @param marks the marks
     * @return the new item
     * @throws FuhitoException when a value is bad, the code is taken, or the kind cannot have a mark
     */
    public RefItem create(RefKind kind, String code, String primaryLanguage, Map<String, RefItemName> names,
            Integer displayOrder, Map<String, Object> attributes, Marks marks) {
        return create(Checks.required("kind", kind).name(), code, primaryLanguage, names, displayOrder, attributes,
                marks);
    }

    /**
     * Creates an item of a fixed kind or of a kind of the realm, with its marks.
     *
     * @param kind the code of the kind
     * @param code the code
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @param marks the marks
     * @return the new item
     * @throws FuhitoException when a value is bad, the kind is unknown, the code is taken, or the kind cannot have a
     *         mark
     */
    public RefItem create(String kind, String code, String primaryLanguage, Map<String, RefItemName> names,
            Integer displayOrder, Map<String, Object> attributes, Marks marks) {
        return create(kind, code, primaryLanguage, names, displayOrder, attributes, marks, true, null);
    }

    /**
     * Creates an item of a fixed kind or of a kind of the realm, with its marks, active or not, and with the first
     * date of its names.
     *
     * @param kind the code of the kind
     * @param code the code
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @param marks the marks
     * @param active false for an item that must not be used for new data
     * @param validFrom the first date of the names as {@code YYYY-MM-DD}, or null for names that hold for every
     *        date
     * @return the new item with its names
     * @throws FuhitoException when a value is bad, the kind is unknown, the code is taken, or the kind cannot have a
     *         mark
     */
    public RefItem create(String kind, String code, String primaryLanguage, Map<String, RefItemName> names,
            Integer displayOrder, Map<String, Object> attributes, Marks marks, boolean active, String validFrom) {
        knownKind(kind, knownKinds);
        Checks.code("code", code);
        LocalDate from = Checks.optionalDate("validFrom", validFrom);
        if (store.findByCode(kind, code).isPresent()) {
            throw FuhitoException.conflict("ref_item.code_taken", Map.of("kind", kind, "code", code));
        }
        RefItem item = item(newId(), kind, code, primaryLanguage, names, displayOrder, attributes, active, marks, 1);
        RefItemPeriod first = new RefItemPeriod(newId(), item.id(), from, null, item.names());
        store.insert(item, List.of(first));
        return item.withNames(first);
    }

    /**
     * Replaces the changeable values of an item and the names of today, and keeps its marks. The kind and the code
     * never change.
     *
     * @param id the ID
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null for none
     * @param active false to stop the use of the item for new data, or null for true
     * @return the changed item
     * @throws FuhitoException when the item does not exist or a value is bad
     */
    public RefItem update(String id, String primaryLanguage, Map<String, RefItemName> names, Integer displayOrder,
            Map<String, Object> attributes, Boolean active) {
        return update(id, primaryLanguage, names, displayOrder, attributes, active, Marks.of(stored(id)), null);
    }

    /**
     * Replaces the changeable values of an item, with its marks, and its names from a date.
     * New names on a date inside a stored period hold until the next stored change. New names on the first date
     * of a period correct that period. New names before the first period hold until it starts.
     *
     * @param id the ID
     * @param primaryLanguage the tag of the language of the primary name
     * @param names the names by language tag
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null for none
     * @param active false to stop the use of the item for new data, or null for true
     * @param marks the marks
     * @param validFrom the first date of the names as {@code YYYY-MM-DD}, or null to change the names of today
     * @return the changed item with the names of that date
     * @throws FuhitoException when the item does not exist, a value is bad, the kind cannot have a mark, or a mark
     *         that cannot change is in use
     */
    public RefItem update(String id, String primaryLanguage, Map<String, RefItemName> names, Integer displayOrder,
            Map<String, Object> attributes, Boolean active, Marks marks, String validFrom) {
        LocalDate from = Checks.optionalDate("validFrom", validFrom);
        RefItem current = stored(id);
        if (current.legalEntity() && !Boolean.TRUE.equals(marks.legalEntity()) && store.isTypeOfAnEmployer(id)) {
            throw FuhitoException.conflict("ref_item.legal_entity_in_use", Map.of("id", id, "code", current.code()));
        }
        RefItem changed = item(current.id(), current.kind(), current.code(), primaryLanguage, names, displayOrder,
                attributes, active == null || active, marks, current.version());
        // Only org units refer to a unit type, so an item in use is the type of an org unit.
        if (changed.lateral() != current.lateral() && store.isInUse(id)) {
            throw FuhitoException.conflict("ref_item.lateral_in_use", Map.of("id", id, "code", current.code()));
        }
        if (changed.rollup() != current.rollup() && store.isInUse(id)) {
            throw FuhitoException.conflict("ref_item.rollup_in_use", Map.of("id", id, "code", current.code()));
        }
        store.update(changed);
        LocalDate date = from == null ? today.ofRealm() : from;
        rename(id, from, date, changed.names());
        return store.find(id, date).orElseThrow();
    }

    // Names that are the same as the ones of the date change nothing, so they split no period.
    private void rename(String id, LocalDate from, LocalDate date, Localized<RefItemName> names) {
        RefItemPeriod held = RefItemPeriod.on(store.names(id), date);
        if (held.names().equals(names)) {
            return;
        }
        if (from == null || from.equals(held.validFrom())) {
            store.updateNames(held.renamed(names));
        } else if (held.holdsOn(from)) {
            store.updateNames(held.endingAt(from));
            store.insertNames(new RefItemPeriod(newId(), id, from, held.validTo(), names));
        } else {
            store.insertNames(new RefItemPeriod(newId(), id, from, held.validFrom(), names));
        }
    }

    /**
     * Deletes an item. An item that other data refers to cannot be deleted. Deactivate it instead. A value of an
     * attribute of the type {@code REF_ITEM} for the kind of the item refers to it by its code, in any version.
     *
     * @param id the ID
     * @throws FuhitoException when the item does not exist or is in use
     */
    public void delete(String id) {
        RefItem item = stored(id);
        if (store.isInUse(id)) {
            throw FuhitoException.conflict("ref_item.in_use", Map.of("id", id));
        }
        attributeRules.ofRefKind(item.kind()).stream()
                .filter(definition -> store.isInAttribute(item, definition))
                .findFirst()
                .ifPresent(definition -> {
                    throw FuhitoException.conflict("ref_item.in_use", Map.of("id", id,
                            "target", definition.target().name(), "name", definition.name()));
                });
        store.delete(id);
    }

    private RefItem item(String id, String kind, String code, String primaryLanguage,
            Map<String, RefItemName> names, Integer displayOrder, Map<String, Object> attributes, boolean active,
            Marks marks, long version) {
        RefItemMarks.require(kind, code, marks);
        return new RefItem(id, kind, code, RefItemName.check(primaryLanguage, names),
                displayOrder == null ? 0 : displayOrder, attributeRules.check(AttributeDefinition.Target.REF_ITEM, attributes),
                active, Boolean.TRUE.equals(marks.legalEntity()), Boolean.TRUE.equals(marks.actingHead()),
                Boolean.TRUE.equals(marks.lateral()), Boolean.TRUE.equals(marks.rollup()), version, null, null);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
