package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The values of a request to start an employment. They are not checked yet.
 *
 * @param personId the ID of the person
 * @param validFrom the first date of the employment, as {@code YYYY-MM-DD}
 * @param validTo the first date after the employment, or null for no end
 * @param staffNumber the number that the legal entity gives to the person, or null
 * @param employmentType the code of the employment type, or null
 * @param grade the code of the grade, or null
 * @param status the status, or null for {@code ACTIVE}
 * @param workPercent the share of full-time work in percent, or null
 * @param attributes free values, or null
 * @param externalId the ID of the employment in the system it came from, or null
 * @param source the name of that system, or null
 */
public record NewEmployment(String personId, String validFrom, String validTo, String staffNumber,
        String employmentType, String grade, String status, BigDecimal workPercent, Map<String, Object> attributes,
        String externalId, String source) {
}
