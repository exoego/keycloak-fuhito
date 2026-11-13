package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.AssignmentView;
import net.exoego.keycloak.fuhito.service.EmploymentView;
import net.exoego.keycloak.fuhito.service.ImportDocument;
import net.exoego.keycloak.fuhito.service.ImportReport;
import net.exoego.keycloak.fuhito.service.ImportService;
import net.exoego.keycloak.fuhito.service.OrgUnitState;

/**
 * Imports into one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ImportsResource {

    private final RequestContext context;
    private final ImportService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public ImportsResource(RequestContext context, ImportService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Loads many rows in one transaction.
     * All rows are written, or none. A dry run does all checks and writes nothing.
     *
     * @param body the effective date, the dry run flag, and the rows
     * @return 200 with the report when the rows were written or the request was a dry run without errors.
     *         400 with the report when a row failed.
     */
    @POST
    public ImportReportRepresentation apply(ImportDocument body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            ImportReport report = service.apply(body);
            var representation = ImportReportRepresentation.of(report, body.dryRun(), context.locale());
            if (!representation.errors().isEmpty()) {
                throw context.failure(json(Response.Status.BAD_REQUEST, representation));
            }
            if (body.dryRun()) {
                // The failure rolls back everything that the checks wrote. The response is still a success.
                throw context.failure(json(Response.Status.OK, representation));
            }
            report.writes().forEach(this::record);
            return representation;
        });
    }

    private void record(ImportReport.Write write) {
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

    private static Response json(Response.Status status, Object entity) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(entity).build();
    }
}
