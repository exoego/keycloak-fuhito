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
 * @param externalEmployer the code of the external employer of the employment, or null for a legal entity
 * @param terms the terms of the employment on the date
 * @param jobTitle the code of the job title, or null
 * @param occupation the code of the occupation, or null
 * @param kind the code of the assignment kind, or null
 * @param employmentType the code of the employment type, or null
 * @param grade the code of the grade, or null
 * @param orgUnit the org unit on the date
 * @param orgUnitPath the units from the root down to the org unit, or null when it was not asked for
 * @param seconded true when the org unit belongs to another legal entity than the employer on the date. A unit
 *        belongs to the legal entity that it is, else to the nearest one above it in the default hierarchy, or to
 *        none. A unit that belongs to none, or an external employer, gives no secondment.
 * @param places the work location and the cost center of the assignment and the ones that apply on the date
 */
public record Member(Assignment assignment, Employment employment, String externalEmployer, EmploymentTerms terms,
        String jobTitle,
        String occupation, String kind, String employmentType, String grade, OrgUnitState orgUnit,
        List<OrgUnitState> orgUnitPath, boolean seconded, LocationAndCost places) {
}
