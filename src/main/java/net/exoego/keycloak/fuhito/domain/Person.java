package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * A person that the realm knows. A person does not need a Keycloak user.
 *
 * @param id the ID
 * @param userId the ID of the linked Keycloak user, or null
 * @param name the default name, or null when only Keycloak has it
 * @param names the names in other languages, by language tag
 * @param phoneticName the reading of the name for sorting, or null
 * @param attributes free values that the realm defines
 * @param externalId the ID of the person in the system it came from, or null
 * @param source the name of that system, or null
 */
public record Person(String id, String userId, String name, Map<String, String> names, String phoneticName,
        Map<String, Object> attributes, String externalId, String source) {
}
