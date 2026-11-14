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
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.TermsChange;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The employments of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.EMPLOYMENTS)
public class EmploymentsResource {

    /**
     * The resource type of employments in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_EMPLOYMENT";

    private final RequestContext context;
    private final EmploymentService service;
    private final PersonService people;
    private final AssignmentService assignments;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param people the operations on people of the realm
     * @param assignments the reads of assignments of the realm
     */
    public EmploymentsResource(RequestContext context, EmploymentService service, PersonService people,
            AssignmentService assignments) {
        this.context = context;
        this.service = service;
        this.people = people;
        this.assignments = assignments;
    }

    @POST
    @Operation(operationId = "startEmployment", summary = "Starts an employment of a user.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new employment.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EmploymentRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response start(EmploymentRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var employment = EmploymentRepresentation.of(service.start(
                    body.toNewEmployment(Writes.personId(context, people, "personId", body.personId(), body.userId()))));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, employment.id(), "fuhito/employments/" + employment.id(),
                    employment);
            return Response.status(Response.Status.CREATED).entity(employment).build();
        });
    }

    @PUT
    @Path("{id}/terms")
    @Operation(
            operationId = "changeEmploymentTerms",
            summary = "Replaces the terms of an employment from the `validFrom` date of the body.",
            description = "The terms are the employment type, the grade, the status, the work percentage, and the "
                    + "attributes.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation changeTerms(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EmploymentRepresentation.Terms body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var employment = EmploymentRepresentation.of(service.changeTerms(id, new TermsChange(body.validFrom(),
                    body.employmentType(), body.grade(), body.status(), body.workPercent(), body.attributes())));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/terms", employment);
            return employment;
        });
    }

    @PUT
    @Path("{id}/end")
    @Operation(
            operationId = "endEmployment",
            summary = "Sets the end of an employment.",
            description = "An earlier end is how a person leaves. It also ends the terms and the assignments on "
                    + "that date. A later end extends a fixed-term employment.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation end(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var employment = EmploymentRepresentation.of(service.end(id, body.validTo(), body.endReason()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/end", employment);
            return employment;
        });
    }

    @POST
    @Path("{id}/transfer")
    @Operation(
            operationId = "transferEmployment",
            summary = "Moves a person to another employer on a date, in one write.",
            description = "The employment ends on the date. A new employment at the new employer starts on the "
                    + "same date. It gets the terms, the assignments, and the reporting lines of the old one from "
                    + "the date on, also the ones that were stored for a later date. It names the old "
                    + "employment in `continuesEmploymentId`. A failure writes nothing.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The two employments, and what the new one got.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = TransferRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response transfer(
            @Parameter(description = "The ID of the employment at the old employer.")
            @PathParam("id") String id,
            TransferRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            EmploymentService.Transfer transfer = service.transfer(id, body.toRequest());
            var ended = EmploymentRepresentation.of(transfer.ended());
            var started = EmploymentRepresentation.of(transfer.started());
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/transfer", ended);
            context.recordChange(Change.CREATE, RESOURCE_TYPE, started.id(), "fuhito/employments/" + started.id(),
                    started);
            var newAssignments = transfer.assignmentIds().stream()
                    .map(assignmentId -> AssignmentRepresentation.of(assignments.get(assignmentId)))
                    .toList();
            newAssignments.forEach(assignment -> context.recordChange(Change.CREATE,
                    AssignmentsResource.RESOURCE_TYPE, assignment.id(), "fuhito/assignments/" + assignment.id(),
                    assignment));
            var newLines = transfer.reportingLines().stream().map(ReportingLineRepresentation::of).toList();
            newLines.forEach(line -> context.recordChange(Change.CREATE, ReportingLinesResource.RESOURCE_TYPE,
                    line.id(), "fuhito/reporting-lines/" + line.id(), line));
            return Response.status(Response.Status.CREATED)
                    .entity(TransferRepresentation.of(ended, started, newAssignments, newLines))
                    .build();
        });
    }

    @DELETE
    @Path("{id}/end")
    @Operation(
            operationId = "removeEmploymentEnd",
            summary = "Removes the end of an employment, for example to take back a resignation.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation removeEnd(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            var employment = EmploymentRepresentation.of(service.removeEnd(id));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/end", employment);
            return employment;
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getEmployment", summary = "Returns an employment with the terms of every period.")
    @APIResponse(responseCode = "200", description = "The employment.")
    @NotFound
    @ApiErrors
    public EmploymentRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> EmploymentRepresentation.of(service.get(id)));
    }
}
