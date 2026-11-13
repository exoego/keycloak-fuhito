package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

/**
 * The data of one import. The values are not checked yet.
 * The document describes the state from the effective date.
 * A new thing starts on that date, and a change to an existing thing takes effect on that date.
 *
 * @param effectiveDate the date of the state, as {@code YYYY-MM-DD}
 * @param dryRun true to do all checks and write nothing
 * @param source the name of the system that the rows come from. Rows can override it. The default is "import".
 * @param refItems the reference items, or null
 * @param orgUnits the org units, or null
 */
public record ImportDocument(String effectiveDate, boolean dryRun, String source, List<RefItemRow> refItems,
        List<OrgUnitRow> orgUnits) {

    /**
     * A reference item. It is matched by kind and code.
     *
     * @param kind the kind
     * @param code the code
     * @param name the default name
     * @param names the names in other languages, or null
     * @param displayOrder the position in lists, or null for 0
     * @param attributes free values, or null
     * @param active false to stop the use of the item for new data, or null for true
     */
    public record RefItemRow(String kind, String code, String name, Map<String, String> names,
            Integer displayOrder, Map<String, Object> attributes, Boolean active) {
    }

    /**
     * An org unit. It is matched by source and external ID.
     *
     * @param source the name of the system that the row comes from, or null for the source of the document
     * @param externalId the ID of the unit in that system
     * @param code a code that is unique in the realm, or null. It is used only when the unit is created.
     * @param name the default name
     * @param names the names in other languages, or null
     * @param shortName the default short name, or null
     * @param shortNames the short names in other languages, or null
     * @param phoneticName the reading of the name for sorting, or null
     * @param unitType the code of the unit type, or null
     * @param parentExternalId the external ID of the parent with the same source, or null for a root
     * @param displayOrder the position among siblings, or null for 0
     * @param attributes free values, or null
     * @param validFrom the first date of a new unit, or null for the effective date. An existing unit ignores it.
     * @param validTo the first date on which the unit no longer exists, or null for no end
     */
    public record OrgUnitRow(String source, String externalId, String code, String name, Map<String, String> names,
            String shortName, Map<String, String> shortNames, String phoneticName, String unitType,
            String parentExternalId, Integer displayOrder, Map<String, Object> attributes, String validFrom,
            String validTo) {
    }
}
