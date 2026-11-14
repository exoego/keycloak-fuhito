package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.AssignmentValues;
import net.exoego.keycloak.fuhito.service.AssignmentView;

/**
 * An assignment in requests and responses.
 * A request to add an assignment gives the first details in the fields of the assignment itself.
 * A response gives the details of every period in {@code periods}.
 *
 * @param id the ID. Requests ignore it.
 * @param employmentId the ID of the employment
 * @param userId the ID of the Keycloak user of the employment. Only responses have it.
 * @param orgUnitId the ID of the org unit
 * @param validFrom the first date of the assignment, as {@code YYYY-MM-DD}
 * @param validTo the first date after the assignment, or null for no end
 * @param externalId the ID of the assignment in the system it came from, or null
 * @param source the name of that system, or null
 * @param jobTitle the code of the first job title. Only requests use it.
 * @param occupation the code of the first occupation. Only requests use it.
 * @param kind the code of the first assignment kind. Only requests use it.
 * @param head whether the person leads the org unit at first. Only requests use it.
 * @param primary whether this is the main assignment at first. Only requests use it.
 * @param switchPrimary true to take the primary flag away from other assignments of the employment for the same
 *        dates. Only requests use it.
 * @param allocationPercent the first share of the person's work in percent. Only requests use it.
 * @param displayOrder the first position among the members of the org unit. Only requests use it.
 * @param attributes the first free values. Only requests use them.
 * @param periods the details of each period, oldest first. Only responses have them.
 */
public record AssignmentRepresentation(String id, String employmentId, String userId, String orgUnitId,
        String validFrom, String validTo, String externalId, String source, String jobTitle, String occupation,
        String kind, Boolean head, Boolean primary, Boolean switchPrimary, BigDecimal allocationPercent,
        Integer displayOrder, Map<String, Object> attributes, List<Details> periods) {

    /**
     * The details of an assignment for a period.
     *
     * @param validFrom the first date of these details
     * @param validTo the first date after these details, or null for no end. Requests ignore it.
     * @param jobTitle the code of the job title, or null
     * @param occupation the code of the occupation, or null
     * @param kind the code of the assignment kind, or null
     * @param head true when the person leads the org unit
     * @param primary true when this is the main assignment of the employment
     * @param switchPrimary true to take the primary flag away from other assignments of the employment for the
     *        same dates. Only requests use it.
     * @param allocationPercent the share of the person's work in percent, or null
     * @param displayOrder the position among the members of the org unit, lowest first
     * @param attributes free values that the realm defines
     */
    public record Details(String validFrom, String validTo, String jobTitle, String occupation, String kind,
            Boolean head, Boolean primary, Boolean switchPrimary, BigDecimal allocationPercent,
            Integer displayOrder, Map<String, Object> attributes) {

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
