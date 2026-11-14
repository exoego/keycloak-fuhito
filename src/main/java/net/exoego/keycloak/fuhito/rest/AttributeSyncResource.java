package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.service.AttributeSyncService;
import net.exoego.keycloak.fuhito.service.SyncReport;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The sync from user attributes that an identity provider fills, for example over SCIM.
 */
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.KEYCLOAK)
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

    @POST
    @Path("run")
    @Operation(
            operationId = "runAttributeSync",
            summary = "Makes the assignments and reporting lines of the sync match the user attributes of now.",
            description = "Running it again with no change in between changes nothing.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "What the run did.")
    @BadRequest
    @ApiErrors
    public SyncReportRepresentation run(
            @Parameter(
                    description = "True to report what the sync would do and write nothing.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("dryRun") boolean dryRun) {
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
