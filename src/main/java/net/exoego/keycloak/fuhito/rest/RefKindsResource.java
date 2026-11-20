package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.RealmKindService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The kinds of reference data of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.REF_ITEMS)
public class RefKindsResource {

    /**
     * The resource type of kinds of reference data in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_REF_KIND";

    private final RequestContext context;
    private final RealmKindService service;
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public RefKindsResource(RequestContext context, RealmKindService service, ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.externalIds = externalIds;
    }

    @GET
    @Operation(
            operationId = "listRefKinds",
            summary = "Lists the kinds of reference data, the fixed ones first, then the ones of the realm.")
    @APIResponse(responseCode = "200", description = "The kinds.")
    @ApiErrors
    public List<RefKindRepresentation> list() {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.list()));
    }

    @POST
    @Operation(
            operationId = "createRefKind",
            summary = "Adds a kind of reference data to the realm, such as a working time system or a collective "
                    + "agreement.",
            description = "The body needs `code`, `primaryLanguage`, and `names`. Its items are reference items with "
                    + "the code as their kind. Attributes of the type `REF_ITEM` can point to it.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new kind.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RefKindRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(RefKindRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Calls.body(body);
            var kind = represent(externalIds.create(ExternalId.Kind.REF_KIND, body.source(), body.externalId(),
                    () -> service.create(body.code(), body.primaryLanguage(), body.names()), RealmKindService.View::id));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, kind.code(), path(kind.code()), kind);
            return Response.status(Response.Status.CREATED).entity(kind).build();
        });
    }

    @PUT
    @Path("{code}")
    @Operation(
            operationId = "renameRefKind",
            summary = "Changes the names of a kind of the realm.",
            description = "The code never changes. The body can leave it out or give the code of the path. Another "
                    + "code fails with 400 and `invalid.value_fixed`. With `source` and `externalId`, the kind gets "
                    + "that ID of that system. `source` with no `externalId` fails with 400 and "
                    + "`invalid.value_fixed`, because a kind keeps no system that made it.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed kind.")
    @BadRequest
    @NotFound
    @ApiErrors
    public RefKindRepresentation rename(
            @Parameter(description = "The code of the kind.")
            @PathParam("code") String code,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {RefKindRepresentation.class, VersionCheckRepresentation.class})))
            RefKindRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(code), () -> {
            Checks.fixed("code", code, body.code());
            RealmKindService.View stored = service.get(code);
            Writes.requireNoSource(body.source(), body.externalId());
            Writes.requireRead(body, () -> represent(stored), RefKindRepresentation.READ_ONLY);
            var kind = represent(externalIds.write(ExternalId.Kind.REF_KIND, stored.id(), body.source(),
                    body.externalId(), () -> service.rename(code, body.primaryLanguage(), body.names())));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, code, path(code), kind);
            return kind;
        });
    }

    @DELETE
    @Path("{code}")
    @Operation(operationId = "deleteRefKind", summary = "Removes a kind of the realm that has no items.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The kind is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The code of the kind.")
            @PathParam("code") String code,
            @Parameter(
                    description = "The version of the kind that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(code), () -> {
            service.delete(code);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, code, path(code), null);
            return Response.noContent().build();
        });
    }

    private static String path(String code) {
        return "fuhito/ref-kinds/" + code;
    }

    // The IDs of every kind of a list are read with one query. A fixed kind has no ID.
    private List<RefKindRepresentation> represent(List<RealmKindService.View> kinds) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.REF_KIND,
                kinds.stream().map(RealmKindService.View::id).filter(Objects::nonNull).toList());
        Locale language = context.nameLanguage();
        return kinds.stream().map(kind -> RefKindRepresentation.of(kind, ids, language)).toList();
    }

    private RefKindRepresentation represent(RealmKindService.View kind) {
        return represent(List.of(kind)).getFirst();
    }

    private long version(String code) {
        return service.get(code).version();
    }
}
