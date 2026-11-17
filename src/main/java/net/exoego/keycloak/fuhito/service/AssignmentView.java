package net.exoego.keycloak.fuhito.service;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;

/**
 * An assignment with its details over time.
 *
 * @param assignmentId the ID of the assignment
 * @param userId the ID of the Keycloak user of the employment
 * @param period the dates from the first row to the last
 * @param continuesAssignmentId the ID of the assignment that this one continues, or null
 * @param rows the details of each period, oldest first
 */
public record AssignmentView(String assignmentId, String userId, ValidPeriod period, String continuesAssignmentId,
        List<Row> rows) {

    /**
     * The details of one period, with codes in place of IDs.
     *
     * @param row the row
     * @param jobTitle the code of the job title, or null
     * @param occupation the code of the occupation, or null
     * @param kind the code of the assignment kind, or null
     * @param places the work location and the cost center of the row and the ones that apply to it
     */
    public record Row(Assignment row, String jobTitle, String occupation, String kind, LocationAndCost places) {
    }
}
