package net.exoego.keycloak.fuhito.rest;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.RealmExport;
import net.exoego.keycloak.fuhito.service.RealmExportService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The restore of an export into a realm with no data of the extension, or in place of its data.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.BULK)
public class RestoreResource {

    /**
     * The resource type of a restore in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_RESTORE";

    private final RequestContext context;
    private final RealmExportService service;
    private final String realmId;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the restore of the realm
     * @param realmId the ID of the realm
     */
    public RestoreResource(RequestContext context, RealmExportService service, String realmId) {
        this.context = context;
        this.service = service;
        this.realmId = realmId;
    }

    @POST
    @Operation(
            operationId = "restore",
            summary = "Loads an export into a realm that has no data of the extension, or in place of its data.",
            description = "Every row keeps its ID, so products that stored IDs find the same resources. The rows "
                    + "are written all or nothing. A username must name a user of this realm. An org unit names its "
                    + "Keycloak organization by alias, and the restore asks for the permission to manage it.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "200",
            description = "The number of rows of each list.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.OBJECT, additionalProperties = Integer.class)))
    @BadRequest
    @ApiErrors
    public Map<String, Integer> restore(
            RealmExport body,
            @Parameter(
                    description = "True to delete every row of the extension in the realm first, for data that was "
                            + "removed or changed by mistake. The change log stays. Without it, a realm with data is "
                            + "refused.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("replace") boolean replace) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Map<String, Integer> counts = service.restore(body, replace, context::requireManageOrganization);
            context.recordChange(replace ? Change.UPDATE : Change.CREATE, RESOURCE_TYPE, realmId, "fuhito/restore",
                    counts);
            return counts;
        });
    }
}
