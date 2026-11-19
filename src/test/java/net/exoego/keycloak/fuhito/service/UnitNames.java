package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.OrgUnitName;

/**
 * Names of org units for the tests.
 */
final class UnitNames {

    private UnitNames() {
    }

    /**
     * Makes the names of a request with only a name, in the language {@code ja}.
     *
     * @param name the name
     * @return the names by language tag
     */
    static Map<String, OrgUnitName> ja(String name) {
        return Map.of("ja", new OrgUnitName(name, null, null));
    }
}
