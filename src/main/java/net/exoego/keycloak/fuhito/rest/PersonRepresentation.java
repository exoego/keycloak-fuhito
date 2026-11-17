package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.service.ExternalIdLists;
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
        @Schema(description = "The version of the person. A new person has 1. Each write counts it up by one, also a "
                + "mask or a change of an ID of another system. A write of a stored person sends the version that "
                + "the caller read. A create needs none.")
        Long version,
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
        @Schema(
                description = "The ID of the person in the system that `source` names. A write stores it as the "
                        + "ID of that system, in place of the one that the system had. A read gives every ID in "
                        + "`externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system that made the person. A create sets it, and it never "
                        + "changes. With `externalId`, it names the system of that ID.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(
                description = "Every ID that a system has for the person. The one of `source` comes first, then "
                        + "the others by system.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    static PersonRepresentation of(PersonView view, ExternalIdLists ids) {
        var p = view.person();
        return new PersonRepresentation(p.id(), p.version(), p.userId(), view.name(),
                view.nameSource() == null ? null : view.nameSource().name(), p.names(), p.phoneticName(),
                p.attributes(), null, p.source(), ExternalIdPairRepresentation.of(ids.of(p.id(), p.source())));
    }

    PersonValues toValues() {
        return new PersonValues(userId, name, names, phoneticName, attributes, externalId, source);
    }
}
