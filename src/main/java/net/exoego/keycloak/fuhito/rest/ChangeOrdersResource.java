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

/**
 * The change orders of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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

    /**
     * Lists the writes that belong to a change order, oldest first.
     * A write belongs to a change order when its request had the query parameter {@code changeOrder}.
     *
     * @param id the ID of the change order
     * @param first the number of entries to skip. The default is 0.
     * @param max the largest number of entries to return. The default is 100.
     * @return the entries
     */
    @GET
    @Path("{id}/changes")
    public List<ChangeLogRepresentation> changes(@PathParam("id") String id, @QueryParam("first") Integer first,
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> changeLog.ofChangeOrder(id, first, max).stream()
                .map(ChangeLogRepresentation::of)
                .toList());
    }

    /**
     * Lists the change orders with an effective date in a range, newest effective date first.
     *
     * @param from the first effective date to return, as {@code YYYY-MM-DD}
     * @param to the first effective date not to return, as {@code YYYY-MM-DD}
     * @return the orders
     */
    @GET
    public List<ChangeOrderRepresentation> list(@QueryParam("from") String from, @QueryParam("to") String to) {
        context.requireRead();
        return Calls.run(context,
                () -> service.list(from, to).stream().map(ChangeOrderRepresentation::of).toList());
    }

    /**
     * Returns one change order.
     *
     * @param id the ID
     * @return the order
     */
    @GET
    @Path("{id}")
    public ChangeOrderRepresentation get(@PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> ChangeOrderRepresentation.of(service.get(id)));
    }

    /**
     * Creates a change order.
     *
     * @param body the values of the new order
     * @return 201 with the new order
     */
    @POST
    public Response create(ChangeOrderRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var order = ChangeOrderRepresentation.of(service.create(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, order.id(), path(order.id()), order);
            return Response.status(Response.Status.CREATED).entity(order).build();
        });
    }

    /**
     * Replaces the values of a change order.
     *
     * @param id the ID
     * @param body the new values
     * @return the changed order
     */
    @PUT
    @Path("{id}")
    public ChangeOrderRepresentation update(@PathParam("id") String id, ChangeOrderRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var order = ChangeOrderRepresentation.of(service.update(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), order);
            return order;
        });
    }

    /**
     * Deletes a change order. A change order with writes cannot be deleted.
     *
     * @param id the ID
     * @return 204
     */
    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
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
