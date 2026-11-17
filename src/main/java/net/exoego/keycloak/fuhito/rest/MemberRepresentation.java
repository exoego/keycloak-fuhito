package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.Member;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Member",
        description = "One assignment of one person on one date.",
        requiredProperties = {"assignmentId", "employmentId", "personId", "seconded", "orgUnitId", "orgUnitName", "validFrom", "head", "primary", "displayOrder", "status"})
public record MemberRepresentation(
        String assignmentId,
        @Schema(description = "The version of the assignment.", readOnly = true)
        Long version,
        String employmentId,
        @Schema(description = "The ID of the person.")
        String personId,
        @Schema(description = "The ID of the Keycloak user that the person is linked to.")
        String userId,
        @Schema(description = "The ID of the org unit that employs the person.")
        String employerId,
        @Schema(
                description = "True when the person works outside the employer. The org unit is then not the "
                        + "employer and not below it in the default hierarchy.")
        boolean seconded,
        @Schema(description = "The number that the legal entity gives to the person.")
        String staffNumber,
        String orgUnitId,
        @Schema(description = "The name of the org unit on the date.")
        String orgUnitName,
        @Schema(description = "The units from the root down to the org unit. Only present when asked for.")
        List<PathEntry> orgUnitPath,
        @Schema(description = "The first date of these details of the assignment.", format = "date")
        String validFrom,
        @Schema(description = "The first date after these details. It is missing for no end.", format = "date")
        String validTo,
        @Schema(description = "The code of the job title.")
        String jobTitle,
        @Schema(description = "The code of the occupation.")
        String occupation,
        @Schema(description = "The code of the assignment kind.")
        String kind,
        @Schema(description = "The code of the work location of the assignment itself.")
        String workLocation,
        @Schema(description = "The code of the cost center of the assignment itself.")
        String costCenter,
        @Schema(
                description = "The code of the work location that applies on the date. It is the one of the "
                        + "assignment, else the one of the org unit, else the one of the nearest unit above it in the "
                        + "default hierarchy.")
        String effectiveWorkLocation,
        @Schema(
                description = "The code of the cost center that applies on the date. It is the one of the "
                        + "assignment, else the one of the org unit, else the one of the nearest unit above it in the "
                        + "default hierarchy.")
        String effectiveCostCenter,
        @Schema(description = "True when the person leads the org unit.")
        boolean head,
        @Schema(description = "True when this is the main assignment of the employment.")
        boolean primary,
        @Schema(
                description = "The share of the person's work in percent.",
                minimum = "0",
                exclusiveMinimum = true,
                maximum = "100")
        BigDecimal allocationPercent,
        @Schema(description = "The position among the members of the org unit, lowest first.")
        int displayOrder,
        @Schema(description = "Free values that the realm defines.")
        Map<String, Object> attributes,
        @Schema(description = "The code of the employment type on the date.")
        String employmentType,
        @Schema(description = "The code of the grade on the date.")
        String grade,
        @Schema(ref = "EmploymentStatus")
        String status,
        @Schema(
                description = "The share of full-time work on the date in percent.",
                minimum = "0",
                exclusiveMinimum = true,
                maximum = "100")
        BigDecimal workPercent) {

    @Schema(
            name = "PathEntry",
            description = "One org unit on the way from the root.",
            requiredProperties = {"id", "name"})
    public record PathEntry(
            String id,
            @Schema(description = "The name of the org unit on the date.")
            String name) {

        static PathEntry of(OrgUnitState state) {
            return new PathEntry(state.unit().id(), state.version().name());
        }
    }

    static MemberRepresentation of(Member member) {
        var row = member.assignment();
        var terms = member.terms();
        return new MemberRepresentation(row.assignmentId(), row.version(), row.employmentId(),
                member.employment().personId(), member.employment().userId(), member.employment().employerId(),
                member.seconded(),
                member.employment().staffNumber(), row.orgUnitId(), member.orgUnit().version().name(),
                member.orgUnitPath() == null ? null : member.orgUnitPath().stream().map(PathEntry::of).toList(),
                row.period().from().toString(), Objects.toString(row.period().to(), null),
                member.jobTitle(), member.occupation(), member.kind(), member.places().workLocation(),
                member.places().costCenter(), member.places().effectiveWorkLocation(),
                member.places().effectiveCostCenter(), row.head(), row.primary(),
                Numbers.plain(row.allocationPercent()), row.displayOrder(), row.attributes(),
                member.employmentType(), member.grade(), terms.status().name(), Numbers.plain(terms.workPercent()));
    }
}
