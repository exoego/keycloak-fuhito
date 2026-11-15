package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.UnitRoleService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public UnitRolesResource(RequestContext context, UnitRoleService service) {
        this.context = context;
        this.service = service;
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
            var role = UnitRoleRepresentation.of(service.add(body.employmentId(), body.orgUnitId(), body.role(),
                    body.includesDescendants(), body.validFrom(), body.validTo()));
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
        return Calls.run(context, () -> UnitRoleRepresentation.of(service.get(id)));
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
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var role = UnitRoleRepresentation.of(service.end(id, body.validTo()));
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
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/unit-roles/" + id;
    }
}
