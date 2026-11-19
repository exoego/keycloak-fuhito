package net.exoego.keycloak.fuhito.domain;

import java.util.Map;

/**
 * A person that the realm knows. A person does not need a Keycloak user.
 *
 * @param id the ID
 * @param userId the ID of the linked Keycloak user, or null
 * @param names the names in each language, or null when only Keycloak has the name
 * @param attributes free values that the realm defines
 * @param source the name of the system that made the person, or null. Its IDs in each system are stored apart.
 * @param version the number that each write of the resource counts up. A new resource has 1.
 */
public record Person(String id, String userId, Localized<PersonName> names, Map<String, Object> attributes,
        String source, long version) {
}
