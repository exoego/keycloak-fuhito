package net.exoego.keycloak.fuhito.service;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.PersonName;

/**
 * The values of a request to create or replace a person. They are not checked yet.
 *
 * @param userId the ID of the Keycloak user to link, or null for no link
 * @param primaryLanguage the tag of the language of the primary names, or null with no names
 * @param names the names by language tag, or null. A person with no link needs them.
 * @param attributes free values, or null
 * @param externalId the ID in the system that the source names, or null to keep the IDs as they are
 * @param source the name of the system. A new person keeps it as the system that made it.
 */
public record PersonValues(String userId, String primaryLanguage, Map<String, PersonName> names,
        Map<String, Object> attributes, String externalId, String source) {
}
