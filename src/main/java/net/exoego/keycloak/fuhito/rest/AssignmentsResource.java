package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The assignments of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.ASSIGNMENTS)
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
    @Operation(operationId = "addAssignment", summary = "Adds an assignment of an employment to an org unit.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new assignment.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AssignmentRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response add(AssignmentRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.add(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, assignment.id(), path(assignment.id()), assignment);
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
    @Operation(operationId = "getAssignment", summary = "Returns an assignment with the details of every period.")
    @APIResponse(responseCode = "200", description = "The assignment.")
    @NotFound
    @ApiErrors
    public AssignmentRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
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
    @Operation(
            operationId = "changeAssignmentDetails",
            summary = "Replaces the details of an assignment from the `validFrom` date of the body.",
            description = "The employment and the org unit do not change.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The assignment with the details of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public AssignmentRepresentation changeDetails(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            AssignmentRepresentation.Details body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.changeDetails(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/details", assignment);
            return assignment;
        });
    }

    /**
     * Sets the end of an assignment. Its history stays readable. The end can move earlier or later.
     *
     * @param id the ID
     * @param body the first date on which the assignment no longer holds
     * @return the assignment with the details of every period
     */
    @PUT
    @Path("{id}/end")
    @Operation(
            operationId = "endAssignment",
            summary = "Sets the end of an assignment.",
            description = "Its history stays readable. The end can move earlier or later.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The assignment with the details of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public AssignmentRepresentation end(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.end(id, body.validTo()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/end", assignment);
            return assignment;
        });
    }

    /**
     * Removes the end of an assignment. The assignment then lasts as long as its employment.
     *
     * @param id the ID
     * @return the assignment with the details of every period
     */
    @DELETE
    @Path("{id}/end")
    @Operation(
            operationId = "removeAssignmentEnd",
            summary = "Removes the end of an assignment.",
            description = "The assignment then lasts as long as its employment.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The assignment with the details of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public AssignmentRepresentation removeEnd(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            var assignment = AssignmentRepresentation.of(service.removeEnd(id));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/end", assignment);
            return assignment;
        });
    }

    private static String path(String id) {
        return "fuhito/assignments/" + id;
    }
}
