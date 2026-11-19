package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.OrgUnitName;

/**
 * The values of a request to change the details of an org unit from a date. They are not checked yet.
 * The values replace all details. A missing optional value clears it.
 *
 * @param validFrom the first date of the new details, as {@code YYYY-MM-DD}
 * @param code the code, or null
 * @param primaryLanguage the language tag of the primary names
 * @param names the names by language tag
 * @param unitType the code of the unit type, or null
 * @param workLocation the code of the work location that the unit gives its members, or null
 * @param costCenter the code of the cost center that the unit gives its members, or null
 * @param displayOrder the position among siblings, or null for 0
 * @param attributes free values, or null
 */
public record OrgUnitChange(String validFrom, String code, String primaryLanguage, Map<String, OrgUnitName> names,
        String unitType, String workLocation, String costCenter, Integer displayOrder,
        Map<String, Object> attributes) {

    /**
     * Creates the values with no work location and no cost center.
     *
     * @param validFrom the first date of the new details, as {@code YYYY-MM-DD}
     * @param code the code, or null
     * @param primaryLanguage the language tag of the primary names
     * @param names the names by language tag
     * @param unitType the code of the unit type, or null
     * @param displayOrder the position among siblings, or null for 0
     * @param attributes free values, or null
     */
    public OrgUnitChange(String validFrom, String code, String primaryLanguage, Map<String, OrgUnitName> names,
            String unitType, Integer displayOrder, Map<String, Object> attributes) {
        this(validFrom, code, primaryLanguage, names, unitType, null, null, displayOrder, attributes);
    }
}
