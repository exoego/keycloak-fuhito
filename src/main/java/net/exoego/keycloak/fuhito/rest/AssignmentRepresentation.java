package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.AssignmentValues;
import net.exoego.keycloak.fuhito.service.AssignmentView;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(
        name = "Assignment",
        description = "A request to add an assignment gives the first details in the fields of the assignment "
                + "itself. A response gives the details of every period in `periods`.",
        requiredProperties = {"employmentId", "orgUnitId", "validFrom"})
public record AssignmentRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(
                description = "The version of the assignment. A new assignment has 1. Each write counts it up by one, "
                        + "also a change of a period or of an ID of another system.",
                readOnly = true)
        Long version,
        String employmentId,
        @Schema(description = "The ID of the Keycloak user of the employment.", readOnly = true)
        String userId,
        String orgUnitId,
        @Schema(description = "The first date of the assignment.", format = "date")
        String validFrom,
        @Schema(description = "The first date after the assignment. It is missing for no end.", format = "date")
        String validTo,
        @Schema(
                description = "The ID of the assignment in the system that `source` names. A write stores it as the "
                        + "ID of that system, in place of the one that the system had. A read gives every ID in "
                        + "`externalIds`.",
                maxLength = 255,
                writeOnly = true)
        String externalId,
        @Schema(
                description = "The name of the system that made the assignment. A create sets it, and it never "
                        + "changes. With `externalId`, it names the system of that ID.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String source,
        @Schema(
                description = "Every ID that a system has for the assignment. The one of `source` comes first, then "
                        + "the others by system.",
                readOnly = true)
        List<ExternalIdPairRepresentation> externalIds,
        @Schema(
                description = "The ID of the assignment that this one continues, after a move to another org unit "
                        + "or a transfer to another employer.",
                readOnly = true)
        String continuesAssignmentId,
        @Schema(description = "The code of the first job title.", writeOnly = true)
        String jobTitle,
        @Schema(description = "The code of the first occupation.", writeOnly = true)
        String occupation,
        @Schema(description = "The code of the first assignment kind.", writeOnly = true)
        String kind,
        @Schema(
                description = "The code of the first work location. Leave it out to take the one of the org unit.",
                writeOnly = true)
        String workLocation,
        @Schema(
                description = "The code of the first cost center. Leave it out to take the one of the org unit.",
                writeOnly = true)
        String costCenter,
        @Schema(description = "Whether the person leads the org unit at first.", writeOnly = true)
        Boolean head,
        @Schema(description = "Whether this is the main assignment at first.", writeOnly = true)
        Boolean primary,
        @Schema(
                description = "True to take the primary flag away from other assignments of the employment for the "
                        + "same dates. True needs `primary` true. Without it, the request fails with "
                        + "`assignment.switch_primary_unused`.",
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
            requiredProperties = {"version", "validFrom"})
    public record Details(
            @Schema(description = "The version of the assignment that the caller read.", writeOnly = true)
            Long version,
            @Schema(description = "The first date of these details.", format = "date")
            String validFrom,
            @Schema(
                    description = "The first date after these details. It is missing for no end. A change of the "
                            + "details with another value than the end of the details on its `validFrom` fails with "
                            + "400 and `invalid.value_fixed`.",
                    format = "date",
                    readOnly = true)
            String validTo,
            @Schema(description = "The code of the job title.")
            String jobTitle,
            @Schema(description = "The code of the occupation.")
            String occupation,
            @Schema(description = "The code of the assignment kind.")
            String kind,
            @Schema(
                    description = "The code of the work location of the assignment itself. Leave it out to take the "
                            + "one of the org unit.")
            String workLocation,
            @Schema(
                    description = "The code of the cost center of the assignment itself. Leave it out to take the "
                            + "one of the org unit.")
            String costCenter,
            @Schema(
                    description = "The code of the work location that applies. It is the one of the assignment, "
                            + "else the one of the org unit, else the one of the nearest unit above it in the default "
                            + "hierarchy. It is read on the date of the period nearest to today. A change of the "
                            + "details fails with 400 and `invalid.value_fixed` when this is not the value of the "
                            + "details on its `validFrom`.",
                    readOnly = true)
            String effectiveWorkLocation,
            @Schema(
                    description = "The code of the cost center that applies. It is the one of the assignment, else "
                            + "the one of the org unit, else the one of the nearest unit above it in the default "
                            + "hierarchy. It is read on the date of the period nearest to today. A change of the "
                            + "details fails with 400 and `invalid.value_fixed` when this is not the value of the "
                            + "details on its `validFrom`.",
                    readOnly = true)
            String effectiveCostCenter,
            @Schema(description = "True when the person leads the org unit.")
            Boolean head,
            @Schema(description = "True when this is the main assignment of the employment.")
            Boolean primary,
            @Schema(
                    description = "True to take the primary flag away from other assignments of the employment for "
                            + "the same dates. True needs `primary` true. Without it, the request fails with "
                            + "`assignment.switch_primary_unused`.",
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
            Map<String, Object> attributes) implements Versioned {

        // The fields that only a response fills.
        static final List<String> READ_ONLY = List.of("validTo", "effectiveWorkLocation", "effectiveCostCenter");

        AssignmentValues toValues() {
            return new AssignmentValues(null, null, validFrom, null, jobTitle, occupation, kind, workLocation,
                    costCenter, head, primary, switchPrimary, allocationPercent, displayOrder, attributes, null,
                    null);
        }
    }

    static AssignmentRepresentation of(AssignmentView view, ExternalIdLists ids) {
        var first = view.rows().getFirst().row();
        return new AssignmentRepresentation(view.assignmentId(), first.version(), first.employmentId(),
                view.userId(), first.orgUnitId(), view.period().from().toString(), text(view.period().to()), null,
                first.source(), ExternalIdPairRepresentation.of(ids.of(view.assignmentId(), first.source())),
                view.continuesAssignmentId(), null, null, null, null, null, null, null, null, null,
                null, null, view.rows().stream()
                        .map(r -> new Details(null, r.row().period().from().toString(), text(r.row().period().to()),
                                r.jobTitle(), r.occupation(), r.kind(), r.places().workLocation(),
                                r.places().costCenter(), r.places().effectiveWorkLocation(),
                                r.places().effectiveCostCenter(), r.row().head(), r.row().primary(), null,
                                Numbers.plain(r.row().allocationPercent()), r.row().displayOrder(),
                                r.row().attributes()))
                        .toList());
    }

    AssignmentValues toValues() {
        return new AssignmentValues(employmentId, orgUnitId, validFrom, validTo, jobTitle, occupation, kind,
                workLocation, costCenter, head, primary, switchPrimary, allocationPercent, displayOrder, attributes,
                externalId, source);
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }
}
