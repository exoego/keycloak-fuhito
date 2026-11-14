package net.exoego.keycloak.fuhito.rest;

import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.service.ChangeOrderValues;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A change order in requests and responses.
 */
@Schema(name = "ChangeOrder", requiredProperties = {"title", "effectiveDate"})
public record ChangeOrderRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(
                description = "The number that the legal entity gives to the order.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String number,
        @Schema(
                description = "A code for the kind of order that the realm chooses.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String kind,
        @Schema(maxLength = 255)
        String title,
        @Schema(description = "A longer text.")
        String note,
        @Schema(description = "The date when the order was issued.", format = "date")
        String orderDate,
        @Schema(description = "The date from which the changes are true.", format = "date")
        String effectiveDate,
        @Schema(description = "The ID of the Keycloak user who approved the order.")
        String approverUserId,
        @Schema(description = "The ID of the Keycloak user who asked for the order.")
        String requesterUserId,
        @Schema(description = "A reference to the order in another system.")
        String externalReference,
        @Schema(description = "When the order was stored, in UTC.", format = "date-time", readOnly = true)
        String createdAt) {

    static ChangeOrderRepresentation of(ChangeOrder order) {
        return new ChangeOrderRepresentation(order.id(), order.number(), order.kind(), order.title(), order.note(),
                Objects.toString(order.orderDate(), null), order.effectiveDate().toString(),
                order.approverUserId(), order.requesterUserId(), order.externalReference(),
                order.createdAt().toString());
    }

    ChangeOrderValues toValues() {
        return new ChangeOrderValues(number, kind, title, note, orderDate, effectiveDate, approverUserId,
                requesterUserId, externalReference);
    }
}
