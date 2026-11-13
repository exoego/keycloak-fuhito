package net.exoego.keycloak.fuhito.rest;

import java.util.List;

/**
 * All organization data of one realm, with every period of every resource.
 *
 * @param exportedAt when the export was made, in UTC
 * @param refItems the reference items of every kind, inactive ones included
 * @param orgUnits the org units with every period of their details and their parents
 * @param employments the employments with every period of their terms
 * @param assignments the assignments with every period of their details
 * @param changeOrders the change orders
 */
public record ExportRepresentation(String exportedAt, List<RefItemRepresentation> refItems,
        List<OrgUnitHistoryRepresentation> orgUnits, List<EmploymentRepresentation> employments,
        List<AssignmentRepresentation> assignments, List<ChangeOrderRepresentation> changeOrders) {
}
