package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import net.exoego.keycloak.fuhito.service.PersonValues;
import net.exoego.keycloak.fuhito.service.PersonView;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A person in requests and responses.
 */
@Schema(name = "Person", description = "A person that the realm knows. A person does not need a Keycloak user.")
public record PersonRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The ID of the linked Keycloak user. One user is linked to at most one person.")
        String userId,
        @Schema(
                description = "The default name. A person with no linked user needs it. When the record has no "
                        + "name, a response has the name of the linked user from Keycloak.",
                maxLength = 255)
        String name,
        @Schema(
                description = "Where the name of a response came from. `RECORD` is the person record. `KEYCLOAK` "
                        + "is the linked user. It is missing when there is no name.",
                enumeration = {"RECORD", "KEYCLOAK"},
                readOnly = true)
        String nameSource,
        @Schema(description = "Names in other languages, by language tag.")
        Map<String, String> names,
        @Schema(description = "The reading of the name for sorting.", maxLength = 255)
        String phoneticName,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(description = "The ID of the person in the system it came from. Only a create request reads it.")
        String externalId,
        @Schema(
                description = "The name of that system. Only a create request reads it.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source) {

    static PersonRepresentation of(PersonView view) {
        var p = view.person();
        return new PersonRepresentation(p.id(), p.userId(), view.name(),
                view.nameSource() == null ? null : view.nameSource().name(), p.names(), p.phoneticName(),
                p.attributes(), p.externalId(), p.source());
    }

    PersonValues toValues() {
        return new PersonValues(userId, name, names, phoneticName, attributes, externalId, source);
    }
}
