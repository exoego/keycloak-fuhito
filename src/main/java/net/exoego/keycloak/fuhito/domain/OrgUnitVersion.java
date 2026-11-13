package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * The details of an org unit for a period.
 *
 * @param id the ID
 * @param orgUnitId the ID of the org unit
 * @param period the dates when these details are true
 * @param name the default name
 * @param names the names in other languages, by language tag
 * @param shortName the default short name, or null
 * @param shortNames the short names in other languages, by language tag
 * @param phoneticName the reading of the name for sorting, or null
 * @param unitTypeId the ID of the unit type, or null
 * @param displayOrder the position among siblings, lowest first
 * @param attributes free values that the realm defines
 */
public record OrgUnitVersion(String id, String orgUnitId, ValidPeriod period, String name,
        Map<String, String> names, String shortName, Map<String, String> shortNames, String phoneticName,
        String unitTypeId, int displayOrder, Map<String, Object> attributes) {
}
