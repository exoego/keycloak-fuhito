package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.UnitRoleService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A role over an org unit in requests and responses.
 */
@Schema(
        name = "UnitRole",
        description = "A role that an employment holds over an org unit without being a member of it, such as the "
                + "HR contact of a division.",
        requiredProperties = {"employmentId", "orgUnitId", "role", "validFrom"})
public record UnitRoleRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(
                description = "The version of the role. A new role has 1. Each write counts it up by one, also a "
                        + "change of an ID of another system.",
                readOnly = true)
        Long version,
        @Schema(description = "The ID of the employment that holds the role.")
        String employmentId,
        @Schema(description = "The ID of the person of the employment.", readOnly = true)
        String personId,
        @Schema(description = "The ID of the Keycloak user of the person.", readOnly = true)
        String userId,
        @Schema(description = "The ID of the org unit.")
        String orgUnitId,
        @Schema(description = "The code of a reference item of the kind `UNIT_ROLE`.")
        String role,
        @Schema(
                description = "True when the role also covers every unit below in the default hierarchy.",
                defaultValue = "false")
        Boolean includesDescendants,
        @Schema(description = "The first date of the role.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date after the role. Leave it out to last as long as the employment.",
                format = "date")
        String validTo,
        @Schema(
                description = "The ID of the role in the system that `source` names. Only a create reads it. "
                        + "A read gives every ID in `externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system of `externalId`. Only a create reads it. A role keeps no system "
                        + "that made it, so a create with this and no `externalId` fails with 400 and "
                        + "`invalid.value_fixed`.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String source,
        @Schema(description = "Every ID that a system has for the role, by system.", readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) {

    static UnitRoleRepresentation of(UnitRoleService.View view, ExternalIdLists ids) {
        var role = view.role();
        return new UnitRoleRepresentation(role.id(), role.version(), role.employmentId(), role.personId(),
                role.userId(), role.orgUnitId(), view.code(), role.includesDescendants(),
                role.period().from().toString(), Objects.toString(role.period().to(), null), null, null,
                ExternalIdPairRepresentation.of(ids.of(role.id(), null)));
    }
}
