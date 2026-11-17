package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.function.Supplier;

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
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.MemberQuery;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import net.exoego.keycloak.fuhito.service.OrganizationLinkService;
import net.exoego.keycloak.fuhito.service.UnitRoleService;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.RemovalService;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
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
    private final UnitRoleService unitRoles;
    private final RemovalService removals;
    private final StructureLimit limit;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param membership the reads of members on the realm of the request
     * @param organizationLinks the links to Keycloak organizations of the realm
     * @param unitRoles the roles over org units of the realm
     * @param removals the removal of resources that were made by mistake
     * @param limit how far ahead a caller who may read only the structure may read it
     */
    OrgUnitsResource(RequestContext context, OrgUnitService service, MembershipService membership,
            OrganizationLinkService organizationLinks, UnitRoleService unitRoles, RemovalService removals,
            StructureLimit limit) {
        this.limit = limit;
        this.removals = removals;
        this.unitRoles = unitRoles;
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
        return Calls.once(context, body, Response.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            var unit = OrgUnitRepresentation.of(service.create(body.toNewOrgUnit()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, unit.id(), path(unit.id()), unit);
            return Response.status(Response.Status.CREATED).entity(unit).build();
        }));
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "changeOrgUnitDetails",
            summary = "Replaces the details of an org unit from the `validFrom` date of the body.",
            description = "The details are the code, the names, the unit type, the display order, and the "
                    + "attributes. The parent and the external ID do not change here. No other org unit may have the "
                    + "same code on a date of the change.")
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
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            return updated(path(id), service.changeDetails(id, body.toChange()));
        });
    }

    @PUT
    @Path("{id}/parent")
    @Operation(
            operationId = "moveOrgUnit",
            summary = "Gives an org unit a new parent in a hierarchy from a date.",
            description = "The response has the parent in that hierarchy.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The unit as it is on that date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation move(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            ParentChangeRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            return updated(path(id) + "/parent", service.move(id, body.parentId(), body.validFrom(), hierarchy));
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteOrgUnit",
            summary = "Deletes an org unit that nothing refers to, with all its history.",
            description = "It works only when no unit had it as a parent, no assignment, employment, role, or succession names it, and it has no link to a Keycloak organization. Its periods and its parents go with it. End a unit that existed for a while instead.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The resource is gone.")
    @NotFound
    @ApiErrors
    public Response remove(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.guardedRemoval(context, () -> state(id), () -> {
            removals.remove(ResourceRemoval.Kind.ORG_UNIT, id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    @PUT
    @Path("{id}/end")
    @Operation(
            operationId = "endOrgUnit",
            summary = "Ends an org unit, or moves its end.",
            description = "Its history stays readable for earlier dates. A later end lengthens the last details and "
                    + "the parents that ended with the unit. The end of a unit that other units follow does not "
                    + "move.")
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
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            return updated(path(id) + "/end", service.end(id, body.validTo()));
        });
    }

    @DELETE
    @Path("{id}/end")
    @Operation(
            operationId = "removeOrgUnitEnd",
            summary = "Removes the end of an org unit.",
            description = "The last details and the parents that ended with the unit get no end either. Each "
                    + "parent must have no end then.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The unit as it is on the first date of its last details.")
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation removeEnd(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireWrite();
        return Calls.guarded(context, () -> state(id), () -> updated(path(id) + "/end", service.removeEnd(id)));
    }

    @GET
    @Operation(
            operationId = "listOrgUnits",
            summary = "Lists the org units with no parent on a date, the roots.",
            description = "There is no read of the whole tree. Go down with the children of an org unit, or "
                    + "read one branch with its subtree. Siblings follow the display order, then the phonetic "
                    + "name, then the name.")
    @APIResponse(responseCode = "200", description = "The roots on the date.")
    @BadRequest
    @ApiErrors
    public List<OrgUnitRepresentation> list(
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to list the units that are legal entities, such as companies, in place "
                            + "of the roots. A unit is one when its unit type says so. `hierarchy` then has no "
                            + "effect.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("legalEntity") boolean legalEntity,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "True for lateral units only, such as committees. False for line units only. "
                            + "Leave it out for both.")
            @QueryParam("lateral") Boolean lateral,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(legalEntity
                ? service.legalEntities(limit.checked(asOf), Page.of(first, max))
                : service.roots(limit.checked(asOf), hierarchy, lateral, Page.of(first, max))));
    }

    @GET
    @Path("search")
    @Operation(
            operationId = "searchOrgUnits",
            summary = "Lists the org units of a date that match a search, at any depth.",
            description = "Each unit comes with its parent in the default hierarchy. The order is the display "
                    + "order, then the phonetic name, then the name.")
    @APIResponse(
            responseCode = "200",
            description = "The units that match.",
            headers = @Header(
                    name = "X-Total-Count",
                    description = "The number of units on all pages. It is there with `count=true`.",
                    schema = @Schema(type = SchemaType.INTEGER)),
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = OrgUnitRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response search(
            @Parameter(
                    description = "A part of the name, of the short name, or of the phonetic name, in any language, "
                            + "with no regard to case.")
            @QueryParam("q") String q,
            @Parameter(description = "The code of the unit on the date.")
            @QueryParam("code") String code,
            @Parameter(description = "The code of the unit type.")
            @QueryParam("unitType") String unitType,
            @Parameter(
                    description = "True for lateral units only, such as committees. False for line units only. "
                            + "Leave it out for both.")
            @QueryParam("lateral") Boolean lateral,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max,
            @Parameter(
                    description = "True to get the number of units on all pages in the header `X-Total-Count`.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("count") boolean count) {
        context.requireReadStructure();
        return Calls.run(context, () -> {
            OrgUnitService.Search search = new OrgUnitService.Search(q, code, unitType, lateral,
                    limit.checked(asOf));
            Response.ResponseBuilder response = Response.ok(represent(service.search(search, Page.of(first, max))));
            if (count) {
                response.header(Pages.TOTAL, service.countSearch(search));
            }
            return response.build();
        });
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
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "True for lateral units only, such as committees. False for line units only. "
                            + "Leave it out for both.")
            @QueryParam("lateral") Boolean lateral,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.children(id, limit.checked(asOf), hierarchy, lateral,
                Page.of(first, max))));
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
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "True for lateral units only, such as committees. False for line units only. "
                            + "Leave it out for both.")
            @QueryParam("lateral") Boolean lateral,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.subtree(id, limit.checked(asOf), hierarchy, lateral,
                Page.of(first, max))));
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
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.path(id, limit.checked(asOf), hierarchy)));
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
        return Calls.guarded(context, () -> state(id), () -> {
            Checks.required("body", body);
            // A link decides who is a member of the organization, as the admin API of Keycloak does.
            organizationLinks.organizationOf(id).ifPresent(context::requireManageOrganization);
            context.requireManageOrganization(body.organizationId());
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
        return Calls.guarded(context, () -> state(id), () -> {
            organizationLinks.organizationOf(id).ifPresent(context::requireManageOrganization);
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
        return Calls.guarded(context, () -> state(id), () -> {
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
            summary = "Lists the assignments to an org unit that hold on a date.",
            description = "To read every member, follow the `Link` header of each response. A late page then "
                    + "costs as much as the first one.")
    @APIResponse(
            responseCode = "200",
            description = "The members on the date.",
            headers = {
                @Header(
                        name = "Link",
                        description = "The URL of the next page, with `rel=\"next\"`. It is there when the page "
                                + "is full. A full page can be the last one, and the next page is then empty.",
                        schema = @Schema(type = SchemaType.STRING)),
                @Header(
                        name = "X-Total-Count",
                        description = "The number of members on all pages. It is there with `count=true`.",
                        schema = @Schema(type = SchemaType.INTEGER))},
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = MemberRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response members(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless a unit sets its own time zone.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
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
            @Parameter(description = "True to keep only the people who work outside the legal entity that employs "
                    + "them. False to keep everyone else. The default is everyone.")
            @QueryParam("seconded") Boolean seconded,
            @Parameter(
                    description = "The code of a work location to keep. It keeps the members whose work location "
                            + "that applies on the date is this one. That is the one of the assignment, else the one "
                            + "of its org unit, else the one of the nearest unit above it in the default hierarchy.")
            @QueryParam("workLocation") String workLocation,
            @Parameter(
                    description = "The code of a cost center to keep. It keeps the members whose cost center that "
                            + "applies on the date is this one. That is the one of the assignment, else the one of "
                            + "its org unit, else the one of the nearest unit above it in the default hierarchy.")
            @QueryParam("costCenter") String costCenter,
            @Parameter(
                    description = "The number of entries to skip.",
                    schema = @Schema(minimum = "0", defaultValue = "0"))
            @QueryParam("first") Integer first,
            @Parameter(
                    description = "The largest number of entries to return.",
                    schema = @Schema(minimum = "0", maximum = "1000", defaultValue = "100"))
            @QueryParam("max") Integer max,
            @Parameter(description = "The cursor from the `Link` header of the page before. It is the name of its format "
                    + "and the values of the last row of that page. It cannot be used with `first`.")
            @QueryParam("after") String after,
            @Parameter(
                    description = "True to get the number of members on all pages in the header `X-Total-Count`. "
                            + "It costs one more query.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("count") boolean count) {
        context.requireRead();
        return Calls.run(context, () -> {
            MemberQuery query = new MemberQuery(asOf, includeDescendants, occupation, jobTitle, status, first, max,
                    hierarchy, after, seconded, workLocation, costCenter);
            return Pages.of(context, membership.members(id, query).stream().map(MemberRepresentation::of).toList(),
                    Page.of(first, max).max(),
                    // The values that the order of the list uses, as MembershipService reads them back.
                    member -> Cursor.encode(Cursor.MEMBERS, member.orgUnitId(),
                            Integer.toString(member.displayOrder()), member.validFrom(), member.assignmentId()),
                    count ? membership.countMembers(id, query) : null);
        });
    }

    @GET
    @Path("{id}/history")
    @Operation(
            operationId = "getOrgUnitHistory",
            summary = "Returns everything that is stored about an org unit over time.",
            description = "A caller who may read only the structure gets 403 when the realm attribute "
                    + "`fuhito.structure.daysAhead` limits how far ahead the caller may read.")
    @APIResponse(responseCode = "200", description = "The details of every period and the parent of every period.")
    @NotFound
    @ApiErrors
    public OrgUnitHistoryRepresentation history(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> {
            limit.requireEveryDate();
            return OrgUnitHistoryRepresentation.of(service.history(id));
        });
    }

    @GET
    @Path("{id}/role-holders")
    @Operation(
            operationId = "listOrgUnitRoleHolders",
            summary = "Lists who has a role over an org unit on a date.",
            description = "These are the roles over the unit, and the roles over a unit above in the default "
                    + "hierarchy that cover the units below. The nearest unit comes first, then the code of the "
                    + "role. Nearness is the number of steps up the default hierarchy on the date. They name "
                    + "people, so the read needs the full read permission.")
    @APIResponse(responseCode = "200", description = "The roles.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<UnitRoleRepresentation> roleHolders(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(description = "The code of a role to keep.")
            @QueryParam("role") String role,
            @Parameter(
                    description = "True to keep, for each role, only the holders of the nearest unit. A holder of "
                            + "a unit above is then left out when a nearer unit has a holder of the same role.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("nearest") boolean nearest,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless a unit sets its own time zone.",
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
        context.requireRead();
        return Calls.run(context, () -> unitRoles.holders(id, role, nearest, asOf, Page.of(first, max)).stream()
                .map(UnitRoleRepresentation::of)
                .toList());
    }

    @GET
    @Path("{id}/succession")
    @Operation(
            operationId = "getOrgUnitSuccession",
            summary = "Returns the org units that an org unit follows, and the ones that follow it.",
            description = "A step `succeed` of a reorganization records them. Several units before mean a merger. "
                    + "Several units after mean a split. For a caller who may read only the structure, the realm "
                    + "attribute `fuhito.structure.daysAhead` leaves out those after the last date the caller may "
                    + "read.")
    @APIResponse(responseCode = "200", description = "The units before and after, oldest first.")
    @NotFound
    @ApiErrors
    public OrgUnitSuccessionsRepresentation succession(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> OrgUnitSuccessionsRepresentation.of(
                limit.readable(service.successions(id))));
    }

    private OrgUnitRepresentation updated(String path, OrgUnitState state) {
        var unit = OrgUnitRepresentation.of(state);
        context.recordChange(Change.UPDATE, RESOURCE_TYPE, unit.id(), path, unit);
        return unit;
    }

    private static String path(String id) {
        return "fuhito/org-units/" + id;
    }

    private List<OrgUnitRepresentation> represent(List<OrgUnitState> states) {
        return states.stream().map(limit::readable).map(OrgUnitRepresentation::of).toList();
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
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy) {
        context.requireReadStructure();
        Supplier<OrgUnitRepresentation> read = () -> OrgUnitRepresentation.of(
                limit.readable(service.get(id, limit.checked(asOf), hierarchy)));
        return limit.active() ? Calls.run(context, read) : Calls.tagged(context, () -> state(id), read);
    }

    // The whole stored state of one resource, which its ETag covers.
    private Object state(String id) {
        return OrgUnitHistoryRepresentation.of(service.history(id));
    }
}
