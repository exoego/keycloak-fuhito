package net.exoego.keycloak.fuhito.rest;

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
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.UnitRoleService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Locale;

/**
 * The roles over org units of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.UNIT_ROLES)
public class UnitRolesResource {

    /**
     * The resource type of roles over org units in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_UNIT_ROLE";

    private final RequestContext context;
    private final UnitRoleService service;
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public UnitRolesResource(RequestContext context, UnitRoleService service, ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.externalIds = externalIds;
    }

    @GET
    @Operation(
            operationId = "listUnitRoles",
            summary = "Lists the roles over org units of a date across the realm, ordered by ID.",
            description = "To read every role, follow the `Link` header of each response. `orgUnitId` keeps the "
                    + "roles over that unit only. The roles over a unit above that cover it are in "
                    + "`GET /org-units/{id}/role-holders`. They name people, so the read needs the full read "
                    + "permission.")
    @APIResponse(
            responseCode = "200",
            description = "The roles of the date.",
            headers = {
                @Header(
                        name = "Link",
                        description = "The URL of the next page, with `rel=\"next\"`. It is there when the page "
                                + "is full. A full page can be the last one, and the next page is then empty.",
                        schema = @Schema(type = SchemaType.STRING))},
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = RoleHolderRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response list(
            @Parameter(description = "The code of a role to keep.")
            @QueryParam("role") String role,
            @Parameter(description = "The ID of an org unit to keep. The units above do not count.")
            @QueryParam("orgUnitId") String orgUnitId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The number of entries to skip. It cannot be used with `after`.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max,
            @Parameter(description = "The cursor from the `Link` header of the page before.")
            @QueryParam("after") String after) {
        context.requireRead();
        return Calls.run(context, () -> {
            Cursor.requireAlone(after, first);
            UnitRoleService.Search search = new UnitRoleService.Search(role, orgUnitId, asOf);
            Locale language = context.nameLanguage();
            return Pages.of(context, service.list(search, Page.of(first, max), after).stream()
                            .map(holder -> RoleHolderRepresentation.of(holder, language)).toList(),
                    Page.of(first, max).max(),
                    holder -> Cursor.encode(Cursor.UNIT_ROLES, holder.id()));
        });
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countUnitRoles",
            summary = "Counts the roles that `GET /unit-roles` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of roles on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(description = "The code of a role to keep.")
            @QueryParam("role") String role,
            @Parameter(description = "The ID of an org unit to keep. The units above do not count.")
            @QueryParam("orgUnitId") String orgUnitId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context,
                () -> new CountRepresentation(service.count(new UnitRoleService.Search(role, orgUnitId, asOf))));
    }

    @POST
    @Operation(
            operationId = "addUnitRole",
            summary = "Gives an employment a role over an org unit.",
            description = "The role is not membership. The end of the employment ends the role, and a transfer "
                    + "to another employer carries it.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new role.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = UnitRoleRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response add(UnitRoleRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var role = represent(externalIds.create(ExternalId.Kind.UNIT_ROLE, body.source(), body.externalId(),
                    () -> service.add(body.employmentId(), body.orgUnitId(), body.role(), body.includesDescendants(),
                            body.validFrom(), body.validTo()),
                    view -> view.role().id()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, role.id(), path(role.id()), role);
            return Response.status(Response.Status.CREATED).entity(role).build();
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getUnitRole", summary = "Returns one role over an org unit.")
    @APIResponse(responseCode = "200", description = "The role.")
    @NotFound
    @ApiErrors
    public UnitRoleRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.get(id)));
    }

    @PUT
    @Path("{id}/end")
    @Operation(operationId = "endUnitRole", summary = "Ends a role over an org unit earlier.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The role with its new end.")
    @BadRequest
    @NotFound
    @ApiErrors
    public UnitRoleRepresentation end(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EndRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            var role = represent(service.end(id, body.validTo()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/end", role);
            return role;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteUnitRole",
            summary = "Deletes a role that was stored by mistake.",
            description = "End a role that held for a while. Its entries in the change log stay.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The role is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the role that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/unit-roles/" + id;
    }

    // The IDs of every role of a list are read with one query.
    static List<UnitRoleRepresentation> represent(List<UnitRoleService.View> roles, ExternalIdService externalIds) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.UNIT_ROLE,
                roles.stream().map(view -> view.role().id()).toList());
        return roles.stream().map(view -> UnitRoleRepresentation.of(view, ids)).toList();
    }

    private UnitRoleRepresentation represent(UnitRoleService.View role) {
        return represent(List.of(role), externalIds).getFirst();
    }

    private long version(String id) {
        return service.get(id).role().version();
    }
}
