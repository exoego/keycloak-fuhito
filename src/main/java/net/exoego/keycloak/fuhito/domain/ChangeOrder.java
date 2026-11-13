package net.exoego.keycloak.fuhito.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An approved instruction that causes changes, such as a personnel order or a reorganization.
 * It is a record of who approved what. It is not an approval workflow.
 *
 * @param id the ID
 * @param number the number that the legal entity gives to the order, or null. It is unique in the realm.
 * @param kind a code for the kind of order that the realm chooses, or null
 * @param title the title
 * @param note a longer text, or null
 * @param orderDate the date when the order was issued, or null
 * @param effectiveDate the date from which the changes are true
 * @param approverUserId the ID of the Keycloak user who approved the order, or null
 * @param requesterUserId the ID of the Keycloak user who asked for the order, or null
 * @param externalReference a reference to the order in another system, or null
 * @param createdAt when the order was stored
 */
public record ChangeOrder(String id, String number, String kind, String title, String note, LocalDate orderDate,
        LocalDate effectiveDate, String approverUserId, String requesterUserId, String externalReference,
        Instant createdAt) {
}
