package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.AssignmentValues;
import net.exoego.keycloak.fuhito.service.AssignmentView;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Assignment",
        description = "A request to add an assignment gives the first details in the fields of the assignment "
                + "itself. A response gives the details of every period in `periods`.",
        requiredProperties = {"employmentId", "orgUnitId", "validFrom"})
public record AssignmentRepresentation(
        @Schema(readOnly = true)
        String id,
        String employmentId,
        @Schema(description = "The ID of the Keycloak user of the employment.", readOnly = true)
        String userId,
        String orgUnitId,
        @Schema(description = "The first date of the assignment.", format = "date")
        String validFrom,
        @Schema(description = "The first date after the assignment. It is missing for no end.", format = "date")
        String validTo,
        @Schema(description = "The ID of the assignment in the system it came from.")
        String externalId,
        @Schema(description = "The name of that system.")
        String source,
        @Schema(description = "The code of the first job title.", writeOnly = true)
        String jobTitle,
        @Schema(description = "The code of the first occupation.", writeOnly = true)
        String occupation,
        @Schema(description = "The code of the first assignment kind.", writeOnly = true)
        String kind,
        @Schema(description = "Whether the person leads the org unit at first.", writeOnly = true)
        Boolean head,
        @Schema(description = "Whether this is the main assignment at first.", writeOnly = true)
        Boolean primary,
        @Schema(
                description = "True to take the primary flag away from other assignments of the employment for the "
                        + "same dates.",
                writeOnly = true)
        Boolean switchPrimary,
        @Schema(
                description = "The first share of the person's work in percent.",
                minimum = "0",
                exclusiveMinimum = true,
                maximum = "100",
                writeOnly = true)
        BigDecimal allocationPercent,
        @Schema(description = "The first position among the members of the org unit.", writeOnly = true)
        Integer displayOrder,
        @Schema(description = "The first free values.", writeOnly = true)
        Map<String, Object> attributes,
        @Schema(description = "The details of each period, oldest first.", readOnly = true)
        List<Details> periods) {

    @Schema(
            name = "AssignmentDetails",
            description = "The details of an assignment for a period.",
            requiredProperties = {"validFrom"})
    public record Details(
            @Schema(description = "The first date of these details.", format = "date")
            String validFrom,
            @Schema(
                    description = "The first date after these details. It is missing for no end.",
                    format = "date",
                    readOnly = true)
            String validTo,
            @Schema(description = "The code of the job title.")
            String jobTitle,
            @Schema(description = "The code of the occupation.")
            String occupation,
            @Schema(description = "The code of the assignment kind.")
            String kind,
            @Schema(description = "True when the person leads the org unit.")
            Boolean head,
            @Schema(description = "True when this is the main assignment of the employment.")
            Boolean primary,
            @Schema(
                    description = "True to take the primary flag away from other assignments of the employment for "
                            + "the same dates.",
                    writeOnly = true)
            Boolean switchPrimary,
            @Schema(
                    description = "The share of the person's work in percent.",
                    minimum = "0",
                    exclusiveMinimum = true,
                    maximum = "100")
            BigDecimal allocationPercent,
            @Schema(description = "The position among the members of the org unit, lowest first.")
            Integer displayOrder,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes) {

        AssignmentValues toValues() {
            return new AssignmentValues(null, null, validFrom, null, jobTitle, occupation, kind, head, primary,
                    switchPrimary, allocationPercent, displayOrder, attributes, null, null);
        }
    }

    static AssignmentRepresentation of(AssignmentView view) {
        var first = view.rows().getFirst().row();
        return new AssignmentRepresentation(view.assignmentId(), first.employmentId(), view.userId(),
                first.orgUnitId(), view.period().from().toString(), text(view.period().to()), first.externalId(),
                first.source(), null, null, null, null, null, null, null, null, null,
                view.rows().stream()
                        .map(r -> new Details(r.row().period().from().toString(), text(r.row().period().to()),
                                r.jobTitle(), r.occupation(), r.kind(), r.row().head(), r.row().primary(), null,
                                Numbers.plain(r.row().allocationPercent()), r.row().displayOrder(),
                                r.row().attributes()))
                        .toList());
    }

    AssignmentValues toValues() {
        return new AssignmentValues(employmentId, orgUnitId, validFrom, validTo, jobTitle, occupation, kind, head,
                primary, switchPrimary, allocationPercent, displayOrder, attributes, externalId, source);
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }
}
