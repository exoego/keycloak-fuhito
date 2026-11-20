package net.exoego.keycloak.fuhito.service;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Reads a row of an import document. Jackson gives null for a key that the row leaves out and for a key with null,
 * so the reader also keeps the keys with null, in the last component of the row.
 */
public final class ImportRowReader extends StdDeserializer<Object> implements ContextualDeserializer {

    private static final long serialVersionUID = 1L;

    private final transient RecordComponent[] components;
    private final transient Constructor<?> constructor;

    /**
     * Creates the reader before Jackson tells it the type of the row.
     */
    public ImportRowReader() {
        super(Object.class);
        this.components = new RecordComponent[0];
        this.constructor = null;
    }

    private ImportRowReader(Class<?> row) {
        super(row);
        this.components = row.getRecordComponents();
        try {
            this.constructor = row.getDeclaredConstructor(
                    Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("A record has its canonical constructor", e);
        }
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
        return new ImportRowReader(ctxt.getContextualType().getRawClass());
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode row = ctxt.readTree(p);
        if (!row.isObject()) {
            return ctxt.handleUnexpectedToken(handledType(), p);
        }
        Object[] values = new Object[components.length];
        Set<String> nulls = new HashSet<>();
        int last = components.length - 1;
        for (int i = 0; i < last; i++) {
            RecordComponent component = components[i];
            JsonNode value = row.get(component.getName());
            if (value != null) {
                addNulls(component.getName(), value, nulls);
                values[i] = value.isNull() ? null
                        : ctxt.readTreeAsValue(value, ctxt.getTypeFactory().constructType(component.getGenericType()));
            }
        }
        values[last] = Set.copyOf(nulls);
        try {
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("A row takes the values of its components", e);
        }
    }

    private static void addNulls(String path, JsonNode value, Set<String> nulls) {
        if (value.isNull()) {
            nulls.add(path);
        }
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            addNulls(path + "." + entry.getKey(), entry.getValue(), nulls);
        }
    }
}
