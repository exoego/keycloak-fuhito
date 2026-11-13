package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.AssignmentService;

/**
 * The assignments of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AssignmentsResource {

    /**
     * The resource type of assignments in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_ASSIGNMENT";

    private final RequestContext context;
    private final AssignmentService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public AssignmentsResource(RequestContext context, AssignmentService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Adds an assignment of an employment to an org unit.
     *
     * @param body the employment, the org unit, the dates, and the first details
     * @return 201 with the new assignment
     */
    @POST
    public Response add(AssignmentRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.add(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, path(assignment.id()), assignment);
            return Response.status(Response.Status.CREATED).entity(assignment).build();
        });
    }

    /**
     * Returns an assignment with the details of every period.
     *
     * @param id the ID
     * @return the assignment
     */
    @GET
    @Path("{id}")
    public AssignmentRepresentation get(@PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> AssignmentRepresentation.of(service.get(id)));
    }

    /**
     * Replaces the details of an assignment from the {@code validFrom} date of the body.
     * The employment and the org unit do not change.
     *
     * @param id the ID
     * @param body the new details and their first date
     * @return the assignment with the details of every period
     */
    @PUT
    @Path("{id}/details")
    public AssignmentRepresentation changeDetails(@PathParam("id") String id,
            AssignmentRepresentation.Details body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.changeDetails(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, path(id) + "/details", assignment);
            return assignment;
        });
    }

    /**
     * Ends an assignment. Its history stays readable.
     *
     * @param id the ID
     * @param body the first date on which the assignment no longer holds
     * @return the assignment with the details of every period
     */
    @PUT
    @Path("{id}/end")
    public AssignmentRepresentation end(@PathParam("id") String id, EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.end(id, body.validTo()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, path(id) + "/end", assignment);
            return assignment;
        });
    }

    private static String path(String id) {
        return "fuhito/assignments/" + id;
    }
}
