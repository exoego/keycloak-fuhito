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
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.RefItemService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The reference items of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.REF_ITEMS)
public class RefItemsResource {

    /**
     * The resource type of reference items in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_REF_ITEM";

    private final RequestContext context;
    private final RefItemService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public RefItemsResource(RequestContext context, RefItemService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(
            operationId = "listRefItems",
            summary = "Lists the items of a kind, ordered by display order and then by code.")
    @APIResponse(responseCode = "200", description = "The items.")
    @BadRequest
    @ApiErrors
    public List<RefItemRepresentation> list(
            @Parameter(required = true, description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added.")
            @QueryParam("kind") String kind,
            @Parameter(description = "True to also return inactive items.", schema = @Schema(defaultValue = "false"))
            @QueryParam("includeInactive") boolean includeInactive,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireReadStructure();
        return Calls.run(context, () -> service.list(kind, includeInactive, Page.of(first, max)).stream()
                .map(RefItemRepresentation::of)
                .toList());
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countRefItems",
            summary = "Counts the items that `GET /ref-items` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of items on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(required = true, description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added.")
            @QueryParam("kind") String kind,
            @Parameter(description = "True to also count inactive items.", schema = @Schema(defaultValue = "false"))
            @QueryParam("includeInactive") boolean includeInactive) {
        context.requireReadStructure();
        return Calls.run(context, () -> new CountRepresentation(service.count(kind, includeInactive)));
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getRefItem", summary = "Returns one item.")
    @APIResponse(responseCode = "200", description = "The item.")
    @NotFound
    @ApiErrors
    public RefItemRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> RefItemRepresentation.of(service.get(id)));
    }

    @POST
    @Operation(
            operationId = "createRefItem",
            summary = "Creates an item.",
            description = "The body needs `kind`, `code`, and `name`.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new item.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RefItemRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(RefItemRepresentation body) {
        context.requireWrite();
        return Calls.once(context, body, Response.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            var item = RefItemRepresentation.of(service.create(body.kind(), body.code(), body.name(),
                    body.names(), body.displayOrder(), body.attributes(), body.marks()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, item.id(), path(item.id()), item);
            return Response.status(Response.Status.CREATED).entity(item).build();
        }));
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "updateRefItem",
            summary = "Replaces the changeable values of an item.",
            description = "The kind and the code never change. The body can leave them out.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed item.")
    @BadRequest
    @NotFound
    @ApiErrors
    public RefItemRepresentation update(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {RefItemRepresentation.class, VersionCheckRepresentation.class})))
            RefItemRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            var item = RefItemRepresentation.of(service.update(id, body.name(), body.names(), body.displayOrder(),
                    body.attributes(), body.active(), body.marks()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), item);
            return item;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteRefItem", summary = "Deletes an item.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The item was deleted.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the item that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/ref-items/" + id;
    }

    private long version(String id) {
        return service.get(id).version();
    }
}
