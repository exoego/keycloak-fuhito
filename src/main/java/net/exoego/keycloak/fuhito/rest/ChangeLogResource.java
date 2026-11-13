package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.ChangeLogService;

/**
 * The change log of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
public class ChangeLogResource {

    private final RequestContext context;
    private final ChangeLogService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the reads on the realm of the request
     */
    public ChangeLogResource(RequestContext context, ChangeLogService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Lists the writes to one resource, oldest first.
     *
     * @param resourceType the type of the resource, for example {@code FUHITO_ORG_UNIT}
     * @param resourceId the ID of the resource
     * @param first the number of entries to skip. The default is 0.
     * @param max the largest number of entries to return. The default is 100.
     * @return the entries
     */
    @GET
    public List<ChangeLogRepresentation> ofResource(@QueryParam("resourceType") String resourceType,
            @QueryParam("resourceId") String resourceId, @QueryParam("first") Integer first,
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> service.ofResource(resourceType, resourceId, first, max).stream()
                .map(ChangeLogRepresentation::of)
                .toList());
    }
}
