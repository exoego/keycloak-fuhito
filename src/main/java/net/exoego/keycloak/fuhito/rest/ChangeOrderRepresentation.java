package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Objects;

import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.service.ChangeOrderValues;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A change order in requests and responses.
 */
@Schema(name = "ChangeOrder", requiredProperties = {"title", "effectiveDate"})
public record ChangeOrderRepresentation(
        @Schema(
                description = "The ID of the order. A write of a stored order with another ID fails with 400 and "
                        + "`invalid.value_fixed`.",
                readOnly = true)
        String id,
        @Schema(description = "The version of the order. A new order has 1. Each write counts it up by one, also a "
                + "change of an ID of another system. A write of a stored order sends the version that the caller "
                + "read. A create needs none.")
        Long version,
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
        @Schema(description = "A reference to the order in another system.", maxLength = 255)
        String externalReference,
        @Schema(
                description = "When the order was stored, in UTC. A write of a stored order with another value fails "
                        + "with 400 and `invalid.value_fixed`.",
                format = "date-time",
                readOnly = true)
        String createdAt,
        @Schema(
                description = "When the order was announced, in UTC. It is absent until someone records the "
                        + "announcement. The extension hides nothing with it. An application can hide the planned "
                        + "changes of an order that has none. `POST /change-orders/{id}/announce` sets it. A write of "
                        + "a stored order with another value fails with 400 and `invalid.value_fixed`.",
                format = "date-time",
                readOnly = true)
        String announcedAt,
        @Schema(
                description = "The ID of the Keycloak user who recorded the announcement. A write of a stored order "
                        + "with another value fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        String announcedByUserId,
        @Schema(
                description = "The ID of the order in the system that `source` names. A write stores it as the ID of "
                        + "that system, in place of the one that the system had. A read gives every ID in "
                        + "`externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system of `externalId`. An order keeps no system that made it, so a "
                        + "write of a stored order with this and no `externalId` fails with 400 and "
                        + "`invalid.value_fixed`.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$",
                writeOnly = true)
        String source,
        @Schema(
                description = "Every ID that a system has for the order, by system. A write of a stored order with "
                        + "another list fails with 400 and `invalid.value_fixed`.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds) implements Versioned {

    // The fields that only a response fills.
    static final List<String> READ_ONLY = List.of("id", "createdAt", "announcedAt", "announcedByUserId",
            "externalIds");

    static ChangeOrderRepresentation of(ChangeOrder order, ExternalIdLists ids) {
        return new ChangeOrderRepresentation(order.id(), order.version(), order.number(), order.kind(), order.title(),
                order.note(), Objects.toString(order.orderDate(), null), order.effectiveDate().toString(),
                order.approverUserId(), order.requesterUserId(), order.externalReference(),
                order.createdAt().toString(), Objects.toString(order.announcedAt(), null),
                order.announcedByUserId(), null, null, ExternalIdPairRepresentation.of(ids.of(order.id(), null)));
    }

    ChangeOrderValues toValues() {
        return new ChangeOrderValues(number, kind, title, note, orderDate, effectiveDate, approverUserId,
                requesterUserId, externalReference);
    }
}
