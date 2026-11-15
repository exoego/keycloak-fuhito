package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The IDs that other systems have for the resources of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.GENERAL)
public class ExternalIdsResource {

    /**
     * The resource type of IDs of other systems in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_EXTERNAL_ID";

    private static final String KINDS = "ORG_UNIT, PERSON, EMPLOYMENT, ASSIGNMENT, or QUALIFICATION.";

    private final RequestContext context;
    private final ExternalIdService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public ExternalIdsResource(RequestContext context, ExternalIdService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(
            operationId = "listExternalIds",
            summary = "Lists every ID of one resource in other systems.",
            description = "The pair that the resource itself has comes first. The IDs of an org unit need the "
                    + "permission to read the org structure. The others need the full read.")
    @APIResponse(responseCode = "200", description = "The IDs.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<ExternalIdRepresentation> list(
            @Parameter(description = "The kind of resource, " + KINDS, required = true)
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource.", required = true)
            @QueryParam("resourceId") String resourceId) {
        return Calls.run(context, () -> {
            requireRead(resourceType);
            return service.list(resourceType, resourceId).stream().map(ExternalIdRepresentation::of).toList();
        });
    }

    @GET
    @Path("lookup")
    @Operation(
            operationId = "lookUpExternalId",
            summary = "Finds the resource that an ID of a system names.",
            description = "It looks at the pair that each resource has, and at the IDs that were added.")
    @APIResponse(responseCode = "200", description = "The ID with its resource.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ExternalIdRepresentation lookUp(
            @Parameter(description = "The kind of resource, " + KINDS, required = true)
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The name of the system.", required = true)
            @QueryParam("source") String source,
            @Parameter(description = "The ID in that system.", required = true)
            @QueryParam("externalId") String externalId) {
        return Calls.run(context, () -> {
            requireRead(resourceType);
            return ExternalIdRepresentation.of(service.find(resourceType, source, externalId));
        });
    }

    @PUT
    @Operation(
            operationId = "putExternalId",
            summary = "Stores the ID of one system for a resource, or replaces it.",
            description = "A resource has at most one ID for each system, counting its own pair. One ID of one "
                    + "system names one resource of a kind.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The stored ID.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ExternalIdRepresentation put(ExternalIdRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            ExternalIdService.View view = service.put(body.resourceType(), body.resourceId(), body.source(),
                    body.externalId());
            var stored = ExternalIdRepresentation.of(view);
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, view.rowId(), path(view.rowId()), stored);
            return stored;
        });
    }

    @DELETE
    @Operation(operationId = "deleteExternalId", summary = "Removes the ID of one system from a resource.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The ID is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The kind of resource, " + KINDS, required = true)
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource.", required = true)
            @QueryParam("resourceId") String resourceId,
            @Parameter(description = "The name of the system.", required = true, schema = @Schema(maxLength = 64))
            @QueryParam("source") String source) {
        context.requireWrite();
        return Calls.run(context, () -> {
            ExternalIdService.View view = service.remove(resourceType, resourceId, source);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, view.rowId(), path(view.rowId()), null);
            return Response.noContent().build();
        });
    }

    private void requireRead(String resourceType) {
        if (ExternalIdService.isStructure(resourceType)) {
            context.requireReadStructure();
        } else {
            context.requireRead();
        }
    }

    private static String path(String rowId) {
        return "fuhito/external-ids/" + rowId;
    }
}
