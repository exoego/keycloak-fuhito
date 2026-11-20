package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Finds an empty value in a write. An empty text and a text of only spaces fail in every field of a REST write and
 * of an import row, also in the names and the attributes. An empty list fails in an import row too. Null is the way
 * to clear a value.
 */
public final class EmptyValues {

    private EmptyValues() {
    }

    /**
     * Requires a row of an import with no empty value.
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
     * Checks every value of a row of an import.
     *
     * @param row the row
     * @throws FuhitoException when a value is empty
     */
    static void reject(Record row) {
        components(row, true);
    }

    /**
     * Requires values with no empty text. The body of a REST write is checked this way.
     *
     * @param <R> the type of the values
     * @param values the values
     * @return the values
     * @throws FuhitoException when a text is empty or only spaces
     */
    public static <R extends Record> R withoutEmptyTexts(R values) {
        components(values, false);
        return values;
    }

    /**
     * Checks a value that may be a text, a list, an object, or a record of them.
     *
     * @param field the name of the field, for the error
     * @param value the value, or null
     * @throws FuhitoException when a text in the value is empty or only spaces
     */
    static void rejectEmptyTexts(String field, Object value) {
        check(field, value, false);
    }

    private static void components(Record values, boolean lists) {
        for (RecordComponent component : values.getClass().getRecordComponents()) {
            if (!component.getName().equals("nulls")) {
                check(component.getName(), valueOf(component, values), lists);
            }
        }
    }

    private static void check(String field, Object value, boolean lists) {
        switch (value) {
            case String text -> Checks.optionalText(field, text);
            case Collection<?> items -> {
                if (lists && items.isEmpty()) {
                    throw FuhitoException.invalid("invalid.blank", Map.of("field", field));
                }
                items.forEach(item -> check(field, item, lists));
            }
            case Map<?, ?> values -> values.forEach((key, item) -> check(field + "." + key, item, lists));
            case Record names -> {
                for (RecordComponent component : names.getClass().getRecordComponents()) {
                    check(field + "." + component.getName(), valueOf(component, names), lists);
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
