package net.exoego.keycloak.fuhito.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.JsonNumbers;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.store.AttributeDefinitionStore;
import net.exoego.keycloak.fuhito.store.AttributeValueStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the definitions of free attributes of one realm, and the check of writes against them. A write of a
 * definition keeps the stored values valid. It fills a new required attribute, and clears the values of a deleted
 * one, in every row of the resource.
 */
public class AttributeDefinitionService implements AttributeRules {

    private static final int MAX_NAME_LENGTH = 64;

    private final AttributeDefinitionStore store;
    private final AttributeValueStore values;
    private final RefItemStore refItems;
    private final Predicate<String> knownKinds;

    /**
     * A write of a definition, with the resources whose rows it filled or cleared.
     *
     * @param definition the definition after the write
     * @param values a write of each resource whose rows got or lost a value, in the order of the IDs
     */
    public record Written(AttributeDefinition definition, List<Write.OfAttributeValue> values) {
    }

    /**
     * Creates the service.
     *
     * @param store the storage of definitions of the realm
     * @param values the stored values of the attributes of the realm
     * @param refItems the storage of reference items of the realm
     */
    public AttributeDefinitionService(AttributeDefinitionStore store, AttributeValueStore values,
            RefItemStore refItems) {
        this(store, values, refItems,
                code -> Arrays.stream(RefKind.values()).anyMatch(kind -> kind.name().equals(code)));
    }

    /**
     * Creates the service with the kinds of reference data of the realm.
     *
     * @param store the storage of definitions of the realm
     * @param values the stored values of the attributes of the realm
     * @param refItems the storage of reference items of the realm
     * @param knownKinds tells whether a code is a fixed kind or a kind of the realm
     */
    public AttributeDefinitionService(AttributeDefinitionStore store, AttributeValueStore values,
            RefItemStore refItems, Predicate<String> knownKinds) {
        this.store = store;
        this.values = values;
        this.refItems = refItems;
        this.knownKinds = knownKinds;
    }

    /**
     * Lists the definitions.
     *
     * @param target the kind of resource as text, or null for all
     * @return the definitions, by kind of resource and name
     * @throws FuhitoException when the kind of resource is unknown
     */
    public List<AttributeDefinition> list(String target) {
        return store.list(Checks.optionalEnum("target", AttributeDefinition.Target.class, target, null));
    }

    /**
     * Returns a definition.
     *
     * @param id the ID
     * @return the definition
     * @throws FuhitoException when the realm has no such definition
     */
    public AttributeDefinition get(String id) {
        return store.find(id)
                .orElseThrow(() -> FuhitoException.notFound("attribute.not_found", Map.of("id", id)));
    }

    /**
     * Finds a definition.
     *
     * @param id the ID
     * @return the definition, or empty when the realm has no such definition
     */
    public Optional<AttributeDefinition> find(String id) {
        return store.find(id);
    }

    /**
     * Defines an attribute of a kind of resource. A required attribute needs a value in every row of the resource,
     * in each version of a dated resource too. When a row has none, the value to fill goes into each such row.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @param type the type of the value
     * @param refKind the kind of reference data for the type {@code REF_ITEM}, or null
     * @param multiple true when the value is a list, or null for false
     * @param required true when every write must give a value, or null for false
     * @param fillValue the value for the rows with no value, or null
     * @return the new definition, with the resources whose rows got the value
     * @throws FuhitoException when a value is bad, the kind of resource has the name already, or a row needs the
     *         value to fill and the write gives none
     */
    public Written create(String target, String name, String type, String refKind, Boolean multiple,
            Boolean required, Object fillValue) {
        AttributeDefinition.Target of = Checks.optionalEnum("target", AttributeDefinition.Target.class,
                Checks.required("target", target), null);
        String checkedName = name(name);
        if (store.list(of).stream().anyMatch(other -> other.name().equals(checkedName))) {
            throw FuhitoException.conflict("attribute.name_taken", Map.of("target", of.name(), "name", checkedName));
        }
        AttributeDefinition definition = definition(UUID.randomUUID().toString(), of, checkedName, type, refKind,
                multiple, required, 1);
        Object fill = fillValue(definition, fillValue);
        boolean filling = definition.required() && requireFill(definition, fill);
        store.insert(definition);
        return new Written(definition, filling ? fill(definition, fill) : List.of());
    }

    /**
     * Changes whether a definition is required. The kind of resource, the name, the type, the kind of reference
     * data, and whether the value is a list never change. When the attribute becomes required and a row has no
     * value, the value to fill goes into each such row.
     *
     * @param id the ID
     * @param type the type of the value, or null for the stored one
     * @param refKind the kind of reference data, or null for the stored one
     * @param multiple true when the value is a list, or null for the stored one
     * @param required true when every write must give a value, or null for false
     * @param fillValue the value for the rows with no value, or null
     * @return the changed definition, with the resources whose rows got the value
     * @throws FuhitoException when the definition does not exist, the write gives another value of a field that
     *         never changes, the value to fill is bad, or a row needs it and the write gives none
     */
    public Written replace(String id, String type, String refKind, Boolean multiple, Boolean required,
            Object fillValue) {
        AttributeDefinition current = get(id);
        Checks.fixed("type", current.type().name(), type);
        Checks.fixed("refKind", current.refKind(), refKind);
        Checks.fixed("multiple", current.multiple(), multiple);
        AttributeDefinition changed = new AttributeDefinition(id, current.target(), current.name(), current.type(),
                current.refKind(), current.multiple(), Boolean.TRUE.equals(required), current.version());
        Object fill = fillValue(changed, fillValue);
        boolean filling = changed.required() && !current.required() && requireFill(changed, fill);
        store.update(changed);
        return new Written(get(id), filling ? fill(changed, fill) : List.of());
    }

    /**
     * Deletes a definition. While a row of the resource has a value of the name, the delete fails, or removes the
     * name from every such row first. A row with null for the name has no value.
     *
     * @param id the ID
     * @param clearValues true to remove the name from the rows with a value of it
     * @return a write of each resource whose rows lost the value, in the order of the IDs
     * @throws FuhitoException when the definition does not exist, or a row has a value of the name and the delete
     *         does not remove it
     */
    public List<Write.OfAttributeValue> delete(String id, boolean clearValues) {
        AttributeDefinition definition = get(id);
        long used = values.countPresent(definition.target(), definition.name());
        if (used > 0 && !clearValues) {
            throw FuhitoException.conflict("attribute.in_use", Map.of("id", id, "target",
                    definition.target().name(), "name", definition.name(), "count", Long.toString(used)));
        }
        List<Write.OfAttributeValue> cleared = used == 0 ? List.of()
                : writes(definition, values.clear(definition.target(), definition.name()));
        store.delete(id);
        return cleared;
    }

    /**
     * Checks the value that a write of a definition puts into the rows with no value.
     *
     * @param definition the definition after the write
     * @param fillValue the value, or null
     * @return the same value
     * @throws FuhitoException when the definition is not required, or the value does not fit it or has spaces around
     *         a text of the type {@code STRING}
     */
    Object fillValue(AttributeDefinition definition, Object fillValue) {
        if (fillValue == null) {
            return null;
        }
        if (!definition.required()) {
            throw FuhitoException.invalid("invalid.not_together",
                    Map.of("field", "fillValue", "otherField", "required"));
        }
        EmptyValues.rejectEmptyValues("fillValue", fillValue);
        if (!fitsAll(definition, fillValue, this::hasItem)) {
            throw FuhitoException.invalid("attribute.bad_value",
                    Map.of("name", definition.name(), "type", typeOf(definition)));
        }
        requireUnpadded("fillValue", definition, fillValue);
        AttributeSize.require(Map.of(definition.name(), fillValue));
        return fillValue;
    }

    // Tells whether a row has no value, and requires the value to fill then. Each such row must stay in the limit
    // of the size of attributes with it.
    private boolean requireFill(AttributeDefinition definition, Object fillValue) {
        long missing = values.countMissing(definition.target(), definition.name());
        if (missing == 0) {
            return false;
        }
        if (fillValue == null) {
            throw FuhitoException.invalid("attribute.fill_value_required", Map.of("target",
                    definition.target().name(), "name", definition.name(), "count", Long.toString(missing)));
        }
        long size = values.largestAfterFill(definition.target(), definition.name(), fillValue);
        if (size > AttributeSize.MAX_BYTES) {
            throw FuhitoException.invalid("attribute.too_large",
                    Map.of("size", Long.toString(size), "max", Integer.toString(AttributeSize.MAX_BYTES)));
        }
        return true;
    }

    private List<Write.OfAttributeValue> fill(AttributeDefinition definition, Object fillValue) {
        return writes(definition, values.fill(definition.target(), definition.name(), fillValue));
    }

    private static List<Write.OfAttributeValue> writes(AttributeDefinition definition, List<String> resourceIds) {
        return resourceIds.stream().map(id -> new Write.OfAttributeValue(definition.target(), id)).toList();
    }

    private AttributeDefinition definition(String id, AttributeDefinition.Target target, String name,
            String type, String refKind, Boolean multiple, Boolean required, long version) {
        AttributeDefinition.Type of = Checks.optionalEnum("type", AttributeDefinition.Type.class,
                Checks.required("type", type), null);
        requireRefKind(of, refKind, knownKinds);
        return new AttributeDefinition(id, target, name, of, refKind, Boolean.TRUE.equals(multiple),
                Boolean.TRUE.equals(required), version);
    }

    /**
     * Requires a kind of reference data for the type {@code REF_ITEM}, and none for another type.
     *
     * @param type the type of the value
     * @param refKind the code of the kind, or null
     * @param knownKinds tells whether a code is a fixed kind or a kind of the realm
     * @throws FuhitoException when the kind is unknown, missing for {@code REF_ITEM}, or given for another type
     */
    static void requireRefKind(AttributeDefinition.Type type, String refKind, Predicate<String> knownKinds) {
        if (refKind != null && !knownKinds.test(refKind)) {
            throw FuhitoException.invalid("invalid.choice", Map.of("field", "refKind", "value", refKind));
        }
        if ((type == AttributeDefinition.Type.REF_ITEM) != (refKind != null)) {
            throw FuhitoException.invalid("attribute.ref_kind", Map.of("type", type.name()));
        }
    }

    @Override
    public Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes) {
        return check(target, attributes, store.list(target), this::hasItem);
    }

    @Override
    public List<AttributeDefinition> ofRefKind(String refKind) {
        return store.list(null).stream()
                .filter(definition -> definition.type() == AttributeDefinition.Type.REF_ITEM
                        && refKind.equals(definition.refKind()))
                .toList();
    }

    @Override
    public void requireDefined(AttributeDefinition.Target target, Map<String, Object> attributes) {
        if (attributes != null && !attributes.isEmpty()) {
            requireDefined(target, attributes, store.list(target));
        }
    }

    @Override
    public AttributeRules remembering() {
        Map<AttributeDefinition.Target, List<AttributeDefinition>> read =
                new EnumMap<>(AttributeDefinition.Target.class);
        return new Rules(target -> read.computeIfAbsent(target, store::list), this::hasItem);
    }

    /**
     * Returns rules that check attributes with every rule of a write, against the given definitions and items in
     * place of those of the realm.
     *
     * @param definitions the definitions of every kind of resource
     * @param items tells whether an item has a kind and a code
     * @return the rules
     */
    static AttributeRules of(List<AttributeDefinition> definitions, BiPredicate<String, String> items) {
        Map<AttributeDefinition.Target, List<AttributeDefinition>> byTarget =
                new EnumMap<>(AttributeDefinition.Target.class);
        definitions.forEach(d -> byTarget.computeIfAbsent(d.target(), target -> new ArrayList<>()).add(d));
        return new Rules(target -> byTarget.getOrDefault(target, List.of()), items);
    }

    // Rules against the definitions that a function gives for each kind of resource.
    private record Rules(Function<AttributeDefinition.Target, List<AttributeDefinition>> definitions,
            BiPredicate<String, String> items) implements AttributeRules {

        @Override
        public Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes) {
            return AttributeDefinitionService.check(target, attributes, definitions.apply(target), items);
        }

        @Override
        public void requireDefined(AttributeDefinition.Target target, Map<String, Object> attributes) {
            if (attributes != null && !attributes.isEmpty()) {
                AttributeDefinitionService.requireDefined(target, attributes, definitions.apply(target));
            }
        }
    }

    private boolean hasItem(String kind, String code) {
        return refItems.findByCode(kind, code).isPresent();
    }

    private static void requireDefined(AttributeDefinition.Target target, Map<String, Object> attributes,
            List<AttributeDefinition> definitions) {
        Set<String> names = definitions.stream().map(AttributeDefinition::name).collect(Collectors.toSet());
        attributes.keySet().stream().filter(name -> !names.contains(name)).findFirst().ifPresent(name -> {
            throw FuhitoException.invalid("attribute.unknown", Map.of("target", target.name(), "name", name));
        });
    }

    private static Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes,
            List<AttributeDefinition> definitions, BiPredicate<String, String> items) {
        Map<String, Object> given = AttributeSize.require(attributes == null ? Map.of() : attributes);
        // A CSV cell cannot hold an empty text or an empty list, so a stored value must not be one either.
        EmptyValues.rejectEmptyValues("attributes", given);
        requireDefined(target, given, definitions);
        Map<String, Object> values = StoredAttributes.of(given);
        Map<String, AttributeDefinition> byName = new HashMap<>();
        definitions.forEach(definition -> byName.put(definition.name(), definition));
        values.forEach((name, value) -> {
            AttributeDefinition definition = byName.get(name);
            if (!fitsAll(definition, value, items)) {
                throw FuhitoException.invalid("attribute.bad_value", Map.of("name", name, "type", typeOf(definition)));
            }
            requireUnpadded("attributes." + name, definition, value);
        });
        byName.values().stream()
                .filter(definition -> definition.required() && !values.containsKey(definition.name()))
                .findFirst()
                .ifPresent(definition -> {
                    throw FuhitoException.invalid("attribute.required",
                            Map.of("target", target.name(), "name", definition.name()));
                });
        return values;
    }

    /**
     * Checks the name of an attribute.
     *
     * @param name the name
     * @return the same name
     * @throws FuhitoException when the name is missing, blank, too long, or has spaces around it
     */
    static String name(String name) {
        Checks.required("name", name);
        if (name.isBlank() || !name.strip().equals(name) || !Checks.fits(name, MAX_NAME_LENGTH)) {
            throw FuhitoException.invalid("attribute.bad_name", Map.of("name", name));
        }
        return name;
    }

    private static void requireUnpadded(String field, AttributeDefinition definition, Object value) {
        if (definition.type() == AttributeDefinition.Type.STRING) {
            List<?> texts = definition.multiple() ? (List<?>) value : List.of(value);
            texts.forEach(text -> Checks.unpadded(field, (String) text));
        }
    }

    private static boolean fitsAll(AttributeDefinition definition, Object value, BiPredicate<String, String> items) {
        if (!definition.multiple()) {
            return fits(definition, value, items);
        }
        return value instanceof List<?> list && list.stream().allMatch(element -> fits(definition, element, items));
    }

    private static String typeOf(AttributeDefinition definition) {
        String type = definition.type().name() + (definition.multiple() ? "[]" : "");
        return definition.refKind() == null ? type : type + " " + definition.refKind();
    }

    private static boolean fits(AttributeDefinition definition, Object value, BiPredicate<String, String> items) {
        return switch (definition.type()) {
            case STRING -> value instanceof String;
            // A client that reads a number as a double, such as JavaScript or a spreadsheet, must read the same one.
            case NUMBER -> value instanceof Number number && JsonNumbers.fitsDouble(number);
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof String text && isDate(text);
            case REF_ITEM -> value instanceof String code && items.test(definition.refKind(), code);
        };
    }

    private static boolean isDate(String text) {
        try {
            Checks.inRange(LocalDate.parse(text));
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
