package net.exoego.keycloak.fuhito.domain;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The names of an org unit in one language.
 *
 * @param name the name
 * @param shortName the short name, or null
 * @param phoneticName the reading of the name, or null
 */
@Schema(name = "OrgUnitName", description = "The names of an org unit in one language.",
        requiredProperties = {"name"})
public record OrgUnitName(
        @Schema(maxLength = 255)
        String name,
        @Schema(maxLength = 255)
        String shortName,
        @Schema(description = "The reading of the name. The one of the primary language sorts the units.",
                maxLength = 255)
        String phoneticName) {

    /**
     * Checks the names of a request.
     *
     * @param field the name of the field, for the error
     * @return the same names
     * @throws FuhitoException when the name is missing, or a name is blank, too long, or has spaces around it
     */
    public OrgUnitName checked(String field) {
        return new OrgUnitName(Checks.name(field + ".name", name), Checks.optionalName(field + ".shortName",
                shortName), Checks.optionalName(field + ".phoneticName", phoneticName));
    }
}
