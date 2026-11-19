package net.exoego.keycloak.fuhito.service;

import java.util.Locale;

import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.PersonName;

/**
 * A person with the name to show.
 *
 * @param person the person
 * @param keycloakName the name of the linked Keycloak user when the record has no names, or null
 */
public record PersonView(Person person, String keycloakName) {

    /**
     * Where the name of a person came from.
     */
    public enum NameSource {
        /** The person record has the name. */
        RECORD,
        /** The record has no name, and the linked Keycloak user has one. */
        KEYCLOAK
    }

    /**
     * Returns the names for a reader of a language.
     *
     * @param language the language of the reader, or null for the primary names
     * @return the names of the record, else the name of the Keycloak user, or null when neither has one
     */
    public PersonName names(Locale language) {
        if (person.names() != null) {
            return person.names().in(language);
        }
        return keycloakName == null ? null : new PersonName(keycloakName, null);
    }

    /**
     * Returns where the name came from.
     *
     * @return the source, or null when there is no name
     */
    public NameSource nameSource() {
        if (person.names() != null) {
            return NameSource.RECORD;
        }
        return keycloakName == null ? null : NameSource.KEYCLOAK;
    }
}
