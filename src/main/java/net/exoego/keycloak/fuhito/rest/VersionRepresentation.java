package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The version of this extension.
 */
@Schema(name = "Version", requiredProperties = {"version"})
public record VersionRepresentation(
        String version) {
}
