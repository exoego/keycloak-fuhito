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
 * @param leaveReason the code of the leave reason, or null
 * @param workPercent the share of full-time work in percent, or null
 * @param contractEndDate the last day of the first fixed-term contract, as {@code YYYY-MM-DD}, or null for an
 *        indefinite contract
 * @param attributes free values, or null
 * @param externalId the ID of the employment in the system that the source names, or null
 * @param source the name of the system that makes the employment, or null
 * @param employerId the ID of the org unit that employs the person, or null when the realm has one legal entity
 */
public record NewEmployment(String personId, String validFrom, String validTo, String staffNumber,
        String employmentType, String grade, String status, String leaveReason, BigDecimal workPercent,
        String contractEndDate, Map<String, Object> attributes, String externalId, String source,
        String employerId) {

    /**
     * Makes a request for an indefinite contract.
     *
     * @param personId the ID of the person
     * @param validFrom the first date of the employment, as {@code YYYY-MM-DD}
     * @param validTo the first date after the employment, or null for no end
     * @param staffNumber the number that the legal entity gives to the person, or null
     * @param employmentType the code of the employment type, or null
     * @param grade the code of the grade, or null
     * @param status the status, or null for {@code ACTIVE}
     * @param leaveReason the code of the leave reason, or null
     * @param workPercent the share of full-time work in percent, or null
     * @param attributes free values, or null
     * @param externalId the ID of the employment in the system that the source names, or null
     * @param source the name of the system that makes the employment, or null
     * @param employerId the ID of the org unit that employs the person, or null when the realm has one legal entity
     */
    public NewEmployment(String personId, String validFrom, String validTo, String staffNumber,
            String employmentType, String grade, String status, String leaveReason, BigDecimal workPercent,
            Map<String, Object> attributes, String externalId, String source, String employerId) {
        this(personId, validFrom, validTo, staffNumber, employmentType, grade, status, leaveReason, workPercent,
                null, attributes, externalId, source, employerId);
    }

    /**
     * Makes a request for an indefinite contract that names no employer. It works in a realm with one legal entity.
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
     * @param externalId the ID of the employment in the system that the source names, or null
     * @param source the name of the system that makes the employment, or null
     */
    public NewEmployment(String personId, String validFrom, String validTo, String staffNumber,
            String employmentType, String grade, String status, BigDecimal workPercent,
            Map<String, Object> attributes, String externalId, String source) {
        this(personId, validFrom, validTo, staffNumber, employmentType, grade, status, null, workPercent,
                null, attributes, externalId, source, null);
    }
}
