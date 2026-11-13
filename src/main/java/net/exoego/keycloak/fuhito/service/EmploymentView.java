package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;

/**
 * An employment with all its terms over time.
 *
 * @param employment the employment
 * @param endReason the code of the end reason, or null
 * @param terms the terms of each period, oldest first
 */
public record EmploymentView(Employment employment, String endReason, List<TermsView> terms) {

    /**
     * The terms of one period, with codes in place of IDs.
     *
     * @param terms the terms
     * @param employmentType the code of the employment type, or null
     * @param grade the code of the grade, or null
     */
    public record TermsView(EmploymentTerms terms, String employmentType, String grade) {
    }
}
