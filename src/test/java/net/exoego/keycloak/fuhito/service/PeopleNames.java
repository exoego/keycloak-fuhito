package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.PersonName;

/**
 * Names of people for the tests.
 */
final class PeopleNames {

    private PeopleNames() {
    }

    /**
     * Makes the names of a request with only a name, in the language {@code ja}.
     *
     * @param name the name
     * @return the names by language tag
     */
    static Map<String, PersonName> japanese(String name) {
        return Map.of("ja", new PersonName(name, null));
    }
}
