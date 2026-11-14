package net.exoego.keycloak.fuhito.rest;

import java.util.Map;
import java.util.Objects;

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
        @Schema(description = "The ID of the Keycloak user. Only a request to add reads it.")
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
        @Schema(description = "The ID of the qualification in the system it came from.")
        String externalId,
        @Schema(description = "The name of that system.")
        String source) {

    static QualificationRepresentation of(QualificationView view) {
        var q = view.qualification();
        return new QualificationRepresentation(q.id(), q.userId(), view.kind(), q.number(),
                q.period().from().toString(), Objects.toString(q.period().to(), null),
                q.attributes(), q.externalId(), q.source());
    }

    QualificationValues toValues() {
        return new QualificationValues(userId, kind, number, validFrom, validTo, attributes, externalId, source);
    }
}
