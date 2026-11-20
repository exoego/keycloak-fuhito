package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.OrgUnitHistory;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "OrgUnitHistory",
        description = "Everything that is stored about an org unit over time.",
        requiredProperties = {"id", "versions", "parents"})
public record OrgUnitHistoryRepresentation(
        String id,
        @Schema(
                description = "The version of the unit. A new unit has 1. Each write counts it up by one, also a "
                        + "change of a period, of a parent, or of an ID of another system.",
                readOnly = true)
        Long version,
        @Schema(description = "The name of the system that made the unit.")
        String source,
        @Schema(description = "Every ID that a system has for the unit. The one of `source` comes first, then the "
                + "others by system.")
        List<ExternalIdPairRepresentation> externalIds,
        @Schema(description = "The details of each period, oldest first. They have no parent.")
        List<OrgUnitRepresentation> versions,
        @Schema(description = "The parents in every hierarchy, oldest first. A gap in a hierarchy means that the unit had no "
                + "parent there.")
        List<ParentPeriod> parents) {

    @Schema(
            name = "ParentPeriod",
            description = "The parent of an org unit for a period.",
            requiredProperties = {"hierarchy", "parentId", "validFrom"})
    public record ParentPeriod(
            @Schema(description = "The code of the hierarchy that the parent belongs to.")
            String hierarchy,
            String parentId,
            @Schema(format = "date")
            String validFrom,
            @Schema(description = "The first date after the period. It is missing for no end.", format = "date")
            String validTo) {
    }

    static OrgUnitHistoryRepresentation of(OrgUnitHistory history, ExternalIdLists ids, Locale language) {
        var unit = history.unit();
        return new OrgUnitHistoryRepresentation(unit.id(), unit.version(), unit.source(),
                ExternalIdPairRepresentation.of(ids.of(unit.id(), unit.source())),
                history.versions().stream().map(version -> OrgUnitRepresentation.of(version, ids, language))
                        .toList(),
                history.parents().stream()
                        .map(link -> new ParentPeriod(history.hierarchyCodes().get(link.hierarchyId()),
                                link.parentId(), link.period().from().toString(),
                                Objects.toString(link.period().to(), null)))
                        .toList());
    }
}
