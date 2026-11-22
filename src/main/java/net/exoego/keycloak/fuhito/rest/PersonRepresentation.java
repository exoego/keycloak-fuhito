package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.PersonName;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.PersonValues;
import net.exoego.keycloak.fuhito.service.PersonView;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A person in requests and responses.
 */
@Schema(name = "Person", description = "A person that the realm knows. A person does not need a Keycloak user.")
public record PersonRepresentation(
        @Schema(
                description = "The ID of the person. A write of a stored person with another ID fails with 400 and "
                        + "`invalid.value_fixed`.",
                readOnly = true)
        String id,
        @Schema(description = "The version of the person. A new person has 1. Each write counts it up by one, also a "
                + "mask or a change of an ID of another system. A change of the name of the linked user in Keycloak, "
                + "its removal, or a change of the realm attribute `fuhito.nameOrder` counts it up too, when the "
                + "record has no names and the read changes. A write of a stored person sends the version that the "
                + "caller read. A create needs none.")
        Long version,
        @Schema(description = "The ID of the linked Keycloak user. One user is linked to at most one person.")
        String userId,
        @Schema(
                description = "The name in the language of the request. It is the one whose key is the first tag of "
                        + "`Accept-Language` by `q`, with case ignored. Else it is the one of the language alone, such "
                        + "as `en` for `en-GB`, else the primary one. A tag goes through no mapping, so `iw` does not "
                        + "find `he`. A request with no `Accept-Language` reads the primary one. "
                        + "When the record has no names, it is the name of the linked user from Keycloak. A write of a "
                        + "stored person with another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String name,
        @Schema(
                description = "Where the name of a response came from. `RECORD` is the person record. `KEYCLOAK` "
                        + "is the linked user. It is missing when there is no name. A write of a stored person with "
                        + "another value fails with 400 and `invalid.value_fixed`.",
                enumeration = {"RECORD", "KEYCLOAK"},
                readOnly = true)
        String nameSource,
        @Schema(
                description = "The reading of the name in the language of `name`. A write of a stored person with "
                        + "another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String phoneticName,
        @Schema(
                description = "The language tag of the primary names, such as `ja`. It is the same text as a key of "
                        + "`names`. A person with no names has none.",
                maxLength = 64)
        String primaryLanguage,
        @Schema(
                description = "The names by language tag, such as `ja`, `en`, or `zh-Hant`. A write stores each tag "
                        + "as sent, such as `en-us`, and a read gives it so. A person with no linked user needs "
                        + "them. A person with a linked user can have none, and a response then has the name of "
                        + "the user in `name`. Two keys that differ only in case, such as `ja` and `JA`, fail "
                        + "with `invalid.duplicate_language`.")
        Map<String, PersonName> names,
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
                        + "changes. With `externalId`, it names the system of that ID. Without it, a write of a stored "
                        + "person with another value fails with 400 and `invalid.value_fixed`.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(
                description = "Every ID that a system has for the person. The one of `source` comes first, then "
                        + "the others by system. A write of a stored person with another list fails with 400 and "
                        + "`invalid.value_fixed`.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    // The fields that only a response fills.
    static final List<String> READ_ONLY = List.of("id", "name", "nameSource", "phoneticName", "externalIds");

    static PersonRepresentation of(PersonView view, ExternalIdLists ids, String language) {
        var p = view.person();
        PersonName read = view.names(language);
        return new PersonRepresentation(p.id(), p.version(), p.userId(), read == null ? null : read.name(),
                view.nameSource() == null ? null : view.nameSource().name(),
                read == null ? null : read.phoneticName(),
                p.names() == null ? null : p.names().primaryLanguage(),
                p.names() == null ? Map.of() : p.names().byLanguage(), p.attributes(), null, p.source(),
                ExternalIdPairRepresentation.of(ids.of(p.id(), p.source())));
    }

    PersonValues toValues() {
        return new PersonValues(userId, primaryLanguage, names, attributes, externalId, source);
    }
}
