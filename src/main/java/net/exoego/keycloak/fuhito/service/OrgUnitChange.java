package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * The values of a request to change the details of an org unit from a date. They are not checked yet.
 * The values replace all details. A missing optional value clears it.
 *
 * @param validFrom the first date of the new details, as {@code YYYY-MM-DD}
 * @param name the default name
 * @param names the names in other languages, or null
 * @param shortName the default short name, or null
 * @param shortNames the short names in other languages, or null
 * @param phoneticName the reading of the name for sorting, or null
 * @param unitType the code of the unit type, or null
 * @param displayOrder the position among siblings, or null for 0
 * @param attributes free values, or null
 */
public record OrgUnitChange(String validFrom, String name, Map<String, String> names, String shortName,
        Map<String, String> shortNames, String phoneticName, String unitType, Integer displayOrder,
        Map<String, Object> attributes) {
}
