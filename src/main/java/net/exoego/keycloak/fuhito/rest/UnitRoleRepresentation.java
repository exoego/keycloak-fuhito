package net.exoego.keycloak.fuhito.rest;

import java.util.Objects;

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
        String validTo) {

    static UnitRoleRepresentation of(UnitRoleService.View view) {
        var role = view.role();
        return new UnitRoleRepresentation(role.id(), role.employmentId(), role.personId(), role.userId(),
                role.orgUnitId(), view.code(), role.includesDescendants(), role.period().from().toString(),
                Objects.toString(role.period().to(), null));
    }
}
