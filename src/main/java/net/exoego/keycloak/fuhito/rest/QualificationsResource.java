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
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.QualificationView;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
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
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param people the operations on people of the realm
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public QualificationsResource(RequestContext context, QualificationService service, PersonService people,
            ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.people = people;
        this.externalIds = externalIds;
    }

    @GET
    @Operation(
            operationId = "listQualifications",
            summary = "Lists the qualifications of the realm, ordered by ID.",
            description = "To read every qualification, follow the `Link` header of each response.")
    @APIResponse(
            responseCode = "200",
            description = "The qualifications.",
            headers = {
                @Header(
                        name = "Link",
                        description = "The URL of the next page, with `rel=\"next\"`. It is there when the page "
                                + "is full. A full page can be the last one, and the next page is then empty.",
                        schema = @Schema(type = SchemaType.STRING))},
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = QualificationRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response list(
            @Parameter(description = "The code of a kind of qualification to keep.")
            @QueryParam("kind") String kind,
            @Parameter(
                    description = "A date to keep the qualifications that hold on it. Leave it out for every date.",
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
            @QueryParam("after") String after) {
        context.requireRead();
        return Calls.run(context, () -> {
            Cursor.requireAlone(after, first);
            return Pages.of(context, represent(service.list(kind, asOf, Page.of(first, max), after), externalIds),
                    Page.of(first, max).max(),
                    qualification -> Cursor.encode(Cursor.QUALIFICATIONS, qualification.id()));
        });
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countQualifications",
            summary = "Counts the qualifications that `GET /qualifications` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of qualifications on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(description = "The code of a kind of qualification to keep.")
            @QueryParam("kind") String kind,
            @Parameter(
                    description = "A date to keep the qualifications that hold on it. Leave it out for every date.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(service.count(kind, asOf)));
    }

    @POST
    @Operation(
            operationId = "addQualification",
            summary = "Adds a qualification of a person.",
            description = "The body needs `personId` or `userId`, not both.")
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
            Calls.body(body);
            var qualification = represent(service.add(body.toValues(
                    Writes.personId(context, people, "personId", body.personId(), "userId", body.userId()))));
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
        return Calls.run(context, () -> represent(service.get(id)));
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "replaceQualification",
            summary = "Replaces the kind, the number, the dates, and the attributes of a qualification.",
            description = "The person never changes. The body can leave out `personId` and `userId`, or give the "
                    + "ones of the stored person. Another person fails with 400 and `invalid.value_fixed`.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed qualification.")
    @BadRequest
    @NotFound
    @ApiErrors
    public QualificationRepresentation replace(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {QualificationRepresentation.class, VersionCheckRepresentation.class})))
            QualificationRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            Qualification stored = service.get(id).qualification();
            Checks.fixed("personId", stored.personId(), body.personId());
            Checks.fixed("userId", stored.userId(), body.userId());
            Writes.requireSource(stored.source(), body.source(), body.externalId());
            var qualification = represent(service.replace(id, body.toValues(null)));
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
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the qualification that the caller read.",
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
        return "fuhito/qualifications/" + id;
    }

    // The IDs of every qualification of a list are read with one query.
    static List<QualificationRepresentation> represent(List<QualificationView> views, ExternalIdService externalIds) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.QUALIFICATION,
                views.stream().map(view -> view.qualification().id()).toList());
        return views.stream().map(view -> QualificationRepresentation.of(view, ids)).toList();
    }

    private QualificationRepresentation represent(QualificationView view) {
        return represent(List.of(view), externalIds).getFirst();
    }

    private long version(String id) {
        return service.get(id).qualification().version();
    }
}
