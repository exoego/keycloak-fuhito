package net.exoego.keycloak.fuhito.rest;

import java.util.List;

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
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.MemberQuery;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import net.exoego.keycloak.fuhito.service.OrganizationLinkService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The org units of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.ORG_UNITS)
public class OrgUnitsResource {

    /**
     * The resource type of org units in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_ORG_UNIT";

    private final RequestContext context;
    private final OrgUnitService service;
    private final MembershipService membership;
    private final OrganizationLinkService organizationLinks;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param membership the reads of members on the realm of the request
     * @param organizationLinks the links to Keycloak organizations of the realm
     */
    public OrgUnitsResource(RequestContext context, OrgUnitService service, MembershipService membership,
            OrganizationLinkService organizationLinks) {
        this.organizationLinks = organizationLinks;
        this.context = context;
        this.service = service;
        this.membership = membership;
    }

    @POST
    @Operation(operationId = "createOrgUnit", summary = "Creates an org unit that starts on a date.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The unit as it is on its first date.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = OrgUnitRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response create(OrgUnitRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var unit = OrgUnitRepresentation.of(service.create(body.toNewOrgUnit()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, unit.id(), path(unit.id()), unit);
            return Response.status(Response.Status.CREATED).entity(unit).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "changeOrgUnitDetails",
            summary = "Replaces the details of an org unit from the `validFrom` date of the body.",
            description = "The details are the names, the unit type, the display order, and the attributes. The "
                    + "code, the parent, and the external ID do not change here.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The unit as it is on that date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation changeDetails(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            OrgUnitRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            return updated(path(id), service.changeDetails(id, body.toChange()));
        });
    }

    @PUT
    @Path("{id}/parent")
    @Operation(
            operationId = "moveOrgUnit",
            summary = "Gives an org unit a new parent in the default hierarchy from a date.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The unit as it is on that date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation move(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            ParentChangeRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            return updated(path(id) + "/parent", service.move(id, body.parentId(), body.validFrom()));
        });
    }

    @PUT
    @Path("{id}/end")
    @Operation(
            operationId = "endOrgUnit",
            summary = "Ends an org unit.",
            description = "Its history stays readable for earlier dates.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The unit as it is on its last date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation end(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            return updated(path(id) + "/end", service.end(id, body.validTo()));
        });
    }

    @GET
    @Operation(
            operationId = "listOrgUnits",
            summary = "Lists the org units that exist on a date.",
            description = "Each parent comes before its children.")
    @APIResponse(responseCode = "200", description = "The units on the date.")
    @BadRequest
    @ApiErrors
    public List<OrgUnitRepresentation> list(
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to return only the units with no parent.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("roots") boolean roots) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.list(asOf, roots)));
    }

    @GET
    @Path("{id}/children")
    @Operation(operationId = "listOrgUnitChildren", summary = "Lists the direct children of an org unit on a date.")
    @APIResponse(responseCode = "200", description = "The children on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<OrgUnitRepresentation> children(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless a unit sets its own time zone.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.children(id, asOf)));
    }

    @GET
    @Path("{id}/subtree")
    @Operation(
            operationId = "listOrgUnitSubtree",
            summary = "Lists an org unit and everything below it on a date.",
            description = "Each parent comes before its children.")
    @APIResponse(responseCode = "200", description = "The unit and its descendants on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<OrgUnitRepresentation> subtree(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless a unit sets its own time zone.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.subtree(id, asOf)));
    }

    @GET
    @Path("{id}/path")
    @Operation(
            operationId = "getOrgUnitPath",
            summary = "Lists the units from the root down to an org unit on a date.")
    @APIResponse(responseCode = "200", description = "The path, with the root first and the unit last.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<OrgUnitRepresentation> path(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless a unit sets its own time zone.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.path(id, asOf)));
    }

    @PUT
    @Path("{id}/organization")
    @Operation(
            operationId = "linkOrgUnitOrganization",
            summary = "Links an org unit to a Keycloak organization.",
            description = "The link says that the unit stands for the organization. One organization is linked to "
                    + "at most one org unit.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The link.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrganizationLinkRepresentation linkOrganization(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            OrganizationLinkRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var unit = organizationLinks.link(id, body.organizationId());
            var link = new OrganizationLinkRepresentation(unit.id(), unit.keycloakOrganizationId());
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/organization", link);
            return link;
        });
    }

    @DELETE
    @Path("{id}/organization")
    @Operation(
            operationId = "unlinkOrgUnitOrganization",
            summary = "Removes the link of an org unit to a Keycloak organization.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The org unit with no link.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrganizationLinkRepresentation unlinkOrganization(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            var unit = organizationLinks.unlink(id);
            var link = new OrganizationLinkRepresentation(unit.id(), null);
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/organization", link);
            return link;
        });
    }

    @PUT
    @Path("{id}/time-settings")
    @Operation(
            operationId = "setOrgUnitTimeSettings",
            summary = "Sets the time zone and the day start of an org unit.",
            description = "They decide what today is for the unit and for the units below it. A value that is left "
                    + "out is removed, and the unit then uses the value from above.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The settings of the org unit.")
    @BadRequest
    @NotFound
    @ApiErrors
    public TimeSettingsRepresentation setTime(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            TimeSettingsRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var unit = service.setTime(id, body.timeZone(), body.dayStart());
            var settings = new TimeSettingsRepresentation(unit.id(), unit.timeZone(), unit.dayStart());
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id) + "/time-settings", settings);
            return settings;
        });
    }

    @GET
    @Path("{id}/members")
    @Operation(
            operationId = "listOrgUnitMembers",
            summary = "Lists the assignments to an org unit that hold on a date.")
    @APIResponse(responseCode = "200", description = "The members on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<MemberRepresentation> members(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless a unit sets its own time zone.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to also list the members of every unit below.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("includeDescendants") boolean includeDescendants,
            @Parameter(description = "The code of an occupation to keep.")
            @QueryParam("occupation") String occupation,
            @Parameter(description = "The code of a job title to keep.")
            @QueryParam("jobTitle") String jobTitle,
            @Parameter(
                    description = "An employment status to keep.",
                    schema = @Schema(ref = "EmploymentStatus"))
            @QueryParam("status") String status,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> membership.members(id,
                        new MemberQuery(asOf, includeDescendants, occupation, jobTitle, status, first, max)).stream()
                .map(MemberRepresentation::of)
                .toList());
    }

    @GET
    @Path("{id}/history")
    @Operation(
            operationId = "getOrgUnitHistory",
            summary = "Returns everything that is stored about an org unit over time.")
    @APIResponse(responseCode = "200", description = "The details of every period and the parent of every period.")
    @NotFound
    @ApiErrors
    public OrgUnitHistoryRepresentation history(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> OrgUnitHistoryRepresentation.of(service.history(id)));
    }

    private OrgUnitRepresentation updated(String path, OrgUnitState state) {
        var unit = OrgUnitRepresentation.of(state);
        context.recordChange(Change.UPDATE, RESOURCE_TYPE, unit.id(), path, unit);
        return unit;
    }

    private static String path(String id) {
        return "fuhito/org-units/" + id;
    }

    private static List<OrgUnitRepresentation> represent(List<OrgUnitState> states) {
        return states.stream().map(OrgUnitRepresentation::of).toList();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getOrgUnit", summary = "Returns an org unit as it is on a date.")
    @APIResponse(responseCode = "200", description = "The unit on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> OrgUnitRepresentation.of(service.get(id, asOf)));
    }
}
