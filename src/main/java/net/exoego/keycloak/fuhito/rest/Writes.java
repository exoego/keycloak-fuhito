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
        Object state = write.state();
        Object representation;
        if (state instanceof RefItem item) {
            representation = RefItemRepresentation.of(item);
        } else if (state instanceof OrgUnitState unit) {
            representation = OrgUnitRepresentation.of(unit);
        } else if (state instanceof EmploymentView employment) {
            representation = EmploymentRepresentation.of(employment);
        } else {
            representation = AssignmentRepresentation.of((AssignmentView) state);
        }
        context.recordChange(write.created() ? Change.CREATE : Change.UPDATE, write.resourceType(),
                write.resourceId(), write.path(), representation);
    }
}
