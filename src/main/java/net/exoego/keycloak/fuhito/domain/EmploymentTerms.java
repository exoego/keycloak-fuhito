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
 * @param workPercent the share of full-time work in percent, or null. 100 is full time.
 * @param attributes free values that the realm defines
 */
public record EmploymentTerms(String id, String employmentId, ValidPeriod period, String employmentTypeId,
        String gradeId, EmploymentStatus status, BigDecimal workPercent, Map<String, Object> attributes) {
}
