package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Rows of an import as a JSON document gives them.
 */
final class ImportRows {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ImportRows() {
    }

    /**
     * Reads a row as the JSON import reads it.
     *
     * @param <R> the type of the row
     * @param type the type of the row
     * @param json the row
     * @return the row
     */
    static <R> R read(Class<R> type, String json) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Gives null for some keys of a row, as a JSON row does with {@code "key": null}.
     *
     * @param <R> the type of the row
     * @param row the row, whose last component holds the keys with null
     * @param keys the keys
     * @return the row with null for the keys
     */
    @SuppressWarnings("unchecked")
    static <R extends Record> R clearing(R row, String... keys) {
        RecordComponent[] components = row.getClass().getRecordComponents();
        try {
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = components[i].getAccessor().invoke(row);
            }
            Set<String> nulls = new HashSet<>((Set<String>) values[components.length - 1]);
            nulls.addAll(List.of(keys));
            values[components.length - 1] = Set.copyOf(nulls);
            return (R) row.getClass().getDeclaredConstructor(Arrays.stream(components)
                    .map(RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError("A row takes the values of its components", e);
        }
    }
}
