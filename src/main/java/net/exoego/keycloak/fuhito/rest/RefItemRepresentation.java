package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import net.exoego.keycloak.fuhito.domain.RefItem;

/**
 * A reference item in requests and responses.
 *
 * @param id the ID. Requests ignore it.
 * @param kind the kind. Only a create request reads it.
 * @param code the code. Only a create request reads it.
 * @param name the default name
 * @param names the names in other languages, by language tag
 * @param displayOrder the position in lists, lowest first
 * @param attributes free values that the realm defines
 * @param active false when the item must not be used for new data
 */
public record RefItemRepresentation(String id, String kind, String code, String name, Map<String, String> names,
        Integer displayOrder, Map<String, Object> attributes, Boolean active) {

    static RefItemRepresentation of(RefItem item) {
        return new RefItemRepresentation(item.id(), item.kind().name(), item.code(), item.name(), item.names(),
                item.displayOrder(), item.attributes(), item.active());
    }
}
