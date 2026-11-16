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
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.PersonService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
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
    @Operation(
            operationId = "listPeople",
            summary = "Lists the people of the realm, ordered by ID.",
            description = "To read every person, follow the `Link` header of each response. A late page then "
                    + "costs as much as the first one. With `first`, the database counts past the skipped "
                    + "people.")
    @APIResponse(
            responseCode = "200",
            description = "The people.",
            headers = @Header(
                    name = "Link",
                    description = "The URL of the next page, with `rel=\"next\"`. It is there when the page is "
                            + "full. A full page can be the last one, and the next page is then empty.",
                    schema = @Schema(type = SchemaType.STRING)),
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = PersonRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response list(
            @Parameter(description = "The ID of a Keycloak user, to return only the person that is linked to it.")
            @QueryParam("userId") String userId,
            @Parameter(description = "A text that the name, the phonetic name, or a name in another language has, in "
                    + "any case. For a person with a Keycloak user, also the first name, the last name, the username, "
                    + "or the email of the user.")
            @QueryParam("q") String q,
            @Parameter(description = "A staff number that an employment of the person has.")
            @QueryParam("staffNumber") String staffNumber,
            @Parameter(
                    description = "The number of entries to skip. It cannot be used with `after`.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max,
            @Parameter(description = "The cursor from the `Link` header of the page before. It is the name of its format "
                    + "and the values of the last row of that page.")
            @QueryParam("after") String after) {
        context.requireRead();
        return Calls.run(context, () -> Pages.of(context,
                service.list(userId, q, staffNumber, first, max, after).stream().map(PersonRepresentation::of)
                        .toList(),
                Page.of(first, max).max(), person -> Cursor.encode(Cursor.PEOPLE, person.id())));
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
        return Calls.once(context, body, Response.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            var person = PersonRepresentation.of(service.create(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, person.id(), path(person.id()), person);
            return Response.status(Response.Status.CREATED).entity(person).build();
        }));
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
        return Calls.tagged(context, () -> PersonRepresentation.of(service.get(id)));
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
        return Calls.guarded(context, () -> state(id), () -> {
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
        return Calls.guardedRemoval(context, () -> state(id), () -> {
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
    @Path("{id}/unit-roles")
    @Operation(operationId = "listPersonUnitRoles", summary = "Lists the roles over org units of a person on a date.")
    @APIResponse(responseCode = "200", description = "The roles, in the order of their start.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<UnitRoleRepresentation> unitRoles(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> reads.unitRoles()
                .ofPerson(service.get(id).person().id(), asOf, Page.of(first, max)).stream()
                .map(UnitRoleRepresentation::of)
                .toList());
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
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> reads.reportingLines()
                .reportsOfPerson(service.get(id).person().id(), asOf, Page.of(first, max)).stream()
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
            @PathParam("id") String id,
            @Parameter(
                    description = """
                            True to also empty the first and the last name of the linked Keycloak user. \
                            It also needs the permission of Keycloak to manage that user. \
                            The username, the email, and the attributes stay. \
                            False leaves the Keycloak user as it is, so responses still show its name. \
                            A read-only user, for example from a user store, cannot be changed. \
                            The masking then fails with 409 and `person.keycloak_name_read_only`, \
                            and nothing is written.""",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("clearKeycloakName") boolean clearKeycloakName) {
        context.requireWrite();
        return Calls.guarded(context, () -> state(id), () -> data.mask(service.get(id).person(), clearKeycloakName));
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
        return Calls.guardedRemoval(context, () -> state(id), () -> {
            data.erase(service.get(id).person().id());
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/people/" + id;
    }

    // The whole stored state of one resource, which its ETag covers.
    private Object state(String id) {
        return PersonRepresentation.of(service.get(id));
    }
}
