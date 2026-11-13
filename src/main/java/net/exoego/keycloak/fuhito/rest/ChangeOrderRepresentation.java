package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.service.ChangeOrderValues;

/**
 * A change order in requests and responses.
 *
 * @param id the ID. Requests ignore it.
 * @param number the number that the legal entity gives to the order, or null
 * @param kind a code for the kind of order that the realm chooses, or null
 * @param title the title
 * @param note a longer text, or null
 * @param orderDate the date when the order was issued, as {@code YYYY-MM-DD}, or null
 * @param effectiveDate the date from which the changes are true, as {@code YYYY-MM-DD}
 * @param approverUserId the ID of the Keycloak user who approved the order, or null
 * @param requesterUserId the ID of the Keycloak user who asked for the order, or null
 * @param externalReference a reference to the order in another system, or null
 * @param createdAt when the order was stored, in UTC. Requests ignore it.
 */
public record ChangeOrderRepresentation(String id, String number, String kind, String title, String note,
        String orderDate, String effectiveDate, String approverUserId, String requesterUserId,
        String externalReference, String createdAt) {

    static ChangeOrderRepresentation of(ChangeOrder order) {
        return new ChangeOrderRepresentation(order.id(), order.number(), order.kind(), order.title(), order.note(),
                order.orderDate() == null ? null : order.orderDate().toString(), order.effectiveDate().toString(),
                order.approverUserId(), order.requesterUserId(), order.externalReference(),
                order.createdAt().toString());
    }

    ChangeOrderValues toValues() {
        return new ChangeOrderValues(number, kind, title, note, orderDate, effectiveDate, approverUserId,
                requesterUserId, externalReference);
    }
}
