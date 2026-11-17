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
 * @param source the name of the system that made the person, or null. Its IDs in each system are stored apart.
 */
public record Person(String id, String userId, String name, Map<String, String> names, String phoneticName,
        Map<String, Object> attributes, String source) {
}
