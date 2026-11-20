package net.exoego.keycloak.fuhito.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.AttributeDefinitionStore;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Operations on the definitions of free attributes of one realm, and the check of writes against them.
 */
public class AttributeDefinitionService implements AttributeRules {

    private static final int MAX_NAME_LENGTH = 64;

    private final AttributeDefinitionStore store;
    private final RefItemStore refItems;
    private final Predicate<String> knownKinds;

    /**
     * Creates the service.
     *
     * @param store the storage of definitions of the realm
     * @param refItems the storage of reference items of the realm
     */
    public AttributeDefinitionService(AttributeDefinitionStore store, RefItemStore refItems) {
        this(store, refItems, code -> Arrays.stream(RefKind.values()).anyMatch(kind -> kind.name().equals(code)));
    }

    /**
     * Creates the service with the kinds of reference data of the realm.
     *
     * @param store the storage of definitions of the realm
     * @param refItems the storage of reference items of the realm
     * @param knownKinds tells whether a code is a fixed kind or a kind of the realm
     */
    public AttributeDefinitionService(AttributeDefinitionStore store, RefItemStore refItems,
            Predicate<String> knownKinds) {
        this.store = store;
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
     * Defines an attribute of a kind of resource.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @param type the type of the value
     * @param refKind the kind of reference data for the type {@code REF_ITEM}, or null
     * @param multiple true when the value is a list, or null for false
     * @param required true when every write must give a value, or null for false
     * @return the new definition
     * @throws FuhitoException when a value is bad or the kind of resource has the name already
     */
    public AttributeDefinition create(String target, String name, String type, String refKind, Boolean multiple,
            Boolean required) {
        AttributeDefinition.Target of = Checks.optionalEnum("target", AttributeDefinition.Target.class,
                Checks.required("target", target), null);
        String checkedName = Checks.required("name", name);
        if (checkedName.isBlank() || !checkedName.strip().equals(checkedName)
                || checkedName.codePointCount(0, checkedName.length()) > MAX_NAME_LENGTH) {
            throw FuhitoException.invalid("attribute.bad_name", Map.of("name", checkedName));
        }
        if (store.list(of).stream().anyMatch(other -> other.name().equals(checkedName))) {
            throw FuhitoException.conflict("attribute.name_taken", Map.of("target", of.name(), "name", checkedName));
        }
        AttributeDefinition definition = definition(UUID.randomUUID().toString(), of, checkedName, type, refKind,
                multiple, required, 1);
        store.insert(definition);
        return definition;
    }

    /**
     * Replaces the type and the rule of a definition. The kind of resource and the name never change.
     * Stored values stay as they are.
     *
     * @param id the ID
     * @param type the type of the value
     * @param refKind the kind of reference data for the type {@code REF_ITEM}, or null
     * @param multiple true when the value is a list, or null for false
     * @param required true when every write must give a value, or null for false
     * @return the changed definition
     * @throws FuhitoException when the definition does not exist or a value is bad
     */
    public AttributeDefinition replace(String id, String type, String refKind, Boolean multiple, Boolean required) {
        AttributeDefinition current = get(id);
        AttributeDefinition changed = definition(id, current.target(), current.name(), type, refKind, multiple,
                required, current.version());
        store.update(changed);
        return get(id);
    }

    /**
     * Deletes a definition. Stored values stay as they are.
     *
     * @param id the ID
     * @throws FuhitoException when the definition does not exist
     */
    public void delete(String id) {
        get(id);
        store.delete(id);
    }

    private AttributeDefinition definition(String id, AttributeDefinition.Target target, String name,
            String type, String refKind, Boolean multiple, Boolean required, long version) {
        AttributeDefinition.Type of = Checks.optionalEnum("type", AttributeDefinition.Type.class,
                Checks.required("type", type), null);
        String kind = refKind;
        if (kind != null && !knownKinds.test(kind)) {
            throw FuhitoException.invalid("invalid.choice", Map.of("field", "refKind", "value", kind));
        }
        if ((of == AttributeDefinition.Type.REF_ITEM) != (kind != null)) {
            throw FuhitoException.invalid("attribute.ref_kind", Map.of("type", of.name()));
        }
        return new AttributeDefinition(id, target, name, of, kind, Boolean.TRUE.equals(multiple),
                Boolean.TRUE.equals(required), version);
    }

    @Override
    public Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes) {
        return check(target, attributes, store.list(target));
    }

    @Override
    public AttributeRules remembering() {
        Map<AttributeDefinition.Target, List<AttributeDefinition>> read =
                new EnumMap<>(AttributeDefinition.Target.class);
        return (target, attributes) -> check(target, attributes, read.computeIfAbsent(target, store::list));
    }

    private Map<String, Object> check(AttributeDefinition.Target target, Map<String, Object> attributes,
            List<AttributeDefinition> definitions) {
        Map<String, Object> values = AttributeSize.require(attributes == null ? Map.of() : attributes);
        // A CSV cell cannot hold an empty text, so a stored value must not be one either.
        EmptyValues.rejectEmptyTexts("attributes", values);
        Map<String, AttributeDefinition> byName = new HashMap<>();
        definitions.forEach(definition -> byName.put(definition.name(), definition));
        values.forEach((name, value) -> {
            AttributeDefinition definition = byName.get(name);
            if (definition == null) {
                throw FuhitoException.invalid("attribute.unknown", Map.of("target", target.name(), "name", name));
            }
            if (value != null && !fitsAll(definition, value)) {
                throw FuhitoException.invalid("attribute.bad_value", Map.of("name", name, "type", typeOf(definition)));
            }
        });
        byName.values().stream()
                .filter(definition -> definition.required() && missing(values.get(definition.name())))
                .findFirst()
                .ifPresent(definition -> {
                    throw FuhitoException.invalid("attribute.required",
                            Map.of("target", target.name(), "name", definition.name()));
                });
        return values;
    }

    private boolean fitsAll(AttributeDefinition definition, Object value) {
        if (!definition.multiple()) {
            return fits(definition, value);
        }
        return value instanceof List<?> list && list.stream().allMatch(element -> fits(definition, element));
    }

    private static boolean missing(Object value) {
        return value == null || (value instanceof List<?> list && list.isEmpty());
    }

    private static String typeOf(AttributeDefinition definition) {
        String type = definition.type().name() + (definition.multiple() ? "[]" : "");
        return definition.refKind() == null ? type : type + " " + definition.refKind();
    }

    private boolean fits(AttributeDefinition definition, Object value) {
        return switch (definition.type()) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof String text && isDate(text);
            case REF_ITEM -> value instanceof String code
                    && refItems.findByCode(definition.refKind(), code).isPresent();
        };
    }

    private static boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
