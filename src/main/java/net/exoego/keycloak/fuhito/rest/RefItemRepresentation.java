package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

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
        @Schema(description = "Only a create request reads it.", ref = "RefKind")
        String kind,
        @Schema(
                description = "Only a create request reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String code,
        @Schema(description = "The default name.", maxLength = 255)
        String name,
        @Schema(description = "Names in other languages, by language tag.")
        Map<String, String> names,
        @Schema(description = "The position in lists, lowest first.", defaultValue = "0")
        Integer displayOrder,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(description = "False when the item must not be used for new data.")
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
                description = "True for a unit type whose org units are groups outside the line organization, such "
                        + "as a committee or a project team. Only the kind `UNIT_TYPE` can have it. It cannot "
                        + "change while an org unit has the type.",
                defaultValue = "false")
        Boolean group) {

    RefItemService.Marks marks() {
        return new RefItemService.Marks(legalEntity, actingHead, group);
    }

    static RefItemRepresentation of(RefItem item) {
        return new RefItemRepresentation(item.id(), item.kind().name(), item.code(), item.name(), item.names(),
                item.displayOrder(), item.attributes(), item.active(),
                item.legalEntity() ? Boolean.TRUE : null, item.actingHead() ? Boolean.TRUE : null,
                item.group() ? Boolean.TRUE : null);
    }
}
