package net.exoego.keycloak.fuhito.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.exoego.keycloak.fuhito.service.EmploymentView;
import net.exoego.keycloak.fuhito.service.NewEmployment;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An employment in requests and responses.
 * A request to start an employment gives the first terms in the fields of the employment itself.
 * A response gives the terms of every period in {@code terms}.
 *
 * @param id the ID. Requests ignore it.
 * @param userId the ID of the Keycloak user
 * @param validFrom the first date of the employment, as {@code YYYY-MM-DD}
 * @param validTo the first date after the employment, or null for no end
 * @param staffNumber the number that the legal entity gives to the person, or null
 * @param endReason the code of the end reason, or null. Requests to start ignore it.
 * @param externalId the ID of the employment in the system it came from, or null
 * @param source the name of that system, or null
 * @param employmentType the code of the first employment type. Only requests use it.
 * @param grade the code of the first grade. Only requests use it.
 * @param status the first status. Only requests use it. The default is {@code ACTIVE}.
 * @param workPercent the first share of full-time work in percent. Only requests use it.
 * @param attributes the first free values. Only requests use them.
 * @param terms the terms of each period, oldest first. Only responses have them.
 */
@Schema(
        name = "Employment",
        description = "A request to start an employment gives the first terms in the fields of the employment "
                + "itself. A response gives the terms of every period in `terms`.",
        requiredProperties = {"userId", "validFrom"})
public record EmploymentRepresentation(
        @Schema(readOnly = true)
        String id,
        @Schema(description = "The ID of the Keycloak user.")
        String userId,
        @Schema(description = "The first date of the employment.", format = "date")
        String validFrom,
        @Schema(description = "The first date after the employment. It is missing for no end.", format = "date")
        String validTo,
        @Schema(
                description = "The number that the legal entity gives to the person.",
                maxLength = 64,
                pattern = "^[A-Za-z0-9][A-Za-z0-9_.-]*$")
        String staffNumber,
        @Schema(description = "The code of the end reason.", readOnly = true)
        String endReason,
        @Schema(description = "The ID of the employment in the system it came from.")
        String externalId,
        @Schema(description = "The name of that system.")
        String source,
        @Schema(description = "The code of the first employment type.", writeOnly = true)
        String employmentType,
        @Schema(description = "The code of the first grade.", writeOnly = true)
        String grade,
        @Schema(
                description = "The first status.",
                ref = "EmploymentStatus",
                defaultValue = "ACTIVE",
                writeOnly = true)
        String status,
        @Schema(
                description = "The first share of full-time work in percent.",
                minimum = "0",
                exclusiveMinimum = true,
                maximum = "100",
                writeOnly = true)
        BigDecimal workPercent,
        @Schema(description = "The first free values.", writeOnly = true)
        Map<String, Object> attributes,
        @Schema(description = "The terms of each period, oldest first.", readOnly = true)
        List<Terms> terms) {

    /**
     * The conditions of an employment for a period.
     *
     * @param validFrom the first date of these conditions
     * @param validTo the first date after these conditions, or null for no end
     * @param employmentType the code of the employment type, or null
     * @param grade the code of the grade, or null
     * @param status {@code ACTIVE}, {@code ON_LEAVE}, or {@code SUSPENDED}
     * @param workPercent the share of full-time work in percent, or null
     * @param attributes free values that the realm defines
     */
    @Schema(
            name = "EmploymentTerms",
            description = "The conditions of an employment for a period.",
            requiredProperties = {"validFrom"})
    public record Terms(
            @Schema(description = "The first date of these conditions.", format = "date")
            String validFrom,
            @Schema(
                    description = "The first date after these conditions. It is missing for no end.",
                    format = "date",
                    readOnly = true)
            String validTo,
            @Schema(description = "The code of the employment type.")
            String employmentType,
            @Schema(description = "The code of the grade.")
            String grade,
            @Schema(ref = "EmploymentStatus", defaultValue = "ACTIVE")
            String status,
            @Schema(
                    description = "The share of full-time work in percent.",
                    minimum = "0",
                    exclusiveMinimum = true,
                    maximum = "100")
            BigDecimal workPercent,
            @Schema(description = "Free values that the realm defines.")
            Map<String, Object> attributes) {
    }

    /**
     * Builds the representation of an employment.
     *
     * @param view the employment with its terms
     * @return the representation
     */
    public static EmploymentRepresentation of(EmploymentView view) {
        var e = view.employment();
        return new EmploymentRepresentation(e.id(), e.userId(), e.period().from().toString(), text(e.period().to()),
                e.staffNumber(), view.endReason(), e.externalId(), e.source(), null, null, null, null, null,
                view.terms().stream()
                        .map(t -> new Terms(t.terms().period().from().toString(), text(t.terms().period().to()),
                                t.employmentType(), t.grade(), t.terms().status().name(), Numbers.plain(t.terms().workPercent()),
                                t.terms().attributes()))
                        .toList());
    }

    NewEmployment toNewEmployment() {
        return new NewEmployment(userId, validFrom, validTo, staffNumber, employmentType, grade, status, workPercent,
                attributes, externalId, source);
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }
}
