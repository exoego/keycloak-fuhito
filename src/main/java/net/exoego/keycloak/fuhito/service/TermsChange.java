package net.exoego.keycloak.fuhito.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The values of a request to change the terms of an employment from a date. They are not checked yet.
 * The values replace all terms. A missing optional value clears it.
 *
 * @param validFrom the first date of the new terms, as {@code YYYY-MM-DD}
 * @param employmentType the code of the employment type, or null
 * @param grade the code of the grade, or null
 * @param status the status, or null for {@code ACTIVE}
 * @param workPercent the share of full-time work in percent, or null
 * @param attributes free values, or null
 */
public record TermsChange(String validFrom, String employmentType, String grade, String status,
        BigDecimal workPercent, Map<String, Object> attributes) {
}
