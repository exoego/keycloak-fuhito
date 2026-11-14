package net.exoego.keycloak.fuhito.rest;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.AssignmentView;
import net.exoego.keycloak.fuhito.service.EmploymentView;
import net.exoego.keycloak.fuhito.service.ImportReport;
import net.exoego.keycloak.fuhito.service.OrgUnitState;

/**
 * Records the writes of a bulk operation in the event log and the change log.
 */
final class Writes {

    private Writes() {
    }

    static void record(RequestContext context, ImportReport.Write write) {
        Object representation = switch (write.state()) {
            case RefItem item -> RefItemRepresentation.of(item);
            case OrgUnitState unit -> OrgUnitRepresentation.of(unit);
            case EmploymentView employment -> EmploymentRepresentation.of(employment);
            case AssignmentView assignment -> AssignmentRepresentation.of(assignment);
            default -> throw new IllegalStateException("No representation for " + write.state().getClass());
        };
        context.recordChange(write.created() ? Change.CREATE : Change.UPDATE, write.resourceType(),
                write.resourceId(), write.path(), representation);
    }
}
