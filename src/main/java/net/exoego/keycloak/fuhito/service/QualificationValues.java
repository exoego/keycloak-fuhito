package net.exoego.keycloak.fuhito.service;

import java.util.Map;

/**
 * The values of a request to add or replace a qualification. They are not checked yet.
 *
 * @param userId the ID of the Keycloak user. Only a new qualification uses it.
 * @param kind the code of the qualification kind
 * @param number the number of the license or certificate, or null
 * @param validFrom the first date on which the qualification is valid, as {@code YYYY-MM-DD}
 * @param validTo the first date on which it is no longer valid, or null for no end
 * @param attributes free values, or null
 * @param externalId the ID in the system it came from, or null. Only a new qualification uses it.
 * @param source the name of that system, or null. Only a new qualification uses it.
 */
public record QualificationValues(String userId, String kind, String number, String validFrom, String validTo,
        Map<String, Object> attributes, String externalId, String source) {
}
