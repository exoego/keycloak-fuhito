package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.EffectiveChange;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "EffectiveChange",
        description = "The fact that something about a resource starts, changes, or ends on a date.",
        requiredProperties = {"effectiveDate", "resourceType", "resourceId", "resourcePath"})
public record EffectiveChangeRepresentation(
        @Schema(description = "The date that a period of the resource starts or ends on.", format = "date")
        String effectiveDate,
        @Schema(enumeration = {"FUHITO_ORG_UNIT", "FUHITO_EMPLOYMENT", "FUHITO_ASSIGNMENT", "FUHITO_REPORTING_LINE",
                "FUHITO_QUALIFICATION", "FUHITO_UNIT_ROLE"})
        String resourceType,
        String resourceId,
        @Schema(description = "The path of the resource below the realm.", examples = {"fuhito/org-units/123"})
        String resourcePath) {

    static EffectiveChangeRepresentation of(EffectiveChange change) {
        String type = switch (change.kind()) {
            case ORG_UNIT -> OrgUnitsResource.RESOURCE_TYPE;
            case EMPLOYMENT -> EmploymentsResource.RESOURCE_TYPE;
            case ASSIGNMENT -> AssignmentsResource.RESOURCE_TYPE;
            case REPORTING_LINE -> ReportingLinesResource.RESOURCE_TYPE;
            case QUALIFICATION -> QualificationsResource.RESOURCE_TYPE;
            case UNIT_ROLE -> UnitRolesResource.RESOURCE_TYPE;
        };
        String collection = switch (change.kind()) {
            case ORG_UNIT -> "org-units";
            case EMPLOYMENT -> "employments";
            case ASSIGNMENT -> "assignments";
            case REPORTING_LINE -> "reporting-lines";
            case QUALIFICATION -> "qualifications";
            case UNIT_ROLE -> "unit-roles";
        };
        return new EffectiveChangeRepresentation(change.date().toString(), type, change.resourceId(),
                "fuhito/" + collection + "/" + change.resourceId());
    }
}
