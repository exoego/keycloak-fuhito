package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The version that a write of a stored resource needs, and the whole body of a write that needs nothing more.
 */
@Schema(
        name = "VersionCheck",
        description = "The version of the resource that the caller read. The write goes on only when the resource "
                + "still has it.",
        requiredProperties = {"version"})
public record VersionCheckRepresentation(
        @Schema(description = "The version of the resource that the caller read.")
        Long version) implements Versioned {
}
