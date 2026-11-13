package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import net.exoego.keycloak.fuhito.service.NewOrgUnit;
import net.exoego.keycloak.fuhito.service.OrgUnitChange;
import net.exoego.keycloak.fuhito.service.OrgUnitState;

/**
 * An org unit as it is on one date, in requests and responses.
 *
 * @param id the ID. Requests ignore it.
 * @param code a code that is unique in the realm, or null
 * @param name the default name
 * @param names the names in other languages, by language tag
 * @param shortName the default short name, or null
 * @param shortNames the short names in other languages, by language tag
 * @param phoneticName the reading of the name for sorting, or null
 * @param unitType the code of the unit type, or null
 * @param parentId the ID of the parent in the default hierarchy, or null for a root
 * @param validFrom the first date of these details, as {@code YYYY-MM-DD}
 * @param validTo the first date after these details, or null for no end. Requests ignore it.
 * @param displayOrder the position among siblings, lowest first
 * @param attributes free values that the realm defines
 * @param externalId the ID of the unit in the system it came from, or null
 * @param source the name of that system, or null
 */
public record OrgUnitRepresentation(String id, String code, String name, Map<String, String> names,
        String shortName, Map<String, String> shortNames, String phoneticName, String unitType, String parentId,
        String validFrom, String validTo, Integer displayOrder, Map<String, Object> attributes, String externalId,
        String source) {

    static OrgUnitRepresentation of(OrgUnitState state) {
        var unit = state.unit();
        var version = state.version();
        var period = version.period();
        return new OrgUnitRepresentation(unit.id(), unit.code(), version.name(), version.names(),
                version.shortName(), version.shortNames(), version.phoneticName(), state.unitType(),
                state.parentId(), period.from().toString(), period.to() == null ? null : period.to().toString(),
                version.displayOrder(), version.attributes(), unit.externalId(), unit.source());
    }

    OrgUnitChange toChange() {
        return new OrgUnitChange(validFrom, name, names, shortName, shortNames, phoneticName, unitType, displayOrder,
                attributes);
    }

    NewOrgUnit toNewOrgUnit() {
        return new NewOrgUnit(code, name, names, shortName, shortNames, phoneticName, unitType, parentId, validFrom,
                displayOrder, attributes, externalId, source);
    }
}
