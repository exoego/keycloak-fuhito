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
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.MemberQuery;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.OrgUnitHistory;
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
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
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
    private final ExternalIdService externalIds;
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
     * @param externalIds the IDs that systems have for the resources of the realm
     * @param membership the reads of members on the realm of the request
     * @param organizationLinks the links to Keycloak organizations of the realm
     * @param unitRoles the roles over org units of the realm
     * @param removals the removal of resources that were made by mistake
     * @param limit how far ahead a caller who may read only the structure may read it
     */
    OrgUnitsResource(RequestContext context, OrgUnitService service, ExternalIdService externalIds,
            MembershipService membership, OrganizationLinkService organizationLinks, UnitRoleService unitRoles,
            RemovalService removals, StructureLimit limit) {
        this.externalIds = externalIds;
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
        return Calls.run(context, () -> {
            Calls.body(body);
            var unit = represent(service.create(body.toNewOrgUnit()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, unit.id(), path(unit.id()), unit);
            return Response.status(Response.Status.CREATED).entity(unit).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "changeOrgUnitDetails",
            summary = "Replaces the details of an org unit from the `validFrom` date of the body.",
            description = "The details are the code, the names, the unit type, the display order, and the "
                    + "attributes. The parent does not change here. With `source` and `externalId`, the unit gets "
                    + "that ID of that system. Another `source` with no `externalId` fails with 400 and "
                    + "`invalid.value_fixed`. No other org unit may have the same code on a date of the change.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The unit as it is on that date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation changeDetails(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {OrgUnitRepresentation.class, VersionCheckRepresentation.class})))
            OrgUnitRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            Writes.requireSource(service.source(id), body.source(), body.externalId());
            Writes.requireRead(body, () -> represent(service.get(id,
                    Checks.date("validFrom", body.validFrom()).toString())), OrgUnitRepresentation.READ_ONLY);
            return updated(path(id), service.changeDetails(id, body.toChange(), body.source(), body.externalId()));
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
        return Calls.guarded(context, body, () -> version(id),
                () -> updated(path(id) + "/parent", service.move(id, body.parentId(), body.validFrom(), hierarchy)));
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteOrgUnit",
            summary = "Deletes an org unit that nothing refers to, with all its history.",
            description = "It works only when no unit had it as a parent, no assignment, employment, role, or succession names it, and it has no link to a Keycloak organization. Its periods and its parents go with it. End a unit that existed for a while instead.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The resource is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response remove(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the org unit that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
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
        return Calls.guarded(context, body, () -> version(id),
                () -> updated(path(id) + "/end", service.end(id, body.validTo())));
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
    @BadRequest
    @NotFound
    @ApiErrors
    public OrgUnitRepresentation removeEnd(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the org unit that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id),
                () -> updated(path(id) + "/end", service.removeEnd(id)));
    }

    @GET
    @Operation(
            operationId = "listOrgUnits",
            summary = "Lists the org units of a date that match the filters, at any depth.",
            description = "With no filter, the list has every org unit of the date. The filters apply together. "
                    + "Each unit comes with its parent in `hierarchy`. The order is the display order, then the "
                    + "phonetic name and the name of the primary language. To read every unit, follow the `Link` "
                    + "header of each response. The list is not in tree order. For the tree, start from the roots, "
                    + "then read the children of an org unit or one branch with its subtree.")
    @APIResponse(
            responseCode = "200",
            description = "The units that match.",
            headers = {
                @Header(
                        name = "Link",
                        description = "The URL of the next page, with `rel=\"next\"`. It is there when the page "
                                + "is full. A full page can be the last one, and the next page is then empty.",
                        schema = @Schema(type = SchemaType.STRING))},
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = OrgUnitRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response list(
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
                    description = "True for rollup units only, such as regions. False for the other units only. "
                            + "Leave it out for both.")
            @QueryParam("rollup") Boolean rollup,
            @Parameter(
                    description = "True for the units that are legal entities only, such as companies. False for "
                            + "the other units only. Leave it out for both. A unit is one when its unit type says so.")
            @QueryParam("legalEntity") Boolean legalEntity,
            @Parameter(
                    description = "True for the units with no parent in `hierarchy` only, the roots. A rollup unit is "
                            + "not in the default hierarchy, so it is no root there. In a hierarchy other than the "
                            + "default one, a root also has a child in it.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("roots") boolean roots,
            @Parameter(
                    description = "The code of the hierarchy of the parents and of `roots`. The default is the "
                            + "hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
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
        context.requireReadStructure();
        return Calls.run(context, () -> {
            Cursor.requireAlone(after, first);
            OrgUnitService.Search search = new OrgUnitService.Search(q, code, unitType, lateral, rollup,
                    legalEntity, roots, hierarchy, limit.checked(asOf));
            return Pages.of(context, represent(service.list(search, Page.of(first, max), after)),
                    Page.of(first, max).max(),
                    unit -> Cursor.encode(Cursor.ORG_UNITS, unit.id()));
        });
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countOrgUnits",
            summary = "Counts the org units that `GET /org-units` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of units on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
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
                    description = "True for rollup units only, such as regions. False for the other units only. "
                            + "Leave it out for both.")
            @QueryParam("rollup") Boolean rollup,
            @Parameter(
                    description = "True for the units that are legal entities only, such as companies. False for "
                            + "the other units only. Leave it out for both. A unit is one when its unit type says so.")
            @QueryParam("legalEntity") Boolean legalEntity,
            @Parameter(
                    description = "True for the units with no parent in `hierarchy` only, the roots. A rollup unit is "
                            + "not in the default hierarchy, so it is no root there. In a hierarchy other than the "
                            + "default one, a root also has a child in it.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("roots") boolean roots,
            @Parameter(
                    description = "The code of the hierarchy of `roots`. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireReadStructure();
        return Calls.run(context, () -> new CountRepresentation(service.count(new OrgUnitService.Search(q, code,
                unitType, lateral, rollup, legalEntity, roots, hierarchy, limit.checked(asOf)))));
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
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
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
    @Path("{id}/children/count")
    @Operation(
            operationId = "countOrgUnitChildren",
            summary = "Counts the children that `GET /org-units/{id}/children` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of children on all pages.")
    @BadRequest
    @NotFound
    @ApiErrors
    public CountRepresentation countChildren(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "True for lateral units only, such as committees. False for line units only. "
                            + "Leave it out for both.")
            @QueryParam("lateral") Boolean lateral) {
        context.requireReadStructure();
        return Calls.run(context, () -> new CountRepresentation(service.countChildren(id, limit.checked(asOf),
                hierarchy, lateral)));
    }

    @GET
    @Path("{id}/subtree")
    @Operation(
            operationId = "listOrgUnitSubtree",
            summary = "Lists an org unit and everything below it on a date.",
            description = "Each parent comes before its children. A rollup unit is not in the default hierarchy, so "
                    + "the list there is empty for it.")
    @APIResponse(responseCode = "200", description = "The unit and its descendants on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<OrgUnitRepresentation> subtree(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
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
    @Path("{id}/subtree/count")
    @Operation(
            operationId = "countOrgUnitSubtree",
            summary = "Counts the units that `GET /org-units/{id}/subtree` lists with the same filters.",
            description = "The org unit itself counts too, unless `lateral` leaves it out, or it is a rollup unit "
                    + "and the hierarchy is the default one.")
    @APIResponse(responseCode = "200", description = "The number of units on all pages.")
    @BadRequest
    @NotFound
    @ApiErrors
    public CountRepresentation countSubtree(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "True for lateral units only, such as committees. False for line units only. "
                            + "Leave it out for both.")
            @QueryParam("lateral") Boolean lateral) {
        context.requireReadStructure();
        return Calls.run(context, () -> new CountRepresentation(service.countSubtree(id, limit.checked(asOf),
                hierarchy, lateral)));
    }

    @GET
    @Path("{id}/path")
    @Operation(
            operationId = "getOrgUnitPath",
            summary = "Lists the units from the root down to an org unit on a date.",
            description = "A rollup unit is not in the default hierarchy, so its path there is empty.")
    @APIResponse(responseCode = "200", description = "The path, with the root first and the unit last.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<OrgUnitRepresentation> path(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
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
        return Calls.guarded(context, body, () -> version(id), () -> {
            Checks.fixed("orgUnitId", id, body.orgUnitId());
            // A link decides who is a member of the organization, as the admin API of Keycloak does.
            organizationLinks.organizationOf(id).ifPresent(context::requireManageOrganization);
            context.requireManageOrganization(body.organizationId());
            var unit = organizationLinks.link(id, body.organizationId());
            var link = new OrganizationLinkRepresentation(unit.id(), unit.version(), unit.keycloakOrganizationId());
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
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the org unit that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            organizationLinks.organizationOf(id).ifPresent(context::requireManageOrganization);
            var unit = organizationLinks.unlink(id);
            var link = new OrganizationLinkRepresentation(unit.id(), unit.version(), null);
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
                    + "out is removed, and the unit then uses the value from above. An empty text fails with "
                    + "`invalid.blank`.")
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
        return Calls.guarded(context, body, () -> version(id), () -> {
            Checks.fixed("orgUnitId", id, body.orgUnitId());
            var unit = service.setTime(id, body.timeZone(), body.dayStart());
            var settings = new TimeSettingsRepresentation(unit.id(), unit.version(), unit.timeZone(),
                    unit.dayStart());
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
                        schema = @Schema(type = SchemaType.STRING))},
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
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
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
                    + "them. The org unit then belongs to another legal entity, as `seconded` of a member says. False "
                    + "to keep everyone else. The default is everyone.")
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
            @QueryParam("after") String after) {
        context.requireRead();
        return Calls.run(context, () -> {
            MemberQuery query = new MemberQuery(asOf, includeDescendants, occupation, jobTitle, status, first, max,
                    hierarchy, after, seconded, workLocation, costCenter);
            Locale language = context.nameLanguage();
            return Pages.of(context, membership.members(id, query).stream()
                            .map(member -> MemberRepresentation.of(member, language)).toList(),
                    Page.of(first, max).max(),
                    // The values that the order of the list uses, as MembershipService reads them back.
                    member -> Cursor.encode(Cursor.MEMBERS, member.orgUnitId(),
                            Integer.toString(member.displayOrder()), member.validFrom(), member.assignmentId()));
        });
    }

    @GET
    @Path("{id}/members/count")
    @Operation(
            operationId = "countOrgUnitMembers",
            summary = "Counts the members that `GET /org-units/{id}/members` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of members on all pages.")
    @BadRequest
    @NotFound
    @ApiErrors
    public CountRepresentation countMembers(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy,
            @Parameter(
                    description = "True to also count the members of every unit below.",
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
                    + "them. The org unit then belongs to another legal entity, as `seconded` of a member says. False "
                    + "to keep everyone else. The default is everyone.")
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
            @QueryParam("costCenter") String costCenter) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(membership.countMembers(id, new MemberQuery(asOf,
                includeDescendants, occupation, jobTitle, status, null, null, hierarchy, null, seconded,
                workLocation, costCenter))));
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
    @BadRequest
    @ApiErrors
    public OrgUnitHistoryRepresentation history(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> {
            limit.requireEveryDate();
            return history(service.history(id));
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
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
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
        return Calls.run(context, () -> UnitRolesResource.represent(
                unitRoles.holders(id, role, nearest, asOf, Page.of(first, max)), externalIds));
    }

    @GET
    @Path("{id}/role-holders/count")
    @Operation(
            operationId = "countOrgUnitRoleHolders",
            summary = "Counts the roles that `GET /org-units/{id}/role-holders` lists with the same filters.",
            description = "They name people, so the read needs the full read permission.")
    @APIResponse(responseCode = "200", description = "The number of roles on all pages.")
    @BadRequest
    @NotFound
    @ApiErrors
    public CountRepresentation countRoleHolders(
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
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(unitRoles.countHolders(id, role, nearest, asOf)));
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
    @BadRequest
    @ApiErrors
    public OrgUnitSuccessionsRepresentation succession(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> OrgUnitSuccessionsRepresentation.of(
                limit.readable(service.successions(id))));
    }

    private OrgUnitRepresentation updated(String path, OrgUnitState state) {
        var unit = represent(state);
        context.recordChange(Change.UPDATE, RESOURCE_TYPE, unit.id(), path, unit);
        return unit;
    }

    private static String path(String id) {
        return "fuhito/org-units/" + id;
    }

    // The IDs of every unit of a list are read with one query.
    private List<OrgUnitRepresentation> represent(List<OrgUnitState> states) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.ORG_UNIT,
                states.stream().map(state -> state.unit().id()).toList());
        Locale language = context.nameLanguage();
        return states.stream().map(limit::readable).map(state -> OrgUnitRepresentation.of(state, ids, language))
                .toList();
    }

    private OrgUnitRepresentation represent(OrgUnitState state) {
        return OrgUnitRepresentation.of(state,
                externalIds.lists(ExternalId.Kind.ORG_UNIT, List.of(state.unit().id())), context.nameLanguage());
    }

    private OrgUnitHistoryRepresentation history(OrgUnitHistory history) {
        return OrgUnitHistoryRepresentation.of(history,
                externalIds.lists(ExternalId.Kind.ORG_UNIT, List.of(history.unit().id())), context.nameLanguage());
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
                    description = "The date to read. The default is today for the org unit. It is the date of the "
                            + "realm unless the unit or a unit above it sets its own time zone or day start.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(description = "The code of the hierarchy. The default is the hierarchy `default`.")
            @QueryParam("hierarchy") String hierarchy) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(limit.readable(service.get(id, limit.checked(asOf), hierarchy))));
    }

    private long version(String id) {
        return service.version(id);
    }
}
