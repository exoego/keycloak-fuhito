package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;

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
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.RefItemService;

/**
 * The reference items of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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

    /**
     * Lists the items of a kind.
     *
     * @param kind the kind
     * @param includeInactive true to also return inactive items
     * @return the items, ordered by display order and then by code
     */
    @GET
    public List<RefItemRepresentation> list(@QueryParam("kind") String kind,
            @QueryParam("includeInactive") boolean includeInactive) {
        context.requireRead();
        return Calls.run(context, () -> service.list(parseKind(kind), includeInactive).stream()
                .map(RefItemRepresentation::of)
                .toList());
    }

    /**
     * Returns one item.
     *
     * @param id the ID
     * @return the item
     */
    @GET
    @Path("{id}")
    public RefItemRepresentation get(@PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> RefItemRepresentation.of(service.get(id)));
    }

    /**
     * Creates an item.
     *
     * @param body the kind, the code, and the other values
     * @return 201 with the new item
     */
    @POST
    public Response create(RefItemRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var item = RefItemRepresentation.of(service.create(parseKind(body.kind()), body.code(), body.name(),
                    body.names(), body.displayOrder(), body.attributes()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, path(item.id()), item);
            return Response.status(Response.Status.CREATED).entity(item).build();
        });
    }

    /**
     * Replaces the changeable values of an item. The kind and the code never change.
     *
     * @param id the ID
     * @param body the new values
     * @return the changed item
     */
    @PUT
    @Path("{id}")
    public RefItemRepresentation update(@PathParam("id") String id, RefItemRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var item = RefItemRepresentation.of(service.update(id, body.name(), body.names(), body.displayOrder(),
                    body.attributes(), body.active()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, path(id), item);
            return item;
        });
    }

    /**
     * Deletes an item.
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
            context.recordChange(Change.DELETE, RESOURCE_TYPE, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/ref-items/" + id;
    }

    private static RefKind parseKind(String kind) {
        Checks.required("kind", kind);
        try {
            return RefKind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw FuhitoException.invalid("invalid.kind", Map.of("value", kind));
        }
    }
}
