package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;

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
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.RefItemService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The reference items of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.REF_ITEMS)
public class RefItemsResource {

    /**
     * The resource type of reference items in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_REF_ITEM";

    private final RequestContext context;
    private final RefItemService service;
    private final ExternalIdService externalIds;
    private final StructureLimit limit;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     * @param limit how far ahead a caller who may read only the structure may read
     */
    RefItemsResource(RequestContext context, RefItemService service, ExternalIdService externalIds,
            StructureLimit limit) {
        this.context = context;
        this.service = service;
        this.externalIds = externalIds;
        this.limit = limit;
    }

    @GET
    @Operation(
            operationId = "listRefItems",
            summary = "Lists the items of a kind, ordered by display order and then by code.",
            description = "Each item has the names of the date. A date before the first names of an item gets "
                    + "its first names.")
    @APIResponse(responseCode = "200", description = "The items.")
    @BadRequest
    @ApiErrors
    public List<RefItemRepresentation> list(
            @Parameter(required = true, description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added.")
            @QueryParam("kind") String kind,
            @Parameter(description = "True to also return inactive items.", schema = @Schema(defaultValue = "false"))
            @QueryParam("includeInactive") boolean includeInactive,
            @Parameter(
                    description = "The date of the names. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.list(kind, includeInactive, limit.checked(asOf),
                Page.of(first, max)).stream().map(limit::readable).toList()));
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countRefItems",
            summary = "Counts the items that `GET /ref-items` lists with the same filters.",
            description = "Every item has names on every date, so the count is the same for every date.")
    @APIResponse(responseCode = "200", description = "The number of items on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(required = true, description = "A fixed kind such as `UNIT_TYPE`, or the code of a kind that the realm added.")
            @QueryParam("kind") String kind,
            @Parameter(description = "True to also count inactive items.", schema = @Schema(defaultValue = "false"))
            @QueryParam("includeInactive") boolean includeInactive) {
        context.requireReadStructure();
        return Calls.run(context, () -> new CountRepresentation(service.count(kind, includeInactive)));
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getRefItem", summary = "Returns one item with the names of a date.")
    @APIResponse(responseCode = "200", description = "The item.")
    @NotFound
    @ApiErrors
    public RefItemRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date of the names. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(limit.readable(service.get(id, limit.checked(asOf)))));
    }

    @GET
    @Path("{id}/history")
    @Operation(
            operationId = "getRefItemHistory",
            summary = "Returns every period of the names of an item.",
            description = "A caller who may read only the structure gets 403 when the realm attribute "
                    + "`fuhito.structure.daysAhead` limits how far ahead the caller may read.")
    @APIResponse(responseCode = "200", description = "The names of every period, oldest first.")
    @NotFound
    @ApiErrors
    public RefItemHistoryRepresentation history(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> {
            limit.requireEveryDate();
            RefItemService.History history = service.history(id);
            return RefItemHistoryRepresentation.of(history,
                    externalIds.lists(ExternalId.Kind.REF_ITEM, List.of(history.item().id())), context.nameLanguage());
        });
    }

    @POST
    @Operation(
            operationId = "createRefItem",
            summary = "Creates an item.",
            description = "The body needs `kind`, `code`, `primaryLanguage`, and `names`. With `validFrom`, the names "
                    + "hold from that date. Without it, they hold for every date.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new item.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RefItemRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(RefItemRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var item = represent(externalIds.create(ExternalId.Kind.REF_ITEM, body.source(), body.externalId(),
                    () -> service.create(body.kind(), body.code(), body.primaryLanguage(), body.names(),
                            body.displayOrder(), body.attributes(), body.marks(), !Boolean.FALSE.equals(body.active()),
                            body.validFrom()), RefItem::id));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, item.id(), path(item.id()), item);
            return Response.status(Response.Status.CREATED).entity(item).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "updateRefItem",
            summary = "Replaces the changeable values of an item.",
            description = "The kind and the code never change. The body can leave them out. Only the names have "
                    + "dates. With `validFrom`, the new names hold from that date until the next stored names, and "
                    + "the old names stay for the dates before. A date that starts stored names corrects them. "
                    + "Without `validFrom`, the write changes the names of today. The response has the names of "
                    + "that date.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed item.")
    @BadRequest
    @NotFound
    @ApiErrors
    public RefItemRepresentation update(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {RefItemRepresentation.class, VersionCheckRepresentation.class})))
            RefItemRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> service.version(id), () -> {
            var item = represent(service.update(id, body.primaryLanguage(), body.names(), body.displayOrder(),
                    body.attributes(), body.active(), body.marks(), body.validFrom()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), item);
            return item;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteRefItem", summary = "Deletes an item.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The item was deleted.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the item that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> service.version(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/ref-items/" + id;
    }

    // The IDs of every item of a list are read with one query.
    private List<RefItemRepresentation> represent(List<RefItem> items) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.REF_ITEM, items.stream().map(RefItem::id).toList());
        Locale language = context.nameLanguage();
        return items.stream().map(item -> RefItemRepresentation.of(item, ids, language)).toList();
    }

    private RefItemRepresentation represent(RefItem item) {
        return represent(List.of(item)).getFirst();
    }
}
