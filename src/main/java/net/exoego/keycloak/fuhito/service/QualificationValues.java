package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * The values of a request to add or replace a qualification. They are not checked yet.
 *
 * @param personId the ID of the person. Only a new qualification uses it.
 * @param kind the code of the qualification kind
 * @param number the number of the license or certificate, or null
 * @param validFrom the first date on which the qualification is valid, as {@code YYYY-MM-DD}
 * @param validTo the first date on which it is no longer valid, or null for no end
 * @param attributes free values, or null
 * @param externalId the ID in the system that the source names, or null to keep the IDs as they are
 * @param source the name of the system. A new qualification keeps it as the system that made it.
 */
public record QualificationValues(String personId, String kind, String number, String validFrom, String validTo,
        Map<String, Object> attributes, String externalId, String source) {
}
