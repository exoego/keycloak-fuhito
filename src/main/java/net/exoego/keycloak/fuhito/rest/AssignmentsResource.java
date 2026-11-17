package net.exoego.keycloak.fuhito.rest;

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
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.RemovalService;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
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
    private final MembershipService members;
    private final RemovalService removals;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param members the reads of assignments in the shape of members
     * @param removals the removal of resources that were made by mistake
     */
    public AssignmentsResource(RequestContext context, AssignmentService service, MembershipService members,
            RemovalService removals) {
        this.removals = removals;
        this.members = members;
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(
            operationId = "listAssignments",
            summary = "Lists the assignments of a date across the realm, ordered by ID, in the shape of members.",
            description = "To read every assignment, follow the `Link` header of each response.")
    @APIResponse(
            responseCode = "200",
            description = "The assignments of the date.",
            headers = {
                @Header(
                        name = "Link",
                        description = "The URL of the next page, with `rel=\"next\"`. It is there when the page "
                                + "is full. A full page can be the last one, and the next page is then empty.",
                        schema = @Schema(type = SchemaType.STRING)),
                @Header(
                        name = "X-Total-Count",
                        description = "The number of entries on all pages. It is there with `count=true`.",
                        schema = @Schema(type = SchemaType.INTEGER))},
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = MemberRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response list(
            @Parameter(description = "The ID of an org unit to keep. The units below do not count.")
            @QueryParam("orgUnitId") String orgUnitId,
            @Parameter(description = "The code of a job title to keep.")
            @QueryParam("jobTitle") String jobTitle,
            @Parameter(description = "The code of an occupation to keep.")
            @QueryParam("occupation") String occupation,
            @Parameter(description = "The code of an assignment kind to keep.")
            @QueryParam("kind") String kind,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The number of entries to skip. It cannot be used with `after`.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max,
            @Parameter(description = "The cursor from the `Link` header of the page before.")
            @QueryParam("after") String after,
            @Parameter(
                    description = "True to get the number of entries on all pages in the header `X-Total-Count`.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("count") boolean count) {
        context.requireRead();
        return Calls.run(context, () -> {
            Cursor.requireAlone(after, first);
            MembershipService.Search search = new MembershipService.Search(orgUnitId, jobTitle, occupation, kind,
                    asOf);
            return Pages.of(context, members.search(search, Page.of(first, max), after).stream()
                            .map(MemberRepresentation::of).toList(), Page.of(first, max).max(),
                    member -> Cursor.encode(Cursor.ASSIGNMENTS, member.assignmentId()),
                    count ? members.count(search) : null);
        });
    }

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
        return Calls.once(context, body, Response.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.add(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, assignment.id(), path(assignment.id()), assignment);
            return Response.status(Response.Status.CREATED).entity(assignment).build();
        }));
    }

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
        return Calls.tagged(context, () -> state(id), () -> AssignmentRepresentation.of(service.get(id)));
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteAssignment",
            summary = "Deletes an assignment that nothing refers to, with all its history.",
            description = "It works only when the assignment has no reporting line and no assignment continues it. Every period of it goes. End an assignment that held for a while instead.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The resource is gone.")
    @NotFound
    @ApiErrors
    public Response remove(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.guardedRemoval(context, () -> state(id), () -> {
            removals.remove(ResourceRemoval.Kind.ASSIGNMENT, id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, "fuhito/assignments/" + id, null);
            return Response.noContent().build();
        });
    }

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
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.changeDetails(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/details", assignment);
            return assignment;
        });
    }

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
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            var assignment = AssignmentRepresentation.of(service.end(id, body.validTo()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/end", assignment);
            return assignment;
        });
    }

    @POST
    @Path("{id}/move")
    @Operation(
            operationId = "moveAssignment",
            summary = "Moves a person to another org unit on a date, in one write.",
            description = "The assignment ends on the date. A new assignment of the same employment to the new "
                    + "org unit starts on the same date, and names the old one in `continuesAssignmentId`. It "
                    + "keeps the occupation, the kind, the primary flag, the allocation, and the attributes. It "
                    + "keeps the job title unless the request gives one. The head flag, the display order, the "
                    + "work location, and the cost center belong to the old org unit and are not kept. The "
                    + "reporting lines of the old assignment end with it. A failure writes nothing.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The two assignments.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = MoveRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response move(
            @Parameter(description = "The ID of the assignment to the old org unit.")
            @PathParam("id") String id,
            MoveRepresentation body) {
        context.requireWrite();
        return Calls.once(context, body, Response.class, () -> Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            AssignmentService.Move move = service.move(id, body.toRequest());
            var ended = AssignmentRepresentation.of(move.ended());
            var started = AssignmentRepresentation.of(move.started());
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/move", ended);
            context.recordChange(Change.CREATE, RESOURCE_TYPE, started.id(), path(started.id()), started);
            return Response.status(Response.Status.CREATED).entity(MoveRepresentation.of(ended, started)).build();
        }));
    }

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
        return Calls.guarded(context, () -> state(id), () -> {
            var assignment = AssignmentRepresentation.of(service.removeEnd(id));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/end", assignment);
            return assignment;
        });
    }

    private static String path(String id) {
        return "fuhito/assignments/" + id;
    }

    // The whole stored state of one resource, which its ETag covers.
    private Object state(String id) {
        return AssignmentRepresentation.of(service.get(id)).stored();
    }
}
