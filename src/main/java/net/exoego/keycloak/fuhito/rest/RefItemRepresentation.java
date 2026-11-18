package net.exoego.keycloak.fuhito.rest;

import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.service.RefItemService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A reference item in requests and responses.
 */
@Schema(name = "RefItem", requiredProperties = {"name"})
public record RefItemRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The version of the item. A new item has 1. Each write counts it up by one. A write "
                + "of a stored item sends the version that the caller read. A create needs none.")
        Long version,
        @Schema(description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added. Only a "
                + "create request reads it.")
        String kind,
        @Schema(
                description = "Only a create request reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(description = "The default name. A read gives the name of its date.", maxLength = 255)
        String name,
        @Schema(description = "Names in other languages, by language tag. A read gives the names of its date.")
        Map<String, String> names,
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
                description = "The first date of the names. A create without it gives names that hold for every "
                        + "date. A change without it changes the names of today. A change with it keeps the old "
                        + "names for the dates before. A read leaves it out for names that hold from the start.",
                format = "date")
        String validFrom,
        @Schema(
                description = "The first date after the names. It is missing for no end.",
                format = "date",
                readOnly = true)
        String validTo) implements Versioned {

    RefItemService.Marks marks() {
        return new RefItemService.Marks(legalEntity, actingHead, lateral);
    }

    static RefItemRepresentation of(RefItem item) {
        return new RefItemRepresentation(item.id(), item.version(), item.kind(), item.code(), item.name(),
                item.names(), item.displayOrder(), item.attributes(), item.active(),
                item.legalEntity() ? Boolean.TRUE : null, item.actingHead() ? Boolean.TRUE : null,
                item.lateral() ? Boolean.TRUE : null, Objects.toString(item.validFrom(), null),
                Objects.toString(item.validTo(), null));
    }
}
