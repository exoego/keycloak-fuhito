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

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public PeopleResource(RequestContext context, PersonService service) {
        this.context = context;
        this.service = service;
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

    private static String path(String id) {
        return "fuhito/people/" + id;
    }
}
