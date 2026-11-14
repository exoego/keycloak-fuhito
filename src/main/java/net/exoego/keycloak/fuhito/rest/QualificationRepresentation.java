package net.exoego.keycloak.fuhito.rest;

import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.QualificationValues;
import net.exoego.keycloak.fuhito.service.QualificationView;

/**
 * A qualification in requests and responses.
 *
 * @param id the ID. Requests ignore it.
 * @param userId the ID of the Keycloak user. Only a request to add reads it.
 * @param kind the code of the qualification kind
 * @param number the number of the license or certificate, or null
 * @param validFrom the first date on which the qualification is valid, as {@code YYYY-MM-DD}
 * @param validTo the first date on which it is no longer valid, or null for no end
 * @param attributes free values that the realm defines
 * @param externalId the ID of the qualification in the system it came from, or null
 * @param source the name of that system, or null
 */
public record QualificationRepresentation(String id, String userId, String kind, String number, String validFrom,
        String validTo, Map<String, Object> attributes, String externalId, String source) {

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
