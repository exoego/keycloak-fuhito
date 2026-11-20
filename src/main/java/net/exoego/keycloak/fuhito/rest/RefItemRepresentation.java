package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.RefItemService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A reference item in requests and responses.
 */
@Schema(name = "RefItem", requiredProperties = {"primaryLanguage", "names"})
public record RefItemRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The version of the item. A new item has 1. Each write counts it up by one, also a "
                + "change of an ID of another system. A write of a stored item sends the version that the caller "
                + "read. A create needs none.")
        Long version,
        @Schema(description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added. Only a "
                + "create request reads it.")
        String kind,
        @Schema(
                description = "Only a create request reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(
                description = "The name of the date in the language of the request. It is the one of the first "
                        + "language of `Accept-Language`, else the one of that language with no region, such as `en` "
                        + "for `en-US`, else the primary one. A request with no `Accept-Language` reads the primary "
                        + "one.",
                readOnly = true)
        String name,
        @Schema(description = "The language tag of the primary name, such as `ja`. It matches a key of `names` in "
                + "standard form.", maxLength = 64)
        String primaryLanguage,
        @Schema(
                description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. A read gives the names "
                        + "of its date, with the tags in their standard form, such as `en-US` for `en-us`. Two keys "
                        + "of one tag in standard form, such as `ja` and `JA`, fail with `invalid.duplicate_language`.")
        Map<String, RefItemName> names,
        @Schema(description = "The position in lists, lowest first.", defaultValue = "0")
        Integer displayOrder,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(description = "False when the item must not be used for new data.", defaultValue = "true")
        Boolean active,
        @Schema(
                description = "True for a unit type whose org units are legal entities, such as a company. Only "
                        + "the kind `UNIT_TYPE` can have it.",
                defaultValue = "false")
        Boolean legalEntity,
        @Schema(
                description = "True for an assignment kind whose heads stand in for an absent head. Only the kind "
                        + "`ASSIGNMENT_KIND` can have it.",
                defaultValue = "false")
        Boolean actingHead,
        @Schema(
                description = "True for a unit type whose org units are lateral units. A lateral unit cuts across the "
                        + "line organization, such as a committee or a project team. Only the kind `UNIT_TYPE` can have "
                        + "it. It cannot change while an org unit has the type.",
                defaultValue = "false")
        Boolean lateral,
        @Schema(
                description = "True for a unit type whose org units are rollup units. A rollup unit gathers units in "
                        + "another hierarchy, such as a region, and is not in the default hierarchy. Only the kind "
                        + "`UNIT_TYPE` can have it, and not with `lateral` or `legalEntity`. It cannot change while an "
                        + "org unit has the type.",
                defaultValue = "false")
        Boolean rollup,
        @Schema(
                description = "The first date of the names. A create without it gives names that hold for every "
                        + "date. A change without it changes the names of today. A change with it keeps the old "
                        + "names for the dates before. A read leaves it out for names that hold from the start.",
                format = "date")
        String validFrom,
        @Schema(
                description = "The first date after the names. It is missing for no end.",
                format = "date",
                readOnly = true)
        String validTo,
        @Schema(
                description = "The ID of the item in the system that `source` names. Only a create reads it. "
                        + "A read gives every ID in `externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system of `externalId`. Only a create reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String source,
        @Schema(description = "Every ID that a system has for the item, by system.", readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    RefItemService.Marks marks() {
        return new RefItemService.Marks(legalEntity, actingHead, lateral, rollup);
    }

    static RefItemRepresentation of(RefItem item, ExternalIdLists ids, Locale language) {
        return new RefItemRepresentation(item.id(), item.version(), item.kind(), item.code(),
                item.names().in(language).name(), item.names().primaryLanguage(), item.names().byLanguage(),
                item.displayOrder(), item.attributes(), item.active(),
                item.legalEntity() ? Boolean.TRUE : null, item.actingHead() ? Boolean.TRUE : null,
                item.lateral() ? Boolean.TRUE : null, item.rollup() ? Boolean.TRUE : null,
                Objects.toString(item.validFrom(), null),
                Objects.toString(item.validTo(), null), null, null,
                ExternalIdPairRepresentation.of(ids.of(item.id(), null)));
    }
}
