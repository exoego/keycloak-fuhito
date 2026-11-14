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
import net.exoego.keycloak.fuhito.service.ChangeLogService;
import net.exoego.keycloak.fuhito.service.ChangeOrderService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The change orders of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.CHANGE_ORDERS)
public class ChangeOrdersResource {

    /**
     * The resource type of change orders in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_CHANGE_ORDER";

    private final RequestContext context;
    private final ChangeOrderService service;
    private final ChangeLogService changeLog;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param changeLog the reads of the change log of the realm
     */
    public ChangeOrdersResource(RequestContext context, ChangeOrderService service, ChangeLogService changeLog) {
        this.context = context;
        this.service = service;
        this.changeLog = changeLog;
    }

    @GET
    @Path("{id}/changes")
    @Operation(
            operationId = "listChangeOrderChanges",
            summary = "Lists the writes that belong to a change order, oldest first.")
    @APIResponse(responseCode = "200", description = "The entries.")
    @NotFound
    @ApiErrors
    public List<ChangeLogRepresentation> changes(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> changeLog.ofChangeOrder(id, first, max).stream()
                .map(ChangeLogRepresentation::of)
                .toList());
    }

    @GET
    @Operation(
            operationId = "listChangeOrders",
            summary = "Lists the change orders with an effective date in a range, newest effective date first.")
    @APIResponse(responseCode = "200", description = "The orders.")
    @BadRequest
    @ApiErrors
    public List<ChangeOrderRepresentation> list(
            @Parameter(description = "The first effective date to return.", schema = @Schema(format = "date"))
            @QueryParam("from") String from,
            @Parameter(description = "The first effective date not to return.", schema = @Schema(format = "date"))
            @QueryParam("to") String to,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context,
                () -> service.list(from, to, first, max).stream().map(ChangeOrderRepresentation::of).toList());
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getChangeOrder", summary = "Returns one change order.")
    @APIResponse(responseCode = "200", description = "The order.")
    @NotFound
    @ApiErrors
    public ChangeOrderRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> ChangeOrderRepresentation.of(service.get(id)));
    }

    @POST
    @Operation(operationId = "createChangeOrder", summary = "Creates a change order.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new order.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ChangeOrderRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(ChangeOrderRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var order = ChangeOrderRepresentation.of(service.create(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, order.id(), path(order.id()), order);
            return Response.status(Response.Status.CREATED).entity(order).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateChangeOrder", summary = "Replaces the values of a change order.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed order.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ChangeOrderRepresentation update(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            ChangeOrderRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var order = ChangeOrderRepresentation.of(service.update(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), order);
            return order;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteChangeOrder",
            summary = "Deletes a change order.",
            description = "A change order with writes cannot be deleted.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The order was deleted.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/change-orders/" + id;
    }
}
