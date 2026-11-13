package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.service.AttributeSyncService;
import net.exoego.keycloak.fuhito.service.SyncReport;

/**
 * The sync from user attributes that an identity provider fills, for example over SCIM.
 */
@Produces(MediaType.APPLICATION_JSON)
public class AttributeSyncResource {

    private final RequestContext context;
    private final AttributeSyncService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the sync of the realm of the request
     */
    public AttributeSyncResource(RequestContext context, AttributeSyncService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Makes the assignments and reporting lines of the sync match the user attributes of now.
     * Running it again with no change in between changes nothing.
     *
     * @param dryRun true to report what the sync would do and write nothing
     * @return what the run did
     */
    @POST
    @Path("run")
    public SyncReportRepresentation run(@QueryParam("dryRun") boolean dryRun) {
        context.requireWrite();
        return Calls.run(context, () -> {
            SyncReport report = service.run();
            var representation = SyncReportRepresentation.of(report, dryRun);
            if (dryRun) {
                // The failure rolls back everything that the run wrote. The response is still a success.
                throw context.failure(Response.ok(representation, MediaType.APPLICATION_JSON_TYPE).build());
            }
            report.writes().forEach(write -> Writes.record(context, write));
            return representation;
        });
    }
}
