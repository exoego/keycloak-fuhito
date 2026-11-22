package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.NewOrgUnit;
import net.exoego.keycloak.fuhito.service.OrgUnitChange;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "OrgUnit",
        description = "An org unit as it is on one date.",
        requiredProperties = {"primaryLanguage", "names", "validFrom"})
public record OrgUnitRepresentation(
        @Schema(
                description = "The ID of the unit. A write of a stored unit with another ID fails with 400 and "
                        + "`invalid.value_fixed`.",
                readOnly = true)
        String id,
        @Schema(description = "The version of the unit. A new unit has 1. Each write counts it up by one, also a "
                + "change of a period, of a parent, or of an ID of another system. A write of a stored unit sends "
                + "the version that the caller read. A create needs none.")
        Long version,
        @Schema(
                description = "A code that no other org unit has on the same date. It is part of the details, so "
                        + "it can change on a date. A change of details that leaves it out clears it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(
                description = "The name in the language of the request. It is the one whose key is the first tag of "
                        + "`Accept-Language` by `q`, with case ignored. Else it is the one of the language alone, such "
                        + "as `en` for `en-GB`, else the primary one. A tag goes through no mapping, so `iw` does not "
                        + "find `he`. A tag with `q=0` is never used. A request with no `Accept-Language`, or with no "
                        + "tag left, reads the primary one. "
                        + "A write of a stored unit with another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String name,
        @Schema(
                description = "The short name in the language of `name`. A write of a stored unit with another value "
                        + "fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String shortName,
        @Schema(
                description = "The reading of the name in the language of `name`. A write of a stored unit with "
                        + "another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String phoneticName,
        @Schema(
                description = "The language tag of the primary names, such as `ja`. It is the same text as a key of "
                        + "`names`. The Keycloak group of the unit has the primary name.",
                maxLength = 64)
        String primaryLanguage,
        @Schema(
                description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. A write stores each tag "
                        + "as sent, such as `en-us`, and a read gives it so. Two keys that differ only in case, such "
                        + "as `ja` and `JA`, fail with `invalid.duplicate_language`.")
        Map<String, OrgUnitName> names,
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
                        + "else the one of the nearest unit above it in the default hierarchy. A history has none. It "
                        + "can change inside the details, when the unit moves or a unit above changes. A write of a "
                        + "stored unit can give one that applies on any date of the details that it replaces. "
                        + "Another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String effectiveWorkLocation,
        @Schema(
                description = "The code of the cost center that applies on the date. It is the one of the unit, else "
                        + "the one of the nearest unit above it in the default hierarchy. A history has none. It can "
                        + "change inside the details, when the unit moves or a unit above changes. A write of a "
                        + "stored unit can give one that applies on any date of the details that it replaces. "
                        + "Another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String effectiveCostCenter,
        @Schema(
                description = "True when the unit type marks the unit as a legal entity, such as a company. A write of "
                        + "a stored unit with another value fails with 400 and `invalid.value_fixed`. False is the "
                        + "same as no value.",
                readOnly = true)
        Boolean legalEntity,
        @Schema(
                description = "True when the unit type marks the unit as a lateral unit outside the line organization, "
                        + "such as a committee. A write of a stored unit with another value fails with 400 and "
                        + "`invalid.value_fixed`. False is the same as no value.",
                readOnly = true)
        Boolean lateral,
        @Schema(
                description = "True when the unit type marks the unit as a rollup unit, such as a region. A rollup "
                        + "unit gathers units in another hierarchy and is not in the default hierarchy. A write of a "
                        + "stored unit with another value fails with 400 and `invalid.value_fixed`. False is the same "
                        + "as no value.",
                readOnly = true)
        Boolean rollup,
        @Schema(
                description = "The code of the hierarchy of `parentId`. A read gives the code of its `hierarchy` "
                        + "parameter, or `default` without one. A history has none. `PUT /org-units/{id}` checks "
                        + "`parentId` in this hierarchy, or in the default one when the body leaves it out. A code that "
                        + "no hierarchy has fails with 400 and `hierarchy.unknown`, as the parameter of a read does. A "
                        + "create takes only `default`, or none. Another value fails with 400 and "
                        + "`invalid.value_fixed`.")
        String hierarchy,
        @Schema(
                description = "The ID of the parent in `hierarchy`. A root has none, and a rollup unit has none in the "
                        + "default hierarchy. A create takes the parent in the default hierarchy. "
                        + "`PUT /org-units/{id}/parent` moves the unit, and `PUT /org-units/{id}` does not. A "
                        + "`PUT /org-units/{id}` can leave it out, or give a parent that the unit has in `hierarchy` "
                        + "on any date of the details that the write replaces, as a read of such a date in that "
                        + "hierarchy has it. These dates run from `validFrom` to the end of the stored details of that "
                        + "date. Another value fails with 400 and `invalid.value_fixed`. So for a unit with no parent "
                        + "there on those dates, it must be null or left out.")
        String parentId,
        @Schema(description = "The first date of these details.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date after these details. It is missing for no end. A write of a stored unit "
                        + "with another value than the end of the details on its `validFrom` fails with 400 and "
                        + "`invalid.value_fixed`.",
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
                        + "changes. With `externalId`, it names the system of that ID. Without it, a write of a stored "
                        + "unit with another value fails with 400 and `invalid.value_fixed`.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(
                description = "Every ID that a system has for the unit. The one of `source` comes first, then "
                        + "the others by system. A write of a stored unit with another list fails with 400 and "
                        + "`invalid.value_fixed`.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds,
        @Schema(
                description = "The ID of the linked Keycloak organization. `PUT /org-units/{id}/organization` changes "
                        + "it. A write of a stored unit with another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String keycloakOrganizationId,
        @Schema(
                description = "The time zone that the unit sets for itself and the units below it. "
                        + "`PUT /org-units/{id}/time-settings` changes it. A write of a stored unit with another value "
                        + "fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String timeZone,
        @Schema(
                description = "The day start that the unit sets for itself and the units below it. "
                        + "`PUT /org-units/{id}/time-settings` changes it. A write of a stored unit with another value "
                        + "fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String dayStart) implements Versioned {

    // The fields that only a response fills and that a read of validFrom has. The effective places can differ
    // between the dates of the details, so a write compares them with every date, as it does the parent.
    static final List<String> READ_ONLY = List.of("id", "name", "shortName", "phoneticName", "legalEntity",
            "lateral", "rollup", "validTo", "externalIds", "keycloakOrganizationId", "timeZone", "dayStart");

    static OrgUnitRepresentation of(OrgUnitState state, ExternalIdLists ids, String language) {
        var unit = state.unit();
        var version = state.version();
        var period = version.period();
        var places = state.places();
        OrgUnitName read = version.names().in(language);
        return new OrgUnitRepresentation(unit.id(), unit.version(), version.code(), read.name(), read.shortName(),
                read.phoneticName(), version.names().primaryLanguage(), version.names().byLanguage(),
                state.unitType(), places.workLocation(), places.costCenter(), places.effectiveWorkLocation(),
                places.effectiveCostCenter(), state.legalEntity() ? Boolean.TRUE : null,
                state.lateral() ? Boolean.TRUE : null, state.rollup() ? Boolean.TRUE : null, state.hierarchy(),
                state.parentId(), period.from().toString(),
                Objects.toString(period.to(), null), version.displayOrder(), version.attributes(), null,
                unit.source(), ExternalIdPairRepresentation.of(ids.of(unit.id(), unit.source())),
                unit.keycloakOrganizationId(), unit.timeZone(), unit.dayStart());
    }

    OrgUnitChange toChange() {
        return new OrgUnitChange(validFrom, code, primaryLanguage, names, unitType, workLocation, costCenter,
                displayOrder, attributes);
    }

    NewOrgUnit toNewOrgUnit() {
        return new NewOrgUnit(code, primaryLanguage, names, unitType, workLocation, costCenter, parentId,
                validFrom, displayOrder, attributes, externalId, source);
    }
}
