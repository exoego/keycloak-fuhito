package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * A shortcut for the common case of a person who joins one org unit.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.ASSIGNMENTS)
public class StaffResource {

    private final RequestContext context;
    private final EmploymentService employments;
    private final AssignmentService assignments;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     */
    public StaffResource(RequestContext context, EmploymentService employments, AssignmentService assignments) {
        this.context = context;
        this.employments = employments;
        this.assignments = assignments;
    }

    /**
     * Starts an employment and adds its first assignment in one transaction.
     * When either part fails, nothing is written.
     *
     * @param body the user, the org unit, the start date, and optional values
     * @return 201 with the new employment and the new assignment
     */
    @POST
    @Operation(operationId = "addStaff", summary = "Starts an employment and adds its first assignment in one call.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new employment and the new assignment.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = StaffRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response add(StaffRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            // The org unit is checked first, so that a request without it does not get as far as the employment.
            Checks.required("orgUnitId", body.orgUnitId());
            var employment = EmploymentRepresentation.of(employments.start(body.toNewEmployment()));
            var assignment = AssignmentRepresentation.of(assignments.add(body.toAssignment(employment.id())));
            context.recordChange(Change.CREATE, EmploymentsResource.RESOURCE_TYPE, employment.id(),
                    "fuhito/employments/" + employment.id(), employment);
            context.recordChange(Change.CREATE, AssignmentsResource.RESOURCE_TYPE, assignment.id(),
                    "fuhito/assignments/" + assignment.id(), assignment);
            return Response.status(Response.Status.CREATED)
                    .entity(StaffRepresentation.of(employment, assignment))
                    .build();
        });
    }
}
