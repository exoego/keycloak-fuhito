package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Export",
        description = "All organization data of one realm, with every period of every resource.",
        requiredProperties = {"exportedAt", "refItems", "orgUnits", "employments", "assignments", "changeOrders"})
public record ExportRepresentation(
        @Schema(description = "When the export was made, in UTC.", format = "date-time")
        String exportedAt,
        @Schema(description = "The reference items of every kind, inactive ones included.")
        List<RefItemRepresentation> refItems,
        @Schema(description = "The org units with every period of their details and their parents.")
        List<OrgUnitHistoryRepresentation> orgUnits,
        @Schema(description = "The employments with every period of their terms.")
        List<EmploymentRepresentation> employments,
        @Schema(description = "The assignments with every period of their details.")
        List<AssignmentRepresentation> assignments,
        List<ChangeOrderRepresentation> changeOrders) {
}
