package net.exoego.keycloak.fuhito.rest;

import java.util.List;

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
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.MemberQuery;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrgUnitState;

/**
 * The org units of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrgUnitsResource {

    /**
     * The resource type of org units in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_ORG_UNIT";

    private final RequestContext context;
    private final OrgUnitService service;
    private final MembershipService membership;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param membership the reads of members on the realm of the request
     */
    public OrgUnitsResource(RequestContext context, OrgUnitService service, MembershipService membership) {
        this.context = context;
        this.service = service;
        this.membership = membership;
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
            var unit = OrgUnitRepresentation.of(service.create(body.toNewOrgUnit()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, path(unit.id()), unit);
            return Response.status(Response.Status.CREATED).entity(unit).build();
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
            return updated(path(id), service.changeDetails(id, body.toChange()));
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
            return updated(path(id) + "/parent", service.move(id, body.parentId(), body.validFrom()));
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
            return updated(path(id) + "/end", service.end(id, body.validTo()));
        });
    }

    /**
     * Lists the org units that exist on a date. Each parent comes before its children.
     *
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @param roots true to return only the units with no parent
     * @return the units on the date
     */
    @GET
    public List<OrgUnitRepresentation> list(@QueryParam("asOf") String asOf, @QueryParam("roots") boolean roots) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.list(asOf, roots)));
    }

    /**
     * Lists the direct children of an org unit on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @return the children on the date
     */
    @GET
    @Path("{id}/children")
    public List<OrgUnitRepresentation> children(@PathParam("id") String id, @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.children(id, asOf)));
    }

    /**
     * Lists an org unit and everything below it on a date. Each parent comes before its children.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @return the unit and its descendants on the date
     */
    @GET
    @Path("{id}/subtree")
    public List<OrgUnitRepresentation> subtree(@PathParam("id") String id, @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.subtree(id, asOf)));
    }

    /**
     * Lists the units from the root down to an org unit on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @return the path, with the root first and the unit last
     */
    @GET
    @Path("{id}/path")
    public List<OrgUnitRepresentation> path(@PathParam("id") String id, @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.path(id, asOf)));
    }

    /**
     * Lists the assignments to an org unit that hold on a date.
     *
     * @param id the ID
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @param includeDescendants true to also list the members of every unit below
     * @param occupation the code of an occupation to keep
     * @param jobTitle the code of a job title to keep
     * @param status an employment status to keep. It is {@code ACTIVE}, {@code ON_LEAVE}, or {@code SUSPENDED}.
     * @param first the number of members to skip. The default is 0.
     * @param max the largest number of members to return. The default is 100.
     * @return the members on the date
     */
    @GET
    @Path("{id}/members")
    public List<MemberRepresentation> members(@PathParam("id") String id, @QueryParam("asOf") String asOf,
            @QueryParam("includeDescendants") boolean includeDescendants,
            @QueryParam("occupation") String occupation, @QueryParam("jobTitle") String jobTitle,
            @QueryParam("status") String status, @QueryParam("first") Integer first,
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> membership.members(id,
                        new MemberQuery(asOf, includeDescendants, occupation, jobTitle, status, first, max)).stream()
                .map(MemberRepresentation::of)
                .toList());
    }

    /**
     * Returns everything that is stored about an org unit over time.
     *
     * @param id the ID
     * @return the details of every period and the parent of every period
     */
    @GET
    @Path("{id}/history")
    public OrgUnitHistoryRepresentation history(@PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> OrgUnitHistoryRepresentation.of(service.history(id)));
    }

    private OrgUnitRepresentation updated(String path, OrgUnitState state) {
        var unit = OrgUnitRepresentation.of(state);
        context.recordChange(Change.UPDATE, RESOURCE_TYPE, path, unit);
        return unit;
    }

    private static String path(String id) {
        return "fuhito/org-units/" + id;
    }

    private static List<OrgUnitRepresentation> represent(List<OrgUnitState> states) {
        return states.stream().map(OrgUnitRepresentation::of).toList();
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
