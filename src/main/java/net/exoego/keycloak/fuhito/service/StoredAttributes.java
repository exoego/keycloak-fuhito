package net.exoego.keycloak.fuhito.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The form in which a write stores free attributes. A name with null has no value, so the stored attributes leave it
 * out. Two writes of the same values then store the same row, whether they leave a name out or give it null. Every
 * write of attributes goes through {@link AttributeRules#check}, which gives this form.
 */
final class StoredAttributes {

    private StoredAttributes() {
    }

    /**
     * Gives the attributes of a write as a write stores them.
     *
     * @param attributes the attributes of the write, or null for none
     * @return the attributes without the names with null, in their order, never null
     */
    static Map<String, Object> of(Map<String, Object> attributes) {
        Map<String, Object> stored = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((name, value) -> {
                if (value != null) {
                    stored.put(name, value);
                }
            });
        }
        return stored;
    }
}
