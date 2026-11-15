package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import net.exoego.keycloak.fuhito.service.OrgUnitSuccessions;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The org units that an org unit follows, and the ones that follow it.
 */
@Schema(
        name = "OrgUnitSuccessions",
        description = "The org units that an org unit follows, and the ones that follow it.",
        requiredProperties = {"predecessors", "successors"})
public record OrgUnitSuccessionsRepresentation(
        @Schema(description = "The units that this unit follows. Several of them mean a merger.")
        List<Link> predecessors,
        @Schema(description = "The units that follow this unit. Several of them mean a split.")
        List<Link> successors) {

    @Schema(name = "OrgUnitSuccessionLink", requiredProperties = {"orgUnitId", "effectiveDate"})
    public record Link(
            @Schema(description = "The ID of the other org unit.")
            String orgUnitId,
            @Schema(description = "The first date on which the old unit no longer exists.", format = "date")
            String effectiveDate) {
    }

    static OrgUnitSuccessionsRepresentation of(OrgUnitSuccessions successions) {
        return new OrgUnitSuccessionsRepresentation(
                successions.predecessors().stream()
                        .map(link -> new Link(link.predecessorId(), link.effectiveDate().toString()))
                        .toList(),
                successions.successors().stream()
                        .map(link -> new Link(link.successorId(), link.effectiveDate().toString()))
                        .toList());
    }
}
