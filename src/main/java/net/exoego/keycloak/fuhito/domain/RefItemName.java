package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The name of a reference item in one language.
 *
 * @param name the name
 */
@Schema(name = "RefItemName", description = "The name of a reference item in one language.",
        requiredProperties = {"name"})
public record RefItemName(
        @Schema(maxLength = 255)
        String name) {

    /**
     * Checks the name of a request.
     *
     * @param field the name of the field, for the error
     * @return the name without spaces around it
     * @throws FuhitoException when the name is missing, blank, or too long
     */
    public RefItemName checked(String field) {
        return new RefItemName(Checks.name(field + ".name", name));
    }

    /**
     * Checks the names of a reference item in a request.
     *
     * @param primaryLanguage the tag of the primary language
     * @param names the names by language tag
     * @return the checked names
     * @throws FuhitoException when a name or a tag is missing or bad, or no name has the primary language
     */
    public static Localized<RefItemName> check(String primaryLanguage, Map<String, RefItemName> names) {
        return Localized.check(primaryLanguage, names, (field, name) -> name.checked(field));
    }
}
