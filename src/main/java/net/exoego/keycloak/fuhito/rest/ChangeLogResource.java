package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.ChangeLogService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The change log of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.CHANGE_ORDERS)
public class ChangeLogResource {

    private final RequestContext context;
    private final ChangeLogService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the reads on the realm of the request
     */
    public ChangeLogResource(RequestContext context, ChangeLogService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(operationId = "listChangeLog", summary = "Lists the writes to one resource, oldest first.")
    @APIResponse(responseCode = "200", description = "The entries.")
    @BadRequest
    @ApiErrors
    public List<ChangeLogRepresentation> ofResource(
            @Parameter(
                    description = "The type of the resource.",
                    required = true,
                    schema = @Schema(examples = {"FUHITO_ORG_UNIT"}))
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource.", required = true)
            @QueryParam("resourceId") String resourceId,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> service.ofResource(resourceType, resourceId, first, max).stream()
                .map(ChangeLogRepresentation::of)
                .toList());
    }
}
