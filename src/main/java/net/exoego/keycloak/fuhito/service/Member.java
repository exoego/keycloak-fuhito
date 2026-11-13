package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;

/**
 * One assignment of one person on one date, with what is known about the person and the org unit on that date.
 *
 * @param assignment the details of the assignment on the date
 * @param employment the employment of the assignment
 * @param terms the terms of the employment on the date
 * @param jobTitle the code of the job title, or null
 * @param occupation the code of the occupation, or null
 * @param kind the code of the assignment kind, or null
 * @param employmentType the code of the employment type, or null
 * @param grade the code of the grade, or null
 * @param orgUnit the org unit on the date
 * @param orgUnitPath the units from the root down to the org unit, or null when it was not asked for
 */
public record Member(Assignment assignment, Employment employment, EmploymentTerms terms, String jobTitle,
        String occupation, String kind, String employmentType, String grade, OrgUnitState orgUnit,
        List<OrgUnitState> orgUnitPath) {
}
