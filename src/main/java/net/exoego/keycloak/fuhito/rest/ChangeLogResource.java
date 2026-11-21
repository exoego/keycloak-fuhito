package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.ChangeLogService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
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
            summary = "Lists the writes to the realm and the propagated changes, oldest first.",
            description = "To follow the changes, keep the `sequence` of the last entry of each read and pass it as "
                    + "`after` in the next read. A read that returns fewer entries than `max` has reached the end. "
                    + "The numbers of a realm start at 1. An entry has no state of the resource. "
                    + "Read the resource again for its state. The entries of writes are kept for good. A propagated "
                    + "change is removed after the days of the realm attribute `fuhito.changeLog.keepPropagatedDays`, "
                    + "90 by default. So the numbers can have gaps. `after` that no entry ever had, because it is "
                    + "larger than the last number, fails with 400 and `invalid.out_of_range`, with the last number in "
                    + "`max`.")
    @APIResponse(responseCode = "200", description = "The entries.")
    @APIResponse(responseCode = "410",
            description = "A propagated change after `after` is removed, so the read would leave it out. The error "
                    + "is `change_log.cursor_expired`. Read every resource again, and then go on after the number in "
                    + "`keptAfter`. A read with `kind=WRITE` never gets it, nor does a read with no `after`.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorRepresentation.class)))
    @BadRequest
    @ApiErrors
    public List<ChangeLogRepresentation> list(
            @Parameter(
                    description = "The sequence number that the entries come after. Without it, or with 0, the read "
                            + "starts at the first entry that is kept.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("after") Long after,
            @Parameter(
                    description = "The type of the resource. Without it, the writes to every type are listed.",
                    schema = @Schema(enumeration = {"FUHITO_ORG_UNIT", "FUHITO_PERSON", "FUHITO_PERSON_DATA",
                            "FUHITO_EMPLOYMENT", "FUHITO_ASSIGNMENT", "FUHITO_QUALIFICATION", "FUHITO_REPORTING_LINE",
                            "FUHITO_UNIT_ROLE", "FUHITO_CHANGE_ORDER", "FUHITO_REF_ITEM", "FUHITO_REF_KIND",
                            "FUHITO_HIERARCHY", "FUHITO_ATTRIBUTE_DEFINITION", "FUHITO_EXTERNAL_ID", "FUHITO_RESTORE"}))
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource. Without it, the writes to every resource are listed.")
            @QueryParam("resourceId") String resourceId,
            @Parameter(
                    description = "`WRITE` lists only the writes, and leaves out the propagated changes. "
                            + "`PROPAGATED` lists only the propagated changes. Without it, both are listed.",
                    schema = @Schema(enumeration = {"WRITE", "PROPAGATED"}))
            @QueryParam("kind") String kind,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> service.list(after, resourceType, resourceId, kind, first, max).stream()
                .map(ChangeLogRepresentation::of)
                .toList());
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countChangeLog",
            summary = "Counts the entries that `GET /change-log` lists with the same filters, from the start.")
    @APIResponse(responseCode = "200", description = "The number of entries.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(
                    description = "The type of the resource. Without it, the writes to every type are counted.",
                    schema = @Schema(enumeration = {"FUHITO_ORG_UNIT", "FUHITO_PERSON", "FUHITO_PERSON_DATA",
                            "FUHITO_EMPLOYMENT", "FUHITO_ASSIGNMENT", "FUHITO_QUALIFICATION", "FUHITO_REPORTING_LINE",
                            "FUHITO_UNIT_ROLE", "FUHITO_CHANGE_ORDER", "FUHITO_REF_ITEM", "FUHITO_REF_KIND",
                            "FUHITO_HIERARCHY", "FUHITO_ATTRIBUTE_DEFINITION", "FUHITO_EXTERNAL_ID", "FUHITO_RESTORE"}))
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource. Without it, the writes to every resource are counted.")
            @QueryParam("resourceId") String resourceId,
            @Parameter(
                    description = "`WRITE` counts only the writes, and `PROPAGATED` only the propagated changes "
                            + "that are kept. Without it, both are counted.",
                    schema = @Schema(enumeration = {"WRITE", "PROPAGATED"}))
            @QueryParam("kind") String kind) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(service.count(resourceType, resourceId, kind)));
    }
}
