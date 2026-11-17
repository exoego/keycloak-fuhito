package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.EffectiveChangeService;
import net.exoego.keycloak.fuhito.service.Page;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What takes effect on which date in one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.CHANGE_ORDERS)
public class EffectiveChangesResource {

    private final RequestContext context;
    private final EffectiveChangeService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the reads on the realm of the request
     */
    public EffectiveChangesResource(RequestContext context, EffectiveChangeService service) {
        this.context = context;
        this.service = service;
    }

    @GET
    @Operation(
            operationId = "listEffectiveChanges",
            summary = "Lists the resources with a period that starts or ends in a range of dates.",
            description = "A change with a future date is in the change log on the day it is stored. This read "
                    + "tells when it takes effect. An entry says that something about the resource starts, "
                    + "changes, or ends on the date. Read the resource with `asOf` to see what. The entries come "
                    + "from the rows as they are now, so a change that was removed before its date is not listed. "
                    + "The order is the date, then the type, then the ID.")
    @APIResponse(responseCode = "200", description = "The entries.")
    @BadRequest
    @ApiErrors
    public List<EffectiveChangeRepresentation> list(
            @Parameter(
                    description = "The first date of the range. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("from") String from,
            @Parameter(
                    description = "The first date after the range. The default is the day after `from`.",
                    schema = @Schema(format = "date"))
            @QueryParam("to") String to,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> service.list(from, to, Page.of(first, max)).stream()
                .map(EffectiveChangeRepresentation::of)
                .toList());
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countEffectiveChanges",
            summary = "Counts the entries that `GET /effective-changes` lists with the same range.")
    @APIResponse(responseCode = "200", description = "The number of entries on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(
                    description = "The first date of the range. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("from") String from,
            @Parameter(
                    description = "The first date after the range. The default is the day after `from`.",
                    schema = @Schema(format = "date"))
            @QueryParam("to") String to) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(service.count(from, to)));
    }
}
