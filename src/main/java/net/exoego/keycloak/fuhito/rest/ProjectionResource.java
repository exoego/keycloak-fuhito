package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.ProjectionReport;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The projection of the org structure into the groups of Keycloak organizations.
 */
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.KEYCLOAK)
public class ProjectionResource {

    private final RequestContext context;
    private final ProjectionService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the projection of the realm of the request
     */
    public ProjectionResource(RequestContext context, ProjectionService service) {
        this.context = context;
        this.service = service;
    }

    @POST
    @Path("rebuild")
    @Operation(
            operationId = "rebuildProjection",
            summary = "Mirrors the org structure of today into the groups of Keycloak organizations.",
            description = "A rebuild with no change in between changes nothing.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "What the rebuild changed in Keycloak.")
    @BadRequest
    @ApiErrors
    public ProjectionReport rebuild() {
        context.requireWrite();
        return Calls.run(context, service::rebuild);
    }
}
