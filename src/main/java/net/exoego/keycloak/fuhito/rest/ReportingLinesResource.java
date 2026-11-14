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
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The reporting lines of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.REPORTING_LINES)
public class ReportingLinesResource {

    /**
     * The resource type of reporting lines in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_REPORTING_LINE";

    private final RequestContext context;
    private final ReportingLineService service;
    private final PersonService people;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param people the operations on people of the realm
     */
    public ReportingLinesResource(RequestContext context, ReportingLineService service, PersonService people) {
        this.context = context;
        this.service = service;
        this.people = people;
    }

    @POST
    @Operation(operationId = "addReportingLine", summary = "Adds a reporting line from an assignment to a manager.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new line.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ReportingLineRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response add(ReportingLineRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var line = ReportingLineRepresentation.of(service.add(body.assignmentId(),
                    Writes.personId(context, people, "managerPersonId", body.managerPersonId(),
                            body.managerUserId()),
                    body.kind(), body.validFrom(), body.validTo()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, line.id(), path(line.id()), line);
            return Response.status(Response.Status.CREATED).entity(line).build();
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getReportingLine", summary = "Returns one reporting line.")
    @APIResponse(responseCode = "200", description = "The line.")
    @NotFound
    @ApiErrors
    public ReportingLineRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> ReportingLineRepresentation.of(service.get(id)));
    }

    @PUT
    @Path("{id}/end")
    @Operation(operationId = "endReportingLine", summary = "Ends a reporting line earlier.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed line.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ReportingLineRepresentation end(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var line = ReportingLineRepresentation.of(service.end(id, body.validTo()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/end", line);
            return line;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteReportingLine",
            summary = "Deletes a reporting line, as if it had never been stored.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The line was deleted.")
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
        return "fuhito/reporting-lines/" + id;
    }
}
