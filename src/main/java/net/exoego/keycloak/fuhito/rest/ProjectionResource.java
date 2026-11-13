package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.ProjectionReport;
import net.exoego.keycloak.fuhito.service.ProjectionService;

/**
 * The projection of the org structure into the groups of Keycloak organizations.
 */
@Produces(MediaType.APPLICATION_JSON)
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

    /**
     * Makes the groups and the memberships of the linked Keycloak organizations match the org structure of today.
     * Running it again with no change in between changes nothing.
     *
     * @return what the rebuild changed
     */
    @POST
    @Path("rebuild")
    public ProjectionReport rebuild() {
        context.requireWrite();
        return Calls.run(context, service::rebuild);
    }
}
