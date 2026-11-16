package net.exoego.keycloak.fuhito.rest;

import java.time.Instant;
import java.util.Map;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.ProjectionReport;
import net.exoego.keycloak.fuhito.service.ProjectionService;
import net.exoego.keycloak.fuhito.store.JobRunStore;
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
    private final JobRunStore runs;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the projection of the realm of the request
     * @param runs the last runs of the realm
     */
    public ProjectionResource(RequestContext context, ProjectionService service, JobRunStore runs) {
        this.context = context;
        this.service = service;
        this.runs = runs;
    }

    @POST
    @Path("rebuild")
    @Operation(
            operationId = "rebuildProjection",
            summary = "Mirrors the org structure of today into the groups of Keycloak organizations.",
            description = "A rebuild with no change in between changes nothing. It also needs the permission of "
                    + "Keycloak to manage every organization that it may change, and to manage groups when the "
                    + "realm uses realm groups. These are checked before anything is written.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "What the rebuild changed in Keycloak.")
    @BadRequest
    @ApiErrors
    public ProjectionReport rebuild() {
        context.requireWrite();
        return Calls.run(context, () -> service.rebuild(context::requireManageOrganization,
                context::requireManageGroups));
    }

    @GET
    @Path("last-run")
    @Operation(
            operationId = "getLastProjectionRun",
            summary = "Returns the state and the counts of the last rebuild of the projection.",
            description = "A caller whose call a proxy ended can read the result here. The job goes on in the "
                    + "server either way.")
    @APIResponse(responseCode = "200", description = "The last run.")
    @NotFound
    @ApiErrors
    public JobRunRepresentation lastRun() {
        context.requireRead();
        return Calls.run(context, () -> runs.last("projection").map(run -> JobRunRepresentation.of(run, Instant.now()))
                .orElseThrow(() -> FuhitoException.notFound("job.never_ran", Map.of("job", "projection"))));
    }
}
