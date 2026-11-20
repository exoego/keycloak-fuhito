package net.exoego.keycloak.fuhito.service;

import java.util.Set;

/**
 * A row of an import document. A row that leaves a key out keeps the stored value, and a key with null clears it.
 * A new thing has nothing stored, so both mean no value there.
 */
interface ImportRow {

    /**
     * Returns the keys that the row gives as null. A key in an object of the row has a path, such as
     * {@code names.en.shortName}.
     *
     * @return the keys with null
     */
    Set<String> nulls();

    /**
     * Tells whether the row gives a key, with a value or with null.
     *
     * @param key the key
     * @param value the value of the row, or null
     * @return false when the row leaves the key out
     */
    default boolean gives(String key, Object value) {
        return value != null || nulls().contains(key);
    }

    /**
     * Tells whether the row gives null for a key.
     *
     * @param key the key
     * @return true for null, false for a value and for a key that the row leaves out
     */
    default boolean isNull(String key) {
        return nulls().contains(key);
    }

    /**
     * Returns the value that a row of a stored thing gives.
     *
     * @param <T> the type of the value
     * @param key the key
     * @param value the value of the row, or null
     * @param stored the stored value, or null
     * @return the stored value when the row leaves the key out, or else the value of the row
     */
    default <T> T or(String key, T value, T stored) {
        return gives(key, value) ? value : stored;
    }
}
