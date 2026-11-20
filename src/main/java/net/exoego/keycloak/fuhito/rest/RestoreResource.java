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
            description = "Every row keeps its ID, so products that stored IDs find the same resources. The rows are "
                    + "written all or nothing. A row that is null, or a row with no value that its table needs, such "
                    + "as `attributes`, fails with 400 and `restore.bad_value`. So does a text longer than its column, "
                    + "or a value that breaks the rule of a write, such as a code or a name. So does a `validTo` that "
                    + "is not after the `validFrom` of its row, as in a write. An empty text, a text of "
                    + "only spaces, or an empty array in a row fails with 400 and `invalid.blank`, as in a write. A "
                    + "text with NUL (U+0000) or half of a surrogate pair fails with 400 and `invalid.character`, "
                    + "as in a write. The error names the list, the `id` of the row, and the field. A document that "
                    + "gives one key twice in one object fails with 400 and `invalid.duplicate_key`, as in a write. "
                    + "That error names the key with its path in the document. A row may have "
                    + "an empty list of its own rows, such as `parents`. Two rows with the same ID or the same unique "
                    + "key, such as the `code` of a hierarchy, the `number` of a change order, or the user of a "
                    + "person, fail with 400 and `restore.duplicate`. Two rows that must not be valid on the same "
                    + "date, such as two versions of one org unit, fail with 400 and `restore.overlap`. These errors "
                    + "also name the `other` row. A row that breaks a rule of the database that the restore did not "
                    + "find, for example because another write stored the same row at the same time, fails with 409 "
                    + "and `restore.conflict`, with the name of the rule in `constraint`. A username must name a user "
                    + "of this realm. An org unit names its Keycloak organization by alias, and the restore asks for "
                    + "the permission to manage it.")
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
        context.requireBulkWrite();
        return Calls.run(context, "restore.conflict", () -> {
            Map<String, Integer> counts = service.restore(body, replace, context::requireManageOrganization);
            context.recordChange(replace ? Change.UPDATE : Change.CREATE, RESOURCE_TYPE, realmId, "fuhito/restore",
                    counts);
            return counts;
        });
    }
}
