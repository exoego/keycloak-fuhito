package net.exoego.keycloak.fuhito.rest;

import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.NewOrgUnit;
import net.exoego.keycloak.fuhito.service.OrgUnitChange;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "OrgUnit",
        description = "An org unit as it is on one date.",
        requiredProperties = {"name", "validFrom"})
public record OrgUnitRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(
                description = "A code that is unique in the realm.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(description = "The default name.", maxLength = 255)
        String name,
        @Schema(description = "Names in other languages, by language tag.")
        Map<String, String> names,
        @Schema(description = "The default short name.", maxLength = 255)
        String shortName,
        @Schema(description = "The short names in other languages, by language tag.")
        Map<String, String> shortNames,
        @Schema(description = "The reading of the name for sorting.", maxLength = 255)
        String phoneticName,
        @Schema(description = "The code of the unit type.")
        String unitType,
        @Schema(description = "The ID of the parent in the default hierarchy. A root has none.")
        String parentId,
        @Schema(description = "The first date of these details.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date after these details. It is missing for no end.",
                format = "date",
                readOnly = true)
        String validTo,
        @Schema(description = "The position among siblings, lowest first.", defaultValue = "0")
        Integer displayOrder,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(description = "The ID of the unit in the system it came from.")
        String externalId,
        @Schema(description = "The name of that system.")
        String source,
        @Schema(description = "The ID of the linked Keycloak organization.", readOnly = true)
        String keycloakOrganizationId,
        @Schema(description = "The time zone that the unit sets for itself and the units below it.", readOnly = true)
        String timeZone,
        @Schema(description = "The day start that the unit sets for itself and the units below it.", readOnly = true)
        String dayStart) {

    static OrgUnitRepresentation of(OrgUnitState state) {
        var unit = state.unit();
        var version = state.version();
        var period = version.period();
        return new OrgUnitRepresentation(unit.id(), unit.code(), version.name(), version.names(),
                version.shortName(), version.shortNames(), version.phoneticName(), state.unitType(),
                state.parentId(), period.from().toString(), Objects.toString(period.to(), null),
                version.displayOrder(), version.attributes(), unit.externalId(), unit.source(),
                unit.keycloakOrganizationId(), unit.timeZone(), unit.dayStart());
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
