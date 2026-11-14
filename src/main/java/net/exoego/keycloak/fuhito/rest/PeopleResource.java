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
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.PersonService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The people of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.PEOPLE)
public class PeopleResource {

    /**
     * The resource type of people in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_PERSON";

    private final RequestContext context;
    private final PersonService service;
    private final UsersResource.Reads reads;
    private final PersonData data;

    PeopleResource(RequestContext context, PersonService service, UsersResource.Reads reads, PersonData data) {
        this.context = context;
        this.service = service;
        this.reads = reads;
        this.data = data;
    }

    @GET
    @Operation(operationId = "listPeople", summary = "Lists the people of the realm, ordered by ID.")
    @APIResponse(responseCode = "200", description = "The people.")
    @ApiErrors
    public List<PersonRepresentation> list(
            @Parameter(description = "The ID of a Keycloak user, to return only the person that is linked to it.")
            @QueryParam("userId") String userId,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context,
                () -> service.list(userId, first, max).stream().map(PersonRepresentation::of).toList());
    }

    @POST
    @Operation(
            operationId = "createPerson",
            summary = "Creates a person.",
            description = "The body needs `name`, or `userId` of a Keycloak user that has a name.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new person.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = PersonRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(PersonRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var person = PersonRepresentation.of(service.create(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, person.id(), path(person.id()), person);
            return Response.status(Response.Status.CREATED).entity(person).build();
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getPerson", summary = "Returns one person.")
    @APIResponse(responseCode = "200", description = "The person.")
    @NotFound
    @ApiErrors
    public PersonRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> PersonRepresentation.of(service.get(id)));
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "replacePerson",
            summary = "Replaces the names, the attributes, and the link to a Keycloak user.",
            description = "A body with no `userId` removes the link. The external ID and its source do not change.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed person.")
    @BadRequest
    @NotFound
    @ApiErrors
    public PersonRepresentation replace(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            PersonRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var person = PersonRepresentation.of(service.replace(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), person);
            return person;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deletePerson", summary = "Deletes a person.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The person was deleted.")
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

    @GET
    @Path("{id}/assignments")
    @Operation(
            operationId = "listPersonAssignments",
            summary = "Lists the assignments of a person that hold on a date.",
            description = "The primary assignment comes first.")
    @APIResponse(responseCode = "200", description = "The assignments of the person on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<MemberRepresentation> assignments(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit of each assignment.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to return each org unit with its path from the root.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("includePath") boolean includePath) {
        context.requireRead();
        return Calls.run(context, () -> reads.membership()
                .assignmentsOfPerson(service.get(id).person().id(), asOf, includePath).stream()
                .map(MemberRepresentation::of)
                .toList());
    }

    @GET
    @Path("{id}/history")
    @Operation(
            operationId = "getPersonHistory",
            summary = "Returns every employment and every assignment of a person, with all their periods.")
    @APIResponse(responseCode = "200", description = "The history of the person.")
    @NotFound
    @ApiErrors
    public UserHistoryRepresentation history(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> UserHistoryRepresentation.of(
                reads.membership().historyOfPerson(service.get(id).person(), reads.employments(),
                        reads.assignments()),
                data.maskedAt(id)));
    }

    @GET
    @Path("{id}/managers")
    @Operation(
            operationId = "listPersonManagers",
            summary = "Lists the managers of a person on a date, for each assignment of the person.",
            description = "An assignment with no stored solid line takes the head of its org unit, or of the "
                    + "nearest unit above.")
    @APIResponse(responseCode = "200", description = "The managers.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<ReportingRepresentation> managers(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> reads.reportingLines()
                .managersOfPerson(service.get(id).person().id(), asOf).stream()
                .map(ReportingRepresentation::of)
                .toList());
    }

    @GET
    @Path("{id}/reports")
    @Operation(operationId = "listPersonReports", summary = "Lists the direct reports of a person on a date.")
    @APIResponse(responseCode = "200", description = "The direct reports.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<ReportingRepresentation> reports(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> reads.reportingLines()
                .reportsOfPerson(service.get(id).person().id(), asOf).stream()
                .map(ReportingRepresentation::of)
                .toList());
    }

    @GET
    @Path("{id}/qualifications")
    @Operation(
            operationId = "listPersonQualifications",
            summary = "Lists the qualifications of a person, oldest first.")
    @APIResponse(responseCode = "200", description = "The qualifications.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<QualificationRepresentation> qualifications(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to return every qualification, expired ones and future ones included.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("all") boolean all) {
        context.requireRead();
        return Calls.run(context, () -> reads.qualifications()
                .ofPerson(service.get(id).person().id(), asOf, all).stream()
                .map(QualificationRepresentation::of)
                .toList());
    }

    @POST
    @Path("{id}/mask")
    @Operation(
            operationId = "maskPerson",
            summary = "Masks the personal data of a person who left. It cannot be undone.",
            description = "It clears the names, the staff number, the numbers of qualifications, the external IDs, "
                    + "and the free attributes, also in the change log. Every record keeps its ID, its org unit, "
                    + "its job title, its occupation, and its dates.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The person and the time of the masking.")
    @BadRequest
    @NotFound
    @ApiErrors
    public UsersResource.MaskRepresentation mask(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> data.mask(service.get(id).person()));
    }

    @DELETE
    @Path("{id}/data")
    @Operation(
            operationId = "erasePersonData",
            summary = "Erases everything about one person for good.",
            description = "These are the person record, the employments with their terms and assignments, the "
                    + "qualifications, the reporting lines of the person as a report and as a manager, and the "
                    + "change log entries about them. This endpoint is for a request to delete personal data.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The data was erased.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response erase(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            data.erase(service.get(id).person().id());
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/people/" + id;
    }
}
