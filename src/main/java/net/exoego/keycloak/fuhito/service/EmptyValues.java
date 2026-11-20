package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Finds an empty value in a row of an import. An empty text, a text of only spaces, and an empty list fail in every
 * field, also in the names and the attributes. Null is the way to clear a value.
 */
final class EmptyValues {

    private EmptyValues() {
    }

    /**
     * Requires a row with no empty value.
     *
     * @param <R> the type of the row
     * @param row the row
     * @return the row
     * @throws FuhitoException when the row is missing or a value is empty
     */
    static <R extends Record> R checked(R row) {
        reject(Checks.required("row", row));
        return row;
    }

    /**
     * Checks every value of a row.
     *
     * @param row the row
     * @throws FuhitoException when a value is empty
     */
    static void reject(Record row) {
        for (RecordComponent component : row.getClass().getRecordComponents()) {
            if (!component.getName().equals("nulls")) {
                check(component.getName(), valueOf(component, row));
            }
        }
    }

    private static void check(String field, Object value) {
        switch (value) {
            case String text when text.isBlank() -> throw FuhitoException.invalid("invalid.blank",
                    Map.of("field", field));
            case Collection<?> items -> {
                if (items.isEmpty()) {
                    throw FuhitoException.invalid("invalid.blank", Map.of("field", field));
                }
                items.forEach(item -> check(field, item));
            }
            case Map<?, ?> values -> values.forEach((key, item) -> check(field + "." + key, item));
            case Record names -> {
                for (RecordComponent component : names.getClass().getRecordComponents()) {
                    check(field + "." + component.getName(), valueOf(component, names));
                }
            }
            case null, default -> {
            }
        }
    }

    private static Object valueOf(RecordComponent component, Record row) {
        try {
            return component.getAccessor().invoke(row);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("A record gives the values of its components", e);
        }
    }
}
