package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * The values of a request to create or replace a person. They are not checked yet.
 *
 * @param userId the ID of the Keycloak user to link, or null for no link
 * @param name the default name, or null. A person with no link needs it.
 * @param names the names in other languages, or null
 * @param phoneticName the reading of the name for sorting, or null
 * @param attributes free values, or null
 * @param externalId the ID in the system it came from, or null. Only a new person uses it.
 * @param source the name of that system, or null. Only a new person uses it.
 */
public record PersonValues(String userId, String name, Map<String, String> names, String phoneticName,
        Map<String, Object> attributes, String externalId, String source) {
}
