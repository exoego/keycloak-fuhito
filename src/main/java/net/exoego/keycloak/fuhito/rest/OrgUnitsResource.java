package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
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
import net.exoego.keycloak.fuhito.service.OrgUnitService;

/**
 * The org units of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrgUnitsResource {

    private final RequestContext context;
    private final OrgUnitService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public OrgUnitsResource(RequestContext context, OrgUnitService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Creates an org unit that starts on a date.
     *
     * @param body the values of the new unit
     * @return 201 with the unit as it is on its first date
     */
    @POST
    public Response create(OrgUnitRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var state = service.create(body.toNewOrgUnit());
            return Response.status(Response.Status.CREATED).entity(OrgUnitRepresentation.of(state)).build();
        });
    }

    /**
     * Replaces the details of an org unit from the {@code validFrom} date of the body.
     * The details are the names, the unit type, the display order, and the attributes.
     * The code, the parent, and the external ID do not change here.
     *
     * @param id the ID
     * @param body the new details and their first date
     * @return the unit as it is on that date
     */
    @PUT
    @Path("{id}")
    public OrgUnitRepresentation changeDetails(@PathParam("id") String id, OrgUnitRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            return OrgUnitRepresentation.of(service.changeDetails(id, body.toChange()));
        });
    }

    /**
     * Gives an org unit a new parent in the default hierarchy from a date.
     *
     * @param id the ID
     * @param body the new parent and the first date under it. No parent makes the unit a root.
     * @return the unit as it is on that date
     */
    @PUT
    @Path("{id}/parent")
    public OrgUnitRepresentation move(@PathParam("id") String id, ParentChangeRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            return OrgUnitRepresentation.of(service.move(id, body.parentId(), body.validFrom()));
        });
    }

    /**
     * Ends an org unit. Its history stays readable for earlier dates.
     *
     * @param id the ID
     * @param body the first date on which the unit no longer exists
     * @return the unit as it is on its last date
     */
    @PUT
    @Path("{id}/end")
    public OrgUnitRepresentation end(@PathParam("id") String id, EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            return OrgUnitRepresentation.of(service.end(id, body.validTo()));
        });
    }

    /**
     * Returns an org unit as it is on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @return the unit on the date
     */
    @GET
    @Path("{id}")
    public OrgUnitRepresentation get(@PathParam("id") String id, @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> OrgUnitRepresentation.of(service.get(id, asOf)));
    }
}
