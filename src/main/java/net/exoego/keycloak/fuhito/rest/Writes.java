package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;

/**
 * Records the writes of a bulk operation in the event log and the change log.
 */
final class Writes {

    private Writes() {
    }

    static void record(RequestContext context, Write write) {
        Object representation = switch (write) {
            case Write.OfRefItem item -> RefItemRepresentation.of(item.state());
            case Write.OfOrgUnit unit -> OrgUnitRepresentation.of(unit.state());
            case Write.OfEmployment employment -> EmploymentRepresentation.of(employment.state());
            case Write.OfAssignment assignment -> AssignmentRepresentation.of(assignment.state());
        };
        context.recordChange(write.created() ? Change.CREATE : Change.UPDATE, write.resourceType(),
                write.resourceId(), write.path(), representation);
    }
}
