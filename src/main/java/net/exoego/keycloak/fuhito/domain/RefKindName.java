package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The name of a kind of reference data in one language.
 *
 * @param name the name
 */
@Schema(name = "RefKindName", description = "The name of a kind of reference data in one language.",
        requiredProperties = {"name"})
public record RefKindName(
        @Schema(maxLength = 255)
        String name) {

    /**
     * Checks the name of a request.
     *
     * @param field the name of the field, for the error
     * @return the same name
     * @throws FuhitoException when the name is missing, blank, too long, or has spaces around it
     */
    public RefKindName checked(String field) {
        return new RefKindName(Checks.name(field + ".name", name));
    }

    /**
     * Checks the names of a kind of reference data in a request.
     *
     * @param primaryLanguage the tag of the primary language
     * @param names the names by language tag
     * @return the checked names
     * @throws FuhitoException when a name or a tag is missing or bad, or no name has the primary language
     */
    public static Localized<RefKindName> check(String primaryLanguage, Map<String, RefKindName> names) {
        return Localized.check(primaryLanguage, names, (field, name) -> name.checked(field));
    }
}
