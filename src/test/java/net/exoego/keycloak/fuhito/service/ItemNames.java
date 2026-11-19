package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RefItemName;

/**
 * Names of reference items for the tests.
 */
final class ItemNames {

    private ItemNames() {
    }

    /**
     * Makes the names of an item with one name, in the language {@code en}.
     *
     * @param name the name
     * @return the names
     */
    static Localized<RefItemName> of(String name) {
        return Localized.of("en", new RefItemName(name));
    }

    /**
     * Makes the names of a request with one name, in the language {@code en}.
     *
     * @param name the name
     * @return the names by language tag
     */
    static Map<String, RefItemName> english(String name) {
        return Map.of("en", new RefItemName(name));
    }

    /**
     * Makes the names of a request with one name, in the language {@code ja}.
     *
     * @param name the name
     * @return the names by language tag
     */
    static Map<String, RefItemName> japanese(String name) {
        return Map.of("ja", new RefItemName(name));
    }
}
