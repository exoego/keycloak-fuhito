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
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.HierarchyService;
import net.exoego.keycloak.fuhito.service.Page;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
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
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public HierarchiesResource(RequestContext context, HierarchyService service, ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.externalIds = externalIds;
    }

    @GET
    @Operation(operationId = "listHierarchies", summary = "Lists the hierarchies of the realm, ordered by code.")
    @APIResponse(responseCode = "200", description = "The hierarchies.")
    @BadRequest
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
        return Calls.run(context, () -> represent(service.list(Page.of(first, max))));
    }

    @GET
    @Path("count")
    @Operation(operationId = "countHierarchies", summary = "Counts the hierarchies that `GET /hierarchies` lists.")
    @APIResponse(responseCode = "200", description = "The number of hierarchies on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count() {
        context.requireReadStructure();
        return Calls.run(context, () -> new CountRepresentation(service.count()));
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getHierarchy", summary = "Returns one hierarchy.")
    @APIResponse(responseCode = "200", description = "The hierarchy.")
    @NotFound
    @BadRequest
    @ApiErrors
    public HierarchyRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.get(id)));
    }

    @POST
    @Operation(
            operationId = "createHierarchy",
            summary = "Creates a hierarchy.",
            description = "A second hierarchy puts the same org units in another tree, such as regions that "
                    + "gather branches of several divisions. The body needs `code` and `name`.")
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
        return Calls.run(context, () -> {
            Calls.body(body);
            Writes.requireNoSource(body.source(), body.externalId());
            var hierarchy = represent(externalIds.create(ExternalId.Kind.HIERARCHY, body.source(), body.externalId(),
                    () -> service.create(body.code(), body.name()), Hierarchy::id));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, hierarchy.id(), path(hierarchy.id()), hierarchy);
            return Response.status(Response.Status.CREATED).entity(hierarchy).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "renameHierarchy",
            summary = "Changes the name of a hierarchy.",
            description = "The code never changes. The body can leave it out or give the stored one. Another code "
                    + "fails with 400 and `invalid.value_fixed`. With `source` and `externalId`, the hierarchy gets "
                    + "that ID of that system. `source` with no `externalId` fails with 400 and "
                    + "`invalid.value_fixed`, because a hierarchy keeps no system that made it.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed hierarchy.")
    @BadRequest
    @NotFound
    @ApiErrors
    public HierarchyRepresentation rename(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {HierarchyRepresentation.class, VersionCheckRepresentation.class})))
            HierarchyRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            Hierarchy stored = service.get(id);
            Checks.fixed("code", stored.code(), body.code());
            Writes.requireNoSource(body.source(), body.externalId());
            Writes.requireRead(body, () -> represent(stored), HierarchyRepresentation.READ_ONLY);
            var hierarchy = represent(externalIds.write(ExternalId.Kind.HIERARCHY, id, body.source(),
                    body.externalId(), () -> service.rename(id, body.name())));
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
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the hierarchy that the caller read.",
                    required = true)
            @QueryParam("version") Long version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/hierarchies/" + id;
    }

    // The IDs of every hierarchy of a list are read with one query.
    private List<HierarchyRepresentation> represent(List<Hierarchy> hierarchies) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.HIERARCHY,
                hierarchies.stream().map(Hierarchy::id).toList());
        return hierarchies.stream().map(hierarchy -> HierarchyRepresentation.of(hierarchy, ids)).toList();
    }

    private HierarchyRepresentation represent(Hierarchy hierarchy) {
        return represent(List.of(hierarchy)).getFirst();
    }

    private long version(String id) {
        return service.get(id).version();
    }
}
