package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Finds an empty value in a write. An empty text, a text of only spaces, and an empty list fail in every field of a
 * REST write and of an import row, also in the names and the attributes. Null is the way to clear a value.
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
        return withoutEmptyValues(Checks.required("row", row));
    }

    /**
     * Requires values with no empty text and no empty list. The body of a REST write and a row of an import are
     * checked this way.
     *
     * @param <R> the type of the values
     * @param values the values
     * @return the values
     * @throws FuhitoException when a text is empty or only spaces, or a list is empty
     */
    public static <R extends Record> R withoutEmptyValues(R values) {
        for (RecordComponent component : values.getClass().getRecordComponents()) {
            if (!component.getName().equals("nulls")) {
                rejectEmptyValues(component.getName(), valueOf(component, values));
            }
        }
        return values;
    }

    /**
     * Checks a value that may be a text, a list, an object, or a record of them.
     *
     * @param field the name of the field, for the error
     * @param value the value, or null
     * @throws FuhitoException when a text in the value is empty or only spaces, or a list in it is empty
     */
    static void rejectEmptyValues(String field, Object value) {
        switch (value) {
            case String text -> Checks.optionalText(field, text);
            case Collection<?> items -> Checks.optionalList(field, items)
                    .forEach(item -> rejectEmptyValues(field, item));
            case Map<?, ?> values -> values.forEach((key, item) -> rejectEmptyValues(field + "." + key, item));
            case Record names -> {
                for (RecordComponent component : names.getClass().getRecordComponents()) {
                    rejectEmptyValues(field + "." + component.getName(), valueOf(component, names));
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
