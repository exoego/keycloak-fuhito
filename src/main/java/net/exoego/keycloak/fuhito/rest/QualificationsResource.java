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
import net.exoego.keycloak.fuhito.service.QualificationService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The qualifications of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.QUALIFICATIONS)
public class QualificationsResource {

    /**
     * The resource type of qualifications in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_QUALIFICATION";

    private final RequestContext context;
    private final QualificationService service;
    private final PersonService people;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param people the operations on people of the realm
     */
    public QualificationsResource(RequestContext context, QualificationService service, PersonService people) {
        this.context = context;
        this.service = service;
        this.people = people;
    }

    @POST
    @Operation(
            operationId = "addQualification",
            summary = "Adds a qualification of a person.",
            description = "The body needs `personId` or `userId`.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new qualification.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = QualificationRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response add(QualificationRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var qualification = QualificationRepresentation.of(service.add(
                    body.toValues(Writes.personId(context, people, "personId", body.personId(), body.userId()))));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, qualification.id(), path(qualification.id()),
                    qualification);
            return Response.status(Response.Status.CREATED).entity(qualification).build();
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getQualification", summary = "Returns one qualification.")
    @APIResponse(responseCode = "200", description = "The qualification.")
    @NotFound
    @ApiErrors
    public QualificationRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> QualificationRepresentation.of(service.get(id)));
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "replaceQualification",
            summary = "Replaces the kind, the number, the dates, and the attributes of a qualification.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed qualification.")
    @BadRequest
    @NotFound
    @ApiErrors
    public QualificationRepresentation replace(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            QualificationRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var qualification = QualificationRepresentation.of(service.replace(id, body.toValues(null)));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), qualification);
            return qualification;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteQualification", summary = "Deletes a qualification.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The qualification was deleted.")
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
        return "fuhito/qualifications/" + id;
    }
}
