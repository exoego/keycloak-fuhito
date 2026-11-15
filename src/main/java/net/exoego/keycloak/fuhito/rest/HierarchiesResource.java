package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.HierarchyService;
import net.exoego.keycloak.fuhito.service.Page;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The hierarchies of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.ORG_UNITS)
public class HierarchiesResource {

    /**
     * The resource type of hierarchies in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_HIERARCHY";

    private final RequestContext context;
    private final HierarchyService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public HierarchiesResource(RequestContext context, HierarchyService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(operationId = "listHierarchies", summary = "Lists the hierarchies of the realm, ordered by code.")
    @APIResponse(responseCode = "200", description = "The hierarchies.")
    @ApiErrors
    public List<HierarchyRepresentation> list(
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireReadStructure();
        return Calls.run(context,
                () -> service.list(Page.of(first, max)).stream().map(HierarchyRepresentation::of).toList());
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getHierarchy", summary = "Returns one hierarchy.")
    @APIResponse(responseCode = "200", description = "The hierarchy.")
    @NotFound
    @ApiErrors
    public HierarchyRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.tagged(context, () -> HierarchyRepresentation.of(service.get(id)));
    }

    @POST
    @Operation(
            operationId = "createHierarchy",
            summary = "Creates a hierarchy.",
            description = "A second hierarchy describes another view of the same org units, such as a matrix "
                    + "organization. The body needs `code` and `name`.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new hierarchy.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = HierarchyRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(HierarchyRepresentation body) {
        context.requireWrite();
        return Calls.once(context, body, Response.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            var hierarchy = HierarchyRepresentation.of(service.create(body.code(), body.name()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, hierarchy.id(), path(hierarchy.id()), hierarchy);
            return Response.status(Response.Status.CREATED).entity(hierarchy).build();
        }));
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "renameHierarchy",
            summary = "Changes the name of a hierarchy.",
            description = "The code never changes. The body can leave it out.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed hierarchy.")
    @BadRequest
    @NotFound
    @ApiErrors
    public HierarchyRepresentation rename(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            HierarchyRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            var hierarchy = HierarchyRepresentation.of(service.rename(id, body.name()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), hierarchy);
            return hierarchy;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteHierarchy",
            summary = "Deletes a hierarchy.",
            description = "The default hierarchy cannot be deleted. A hierarchy with parent links, on any date, "
                    + "cannot be deleted.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The hierarchy was deleted.")
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.guardedRemoval(context, () -> state(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/hierarchies/" + id;
    }

    // The whole stored state of one resource, which its ETag covers.
    private Object state(String id) {
        return HierarchyRepresentation.of(service.get(id));
    }
}
