package net.exoego.keycloak.fuhito.service;

import net.exoego.keycloak.fuhito.domain.Person;

/**
 * A person with the name to show.
 *
 * @param person the person
 * @param name the name of the record, or the name of the linked Keycloak user when the record has none, or
 *        null when neither has one
 * @param nameSource where the name came from, or null when there is none
 */
public record PersonView(Person person, String name, NameSource nameSource) {

    /**
     * Where the name of a person came from.
     */
    public enum NameSource {
        /** The person record has the name. */
        RECORD,
        /** The record has no name, and the linked Keycloak user has one. */
        KEYCLOAK
    }
}
