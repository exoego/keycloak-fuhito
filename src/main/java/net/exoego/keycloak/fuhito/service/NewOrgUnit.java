package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.OrgUnitName;

/**
 * The values of a request to create an org unit. They are not checked yet.
 *
 * @param code a code that is unique in the realm, or null
 * @param primaryLanguage the language tag of the primary names
 * @param names the names by language tag
 * @param unitType the code of the unit type, or null
 * @param workLocation the code of the work location that the unit gives its members, or null
 * @param costCenter the code of the cost center that the unit gives its members, or null
 * @param parentId the ID of the parent in the default hierarchy, or null for a root
 * @param validFrom the first date of the unit, as {@code YYYY-MM-DD}
 * @param displayOrder the position among siblings, or null for 0
 * @param attributes free values, or null
 * @param externalId the ID of the unit in the system that the source names, or null
 * @param source the name of the system that makes the unit, or null
 */
public record NewOrgUnit(String code, String primaryLanguage, Map<String, OrgUnitName> names, String unitType,
        String workLocation, String costCenter, String parentId, String validFrom, Integer displayOrder,
        Map<String, Object> attributes, String externalId, String source) {

    /**
     * Creates the values with no work location and no cost center.
     *
     * @param code a code that is unique in the realm, or null
     * @param primaryLanguage the language tag of the primary names
     * @param names the names by language tag
     * @param unitType the code of the unit type, or null
     * @param parentId the ID of the parent in the default hierarchy, or null for a root
     * @param validFrom the first date of the unit, as {@code YYYY-MM-DD}
     * @param displayOrder the position among siblings, or null for 0
     * @param attributes free values, or null
     * @param externalId the ID of the unit in the system that the source names, or null
     * @param source the name of the system that makes the unit, or null
     */
    public NewOrgUnit(String code, String primaryLanguage, Map<String, OrgUnitName> names, String unitType,
            String parentId, String validFrom, Integer displayOrder, Map<String, Object> attributes,
            String externalId, String source) {
        this(code, primaryLanguage, names, unitType, null, null, parentId, validFrom, displayOrder, attributes,
                externalId, source);
    }
}
