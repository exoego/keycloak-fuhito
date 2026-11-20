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
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
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
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param people the operations on people of the realm
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public ReportingLinesResource(RequestContext context, ReportingLineService service, PersonService people,
            ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.people = people;
        this.externalIds = externalIds;
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
            Calls.body(body);
            Writes.requireNoSource(body.source(), body.externalId());
            String manager = Writes.personId(context, people, "managerPersonId", body.managerPersonId(),
                    "managerUserId", body.managerUserId());
            var line = represent(externalIds.create(ExternalId.Kind.REPORTING_LINE, body.source(), body.externalId(),
                    () -> service.add(body.assignmentId(), manager, body.kind(), body.validFrom(), body.validTo()),
                    ReportingLine::id));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, line.id(), path(line.id()), line);
            return Response.status(Response.Status.CREATED).entity(line).build();
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getReportingLine", summary = "Returns one reporting line.")
    @APIResponse(responseCode = "200", description = "The line.")
    @NotFound
    @BadRequest
    @ApiErrors
    public ReportingLineRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.get(id)));
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
        return Calls.guarded(context, body, () -> version(id), () -> {
            var line = represent(service.end(id, body.validTo()));
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
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the line that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/reporting-lines/" + id;
    }

    // The IDs of every line of a list are read with one query.
    static List<ReportingLineRepresentation> represent(List<ReportingLine> lines, ExternalIdService externalIds) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.REPORTING_LINE,
                lines.stream().map(ReportingLine::id).toList());
        return lines.stream().map(line -> ReportingLineRepresentation.of(line, ids)).toList();
    }

    private ReportingLineRepresentation represent(ReportingLine line) {
        return represent(List.of(line), externalIds).getFirst();
    }

    private long version(String id) {
        return service.get(id).version();
    }
}
