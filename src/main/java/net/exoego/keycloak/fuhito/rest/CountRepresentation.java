package net.exoego.keycloak.fuhito.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The number of entries of a list on all pages.
 */
@Schema(
        name = "Count",
        description = "The number of entries that a list has on all its pages with the same filters.",
        requiredProperties = {"count"})
public record CountRepresentation(
        @Schema(description = "The number of entries.", minimum = "0")
        long count) {
}
