package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.ExternalIdLists;
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
                description = "The version of the unit. A new unit has 1. Each write counts it up by one, also a "
                        + "change of a period, of a parent, or of an ID of another system.",
                readOnly = true)
        Long version,
        @Schema(
                description = "A code that no other org unit has on the same date. It is part of the details, so "
                        + "it can change on a date. A change of details that leaves it out clears it.",
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
        @Schema(
                description = "The code of the work location that the unit gives its members. Leave it out to take "
                        + "the one of the nearest unit above.")
        String workLocation,
        @Schema(
                description = "The code of the cost center that the unit gives its members. Leave it out to take the "
                        + "one of the nearest unit above.")
        String costCenter,
        @Schema(
                description = "The code of the work location that applies on the date. It is the one of the unit, "
                        + "else the one of the nearest unit above it in the default hierarchy. A history has none.",
                readOnly = true)
        String effectiveWorkLocation,
        @Schema(
                description = "The code of the cost center that applies on the date. It is the one of the unit, else "
                        + "the one of the nearest unit above it in the default hierarchy. A history has none.",
                readOnly = true)
        String effectiveCostCenter,
        @Schema(
                description = "True when the unit type marks the unit as a legal entity, such as a company.",
                readOnly = true)
        Boolean legalEntity,
        @Schema(
                description = "True when the unit type marks the unit as a lateral unit outside the line organization, "
                        + "such as a committee.",
                readOnly = true)
        Boolean lateral,
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
        @Schema(
                description = "The ID of the unit in the system that `source` names. A write stores it as the "
                        + "ID of that system, in place of the one that the system had. A read gives every ID in "
                        + "`externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system that made the unit. A create sets it, and it never "
                        + "changes. With `externalId`, it names the system of that ID.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(
                description = "Every ID that a system has for the unit. The one of `source` comes first, then "
                        + "the others by system.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds,
        @Schema(description = "The ID of the linked Keycloak organization.", readOnly = true)
        String keycloakOrganizationId,
        @Schema(description = "The time zone that the unit sets for itself and the units below it.", readOnly = true)
        String timeZone,
        @Schema(description = "The day start that the unit sets for itself and the units below it.", readOnly = true)
        String dayStart) {

    static OrgUnitRepresentation of(OrgUnitState state, ExternalIdLists ids) {
        var unit = state.unit();
        var version = state.version();
        var period = version.period();
        var places = state.places();
        return new OrgUnitRepresentation(unit.id(), unit.version(), version.code(), version.name(), version.names(),
                version.shortName(), version.shortNames(), version.phoneticName(), state.unitType(),
                places.workLocation(), places.costCenter(), places.effectiveWorkLocation(),
                places.effectiveCostCenter(), state.legalEntity() ? Boolean.TRUE : null,
                state.lateral() ? Boolean.TRUE : null, state.parentId(), period.from().toString(),
                Objects.toString(period.to(), null), version.displayOrder(), version.attributes(), null,
                unit.source(), ExternalIdPairRepresentation.of(ids.of(unit.id(), unit.source())),
                unit.keycloakOrganizationId(), unit.timeZone(), unit.dayStart());
    }

    OrgUnitChange toChange() {
        return new OrgUnitChange(validFrom, code, name, names, shortName, shortNames, phoneticName, unitType,
                workLocation, costCenter, displayOrder, attributes);
    }

    NewOrgUnit toNewOrgUnit() {
        return new NewOrgUnit(code, name, names, shortName, shortNames, phoneticName, unitType, workLocation,
                costCenter, parentId, validFrom, displayOrder, attributes, externalId, source);
    }
}
