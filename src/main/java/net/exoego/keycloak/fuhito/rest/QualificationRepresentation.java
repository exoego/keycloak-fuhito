package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.QualificationValues;
import net.exoego.keycloak.fuhito.service.QualificationView;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A qualification in requests and responses.
 */
@Schema(name = "Qualification", requiredProperties = {"kind", "validFrom"})
public record QualificationRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The version of the qualification. A new qualification has 1. Each write counts it up "
                + "by one, also a change of an ID of another system. A write of a stored qualification sends the "
                + "version that the caller read. A create needs none.")
        Long version,
        @Schema(
                description = "The ID of the person. Only a request to add reads it, and takes this or `userId`. A "
                        + "request to add with both fails with 400 and `invalid.twice`.")
        String personId,
        @Schema(
                description = "The ID of the Keycloak user that the person is linked to. A request to add can give "
                        + "it in place of `personId`, not with it. The person of the user is then used, and created "
                        + "when the user has none. A read of a person with a user has both, so a request to add made "
                        + "from a read leaves one out.")
        String userId,
        @Schema(description = "The code of the qualification kind.")
        String kind,
        @Schema(description = "The number of the license or certificate.", maxLength = 255)
        String number,
        @Schema(description = "The first date on which the qualification is valid.", format = "date")
        String validFrom,
        @Schema(
                description = "The first date on which it is no longer valid. It is missing for no end.",
                format = "date")
        String validTo,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(
                description = "The ID of the qualification in the system that `source` names. A write stores it as the "
                        + "ID of that system, in place of the one that the system had. A read gives every ID in "
                        + "`externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system that made the qualification. A create sets it, and it never "
                        + "changes. With `externalId`, it names the system of that ID.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(
                description = "Every ID that a system has for the qualification. The one of `source` comes first, then "
                        + "the others by system.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    static QualificationRepresentation of(QualificationView view, ExternalIdLists ids) {
        var q = view.qualification();
        return new QualificationRepresentation(q.id(), q.version(), q.personId(), q.userId(), view.kind(), q.number(),
                q.period().from().toString(), Objects.toString(q.period().to(), null),
                q.attributes(), null, q.source(), ExternalIdPairRepresentation.of(ids.of(q.id(), q.source())));
    }

    QualificationValues toValues(String person) {
        return new QualificationValues(person, kind, number, validFrom, validTo, attributes, externalId, source);
    }
}
