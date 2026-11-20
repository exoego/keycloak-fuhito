package net.exoego.keycloak.fuhito.service;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Finds an empty value in a write. An empty text, a text of only spaces, and an empty list fail in every field of a
 * REST write, of an import row, and of a row of a restore, also in the names and the attributes. Null is the way to
 * clear a value. A text that the database cannot store as sent fails in the same fields.
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
     * @throws FuhitoException when the row is missing, a value is empty, or a text cannot be stored
     */
    static <R extends Record> R checked(R row) {
        return withoutEmptyValues(Checks.required("row", row));
    }

    /**
     * Tells whether a row of an import passes {@link #checked(Record)}. An import reads stored data only for such
     * rows, so that a text that the database cannot take never reaches it.
     *
     * @param row the row, or null
     * @return true when the row passes
     */
    static boolean passes(Record row) {
        try {
            checked(row);
            return true;
        } catch (FuhitoException e) {
            return false;
        }
    }

    /**
     * Requires values with no empty text and no empty list. The body of a REST write and a row of an import are
     * checked this way.
     *
     * @param <R> the type of the values
     * @param values the values
     * @return the values
     * @throws FuhitoException when a text is empty, only spaces, or cannot be stored, or a list is empty
     */
    public static <R extends Record> R withoutEmptyValues(R values) {
        return withoutEmptyValues(values, component -> !component.getName().equals("nulls"));
    }

    /**
     * Requires no empty text and no empty list in the components of a record that a test picks.
     *
     * @param <R> the type of the values
     * @param values the values
     * @param checked picks the components to check
     * @return the values
     * @throws FuhitoException when a text is empty, only spaces, or cannot be stored, or a list is empty
     */
    static <R extends Record> R withoutEmptyValues(R values, Predicate<RecordComponent> checked) {
        for (RecordComponent component : values.getClass().getRecordComponents()) {
            if (checked.test(component)) {
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
     * @throws FuhitoException when a text in the value is empty, only spaces, or cannot be stored, or a list in it
     *         is empty. A key of an object that cannot be stored fails too.
     */
    static void rejectEmptyValues(String field, Object value) {
        switch (value) {
            case String text -> Checks.storable(field, Checks.optionalText(field, text));
            case Collection<?> items -> Checks.optionalList(field, items)
                    .forEach(item -> rejectEmptyValues(field, item));
            case Map<?, ?> values -> values.forEach((key, item) -> {
                String named = field + "." + key;
                Checks.storable(named, String.valueOf(key));
                rejectEmptyValues(named, item);
            });
            case Record names -> {
                for (RecordComponent component : names.getClass().getRecordComponents()) {
                    rejectEmptyValues(field + "." + component.getName(), valueOf(component, names));
                }
            }
            case null, default -> {
            }
        }
    }

    /**
     * Returns the value of a component of a record.
     *
     * @param component the component
     * @param row the record
     * @return the value, or null
     */
    static Object valueOf(RecordComponent component, Record row) {
        try {
            return component.getAccessor().invoke(row);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("A record gives the values of its components", e);
        }
    }
}
