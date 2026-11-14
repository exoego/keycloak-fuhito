package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.Member;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One assignment of one person on one date.
 *
 * @param assignmentId the ID of the assignment
 * @param employmentId the ID of the employment
 * @param userId the ID of the Keycloak user
 * @param staffNumber the number that the legal entity gives to the person, or null
 * @param orgUnitId the ID of the org unit
 * @param orgUnitName the name of the org unit on the date
 * @param orgUnitPath the units from the root down to the org unit. Only present when asked for.
 * @param validFrom the first date of these details of the assignment
 * @param validTo the first date after these details, or null for no end
 * @param jobTitle the code of the job title, or null
 * @param occupation the code of the occupation, or null
 * @param kind the code of the assignment kind, or null
 * @param head true when the person leads the org unit
 * @param primary true when this is the main assignment of the employment
 * @param allocationPercent the share of the person's work in percent, or null
 * @param displayOrder the position among the members of the org unit, lowest first
 * @param attributes free values of the assignment
 * @param employmentType the code of the employment type on the date, or null
 * @param grade the code of the grade on the date, or null
 * @param status the employment status on the date
 * @param workPercent the share of full-time work on the date in percent, or null
 */
@Schema(
        name = "Member",
        description = "One assignment of one person on one date.",
        requiredProperties = {"assignmentId", "employmentId", "userId", "orgUnitId", "orgUnitName", "validFrom", "head", "primary", "displayOrder", "status"})
public record MemberRepresentation(
        String assignmentId,
        String employmentId,
        @Schema(description = "The ID of the Keycloak user.")
        String userId,
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

    /**
     * One org unit on the way from the root.
     *
     * @param id the ID of the org unit
     * @param name the name of the org unit on the date
     */
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
        return new MemberRepresentation(row.assignmentId(), row.employmentId(), member.employment().userId(),
                member.employment().staffNumber(), row.orgUnitId(), member.orgUnit().version().name(),
                member.orgUnitPath() == null ? null : member.orgUnitPath().stream().map(PathEntry::of).toList(),
                row.period().from().toString(), Objects.toString(row.period().to(), null),
                member.jobTitle(), member.occupation(), member.kind(), row.head(), row.primary(),
                Numbers.plain(row.allocationPercent()), row.displayOrder(), row.attributes(),
                member.employmentType(), member.grade(), terms.status().name(), Numbers.plain(terms.workPercent()));
    }
}
