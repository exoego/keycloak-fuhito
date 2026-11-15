package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.RealmKindService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
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

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public RefKindsResource(RequestContext context, RealmKindService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(
            operationId = "listRefKinds",
            summary = "Lists the kinds of reference data, the fixed ones first, then the ones of the realm.")
    @APIResponse(responseCode = "200", description = "The kinds.")
    @ApiErrors
    public List<RefKindRepresentation> list() {
        context.requireReadStructure();
        return Calls.run(context, () -> service.list().stream().map(RefKindRepresentation::of).toList());
    }

    @POST
    @Operation(
            operationId = "createRefKind",
            summary = "Adds a kind of reference data to the realm, such as a work location or a cost center.",
            description = "Its items are reference items with the code as their kind. Attributes of the type "
                    + "`REF_ITEM` can point to it.")
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
        return Calls.once(context, body, Response.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            var kind = RefKindRepresentation.of(service.create(body.code(), body.name(), body.names()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, kind.code(), path(kind.code()), kind);
            return Response.status(Response.Status.CREATED).entity(kind).build();
        }));
    }

    @DELETE
    @Path("{code}")
    @Operation(operationId = "deleteRefKind", summary = "Removes a kind of the realm that has no items.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The kind is gone.")
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The code of the kind.")
            @PathParam("code") String code) {
        context.requireWrite();
        return Calls.run(context, () -> {
            service.delete(code);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, code, path(code), null);
            return Response.noContent().build();
        });
    }

    private static String path(String code) {
        return "fuhito/ref-kinds/" + code;
    }
}
