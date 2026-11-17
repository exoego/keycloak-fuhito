package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * The values of a request to create an org unit. They are not checked yet.
 *
 * @param code a code that is unique in the realm, or null
 * @param name the default name
 * @param names the names in other languages, or null
 * @param shortName the default short name, or null
 * @param shortNames the short names in other languages, or null
 * @param phoneticName the reading of the name for sorting, or null
 * @param unitType the code of the unit type, or null
 * @param workLocation the code of the work location that the unit gives its members, or null
 * @param costCenter the code of the cost center that the unit gives its members, or null
 * @param parentId the ID of the parent in the default hierarchy, or null for a root
 * @param validFrom the first date of the unit, as {@code YYYY-MM-DD}
 * @param displayOrder the position among siblings, or null for 0
 * @param attributes free values, or null
 * @param externalId the ID of the unit in the system it came from, or null
 * @param source the name of that system, or null
 */
public record NewOrgUnit(String code, String name, Map<String, String> names, String shortName,
        Map<String, String> shortNames, String phoneticName, String unitType, String workLocation, String costCenter,
        String parentId, String validFrom, Integer displayOrder, Map<String, Object> attributes, String externalId,
        String source) {

    /**
     * Creates the values with no work location and no cost center.
     *
     * @param code a code that is unique in the realm, or null
     * @param name the default name
     * @param names the names in other languages, or null
     * @param shortName the default short name, or null
     * @param shortNames the short names in other languages, or null
     * @param phoneticName the reading of the name for sorting, or null
     * @param unitType the code of the unit type, or null
     * @param parentId the ID of the parent in the default hierarchy, or null for a root
     * @param validFrom the first date of the unit, as {@code YYYY-MM-DD}
     * @param displayOrder the position among siblings, or null for 0
     * @param attributes free values, or null
     * @param externalId the ID of the unit in the system it came from, or null
     * @param source the name of that system, or null
     */
    public NewOrgUnit(String code, String name, Map<String, String> names, String shortName,
            Map<String, String> shortNames, String phoneticName, String unitType, String parentId, String validFrom,
            Integer displayOrder, Map<String, Object> attributes, String externalId, String source) {
        this(code, name, names, shortName, shortNames, phoneticName, unitType, null, null, parentId, validFrom,
                displayOrder, attributes, externalId, source);
    }
}
