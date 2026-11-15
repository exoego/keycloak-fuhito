package net.exoego.keycloak.fuhito.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The conditions of an employment for a period.
 *
 * @param id the ID
 * @param employmentId the ID of the employment
 * @param period the dates when these conditions are true
 * @param employmentTypeId the ID of the employment type, or null
 * @param gradeId the ID of the grade, or null
 * @param status whether the person works
 * @param leaveReasonId the ID of the leave reason, or null. Only terms with the status {@code ON_LEAVE} have one.
 * @param workPercent the share of full-time work in percent, or null. 100 is full time.
 * @param attributes free values that the realm defines
 */
public record EmploymentTerms(String id, String employmentId, ValidPeriod period, String employmentTypeId,
        String gradeId, EmploymentStatus status, String leaveReasonId, BigDecimal workPercent,
        Map<String, Object> attributes) {

    /**
     * Returns the same conditions for another period.
     *
     * @param newPeriod the period
     * @return the terms
     */
    public EmploymentTerms withPeriod(ValidPeriod newPeriod) {
        return copy(id, employmentId, newPeriod);
    }

    /**
     * Returns the same conditions under another ID, for another employment and period.
     *
     * @param newId the ID
     * @param newEmploymentId the ID of the employment
     * @param newPeriod the period
     * @return the terms
     */
    public EmploymentTerms copy(String newId, String newEmploymentId, ValidPeriod newPeriod) {
        return new EmploymentTerms(newId, newEmploymentId, newPeriod, employmentTypeId, gradeId, status, leaveReasonId,
                workPercent, attributes);
    }
}
