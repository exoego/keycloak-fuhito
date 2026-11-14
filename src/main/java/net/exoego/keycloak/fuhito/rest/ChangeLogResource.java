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
    @Operation(
            operationId = "listChangeLog",
            summary = "Lists the writes to the realm, oldest first.",
            description = "To follow the changes, keep the `sequence` of the last entry of each read and pass it as "
                    + "`after` in the next read. A read that returns fewer entries than `max` has reached the end. "
                    + "The numbers of a realm start at 1 and have no gaps, except where an erase of the data of a "
                    + "person removed entries.")
    @APIResponse(responseCode = "200", description = "The entries.")
    @BadRequest
    @ApiErrors
    public List<ChangeLogRepresentation> list(
            @Parameter(
                    description = "The sequence number that the entries come after.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("after") Long after,
            @Parameter(
                    description = "The type of the resource. Without it, the writes to every type are listed.",
                    schema = @Schema(examples = {"FUHITO_ORG_UNIT"}))
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource. Without it, the writes to every resource are listed.")
            @QueryParam("resourceId") String resourceId,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> service.list(after, resourceType, resourceId, first, max).stream()
                .map(ChangeLogRepresentation::of)
                .toList());
    }
}
