package net.exoego.keycloak.fuhito.service;

/**
 * The values of a request to create or replace a change order. They are not checked yet.
 *
 * @param number the number that the legal entity gives to the order, or null
 * @param kind a code for the kind of order, or null
 * @param title the title
 * @param note a longer text, or null
 * @param orderDate the date when the order was issued, as {@code YYYY-MM-DD}, or null
 * @param effectiveDate the date from which the changes are true, as {@code YYYY-MM-DD}
 * @param approverUserId the ID of the Keycloak user who approved the order, or null
 * @param requesterUserId the ID of the Keycloak user who asked for the order, or null
 * @param externalReference a reference to the order in another system, or null
 */
public record ChangeOrderValues(String number, String kind, String title, String note, String orderDate,
        String effectiveDate, String approverUserId, String requesterUserId, String externalReference) {
}
