package net.exoego.keycloak.fuhito.rest;

import java.util.Objects;

import net.exoego.keycloak.fuhito.service.UnitRoleService;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A role over an org unit on a date, with the names that show it.
 */
@Schema(
        name = "RoleHolder",
        description = "A role that an employment holds over an org unit on one date, with the names to show it.",
        requiredProperties = {"id", "employmentId", "personId", "orgUnitId", "orgUnitName", "role", "roleName",
            "includesDescendants", "validFrom"})
public record RoleHolderRepresentation(
        @Schema(description = "The ID of the role.")
        String id,
        @Schema(
                description = "The version of the role. A new role has 1. Each write counts it up by one.",
                readOnly = true)
        Long version,
        @Schema(description = "The ID of the employment that holds the role.")
        String employmentId,
        @Schema(description = "The ID of the person of the employment.")
        String personId,
        @Schema(description = "The ID of the Keycloak user of the person.")
        String userId,
        @Schema(description = "The ID of the org unit.")
        String orgUnitId,
        @Schema(description = "The name of the org unit on the date, in the language of the request.")
        String orgUnitName,
        @Schema(description = "The code of a reference item of the kind `UNIT_ROLE`.")
        String role,
        @Schema(description = "The name of the reference item of the role on the date, in the language of the "
                + "request.")
        String roleName,
        @Schema(description = "True when the role also covers every unit below in the default hierarchy.")
        boolean includesDescendants,
        @Schema(description = "The first date of the role.", format = "date")
        String validFrom,
        @Schema(description = "The first date after the role. It is missing for no end.", format = "date")
        String validTo) {

    static RoleHolderRepresentation of(UnitRoleService.Holder holder, String language) {
        var role = holder.view().role();
        String orgUnitName = holder.orgUnitNames() == null ? null : holder.orgUnitNames().in(language).name();
        String roleName = holder.roleNames() == null ? null : holder.roleNames().in(language).name();
        return new RoleHolderRepresentation(role.id(), role.version(), role.employmentId(), role.personId(),
                role.userId(), role.orgUnitId(), orgUnitName, holder.view().code(), roleName,
                role.includesDescendants(), role.period().from().toString(),
                Objects.toString(role.period().to(), null));
    }
}
