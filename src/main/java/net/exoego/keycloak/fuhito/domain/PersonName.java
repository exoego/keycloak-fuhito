package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The names of a person in one language.
 *
 * @param name the name
 * @param phoneticName the reading of the name, or null
 */
@Schema(name = "PersonName", description = "The names of a person in one language.", requiredProperties = {"name"})
public record PersonName(
        @Schema(maxLength = 255)
        String name,
        @Schema(description = "The reading of the name for sorting.", maxLength = 255)
        String phoneticName) {

    /**
     * Checks the names of a request.
     *
     * @param field the name of the field, for the error
     * @return the same names
     * @throws FuhitoException when the name is missing, or a name is blank, too long, or has spaces around it
     */
    public PersonName checked(String field) {
        return new PersonName(Checks.name(field + ".name", name),
                Checks.optionalName(field + ".phoneticName", phoneticName));
    }

    /**
     * Checks the names of a person in a request. A person with a Keycloak user may have none.
     *
     * @param primaryLanguage the tag of the primary language, or null
     * @param names the names by language tag, or null
     * @return the checked names, or null when the request has no primary language and no names
     * @throws FuhitoException when a name or a tag is bad, or no names have the primary language
     */
    public static Localized<PersonName> check(String primaryLanguage, Map<String, PersonName> names) {
        if (primaryLanguage == null && (names == null || names.isEmpty())) {
            return null;
        }
        return Localized.check(primaryLanguage, names, (field, name) -> name.checked(field));
    }
}
