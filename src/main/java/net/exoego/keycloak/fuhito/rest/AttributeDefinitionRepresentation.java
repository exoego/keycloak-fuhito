package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A definition of a free attribute in requests and responses.
 */
@Schema(
        name = "AttributeDefinition",
        description = "The definition of one free attribute of one kind of resource.",
        requiredProperties = {"target", "name", "type"})
public record AttributeDefinitionRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The version of the definition. A new definition has 1. Each write counts it up by "
                + "one, also a change of an ID of another system. A write of a stored definition sends the version "
                + "that the caller read. A create needs none.")
        Long version,
        @Schema(
                description = "The kind of resource. Only a create request reads it.",
                enumeration = {"ORG_UNIT", "PERSON", "EMPLOYMENT_TERMS", "ASSIGNMENT", "QUALIFICATION", "REF_ITEM"})
        String target,
        @Schema(description = "The name in `attributes`. Only a create request reads it.", maxLength = 64)
        String name,
        @Schema(enumeration = {"STRING", "NUMBER", "BOOLEAN", "DATE", "REF_ITEM"})
        String type,
        @Schema(description = "The kind of reference data, fixed or of the realm. Only the type `REF_ITEM` has it.")
        String refKind,
        @Schema(
                description = "True when the value is a JSON array, and each element has the type.",
                defaultValue = "false")
        Boolean multiple,
        @Schema(
                description = "True when every write of the resource must give a value. A list needs at least one "
                        + "element.",
                defaultValue = "false")
        Boolean required,
        @Schema(
                description = "The ID of the definition in the system that `source` names. Only a create reads it. "
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
        @Schema(description = "Every ID that a system has for the definition, by system.", readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    static AttributeDefinitionRepresentation of(AttributeDefinition definition, ExternalIdLists ids) {
        return new AttributeDefinitionRepresentation(definition.id(), definition.version(),
                definition.target().name(), definition.name(), definition.type().name(), definition.refKind(),
                definition.multiple(), definition.required(), null, null,
                ExternalIdPairRepresentation.of(ids.of(definition.id(), null)));
    }
}
