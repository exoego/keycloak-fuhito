package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.ResourceType;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.RealmKindService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The IDs that systems have for the resources of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.GENERAL)
public class ExternalIdsResource {

    /**
     * The resource type of IDs of other systems in the admin event log.
     */
    public static final ResourceType RESOURCE_TYPE = ResourceType.FUHITO_EXTERNAL_ID;

    private static final String KINDS = "ORG_UNIT, PERSON, EMPLOYMENT, ASSIGNMENT, QUALIFICATION, REPORTING_LINE, "
            + "UNIT_ROLE, CHANGE_ORDER, REF_ITEM, REF_KIND, HIERARCHY, or ATTRIBUTE_DEFINITION.";

    private final RequestContext context;
    private final ExternalIdService service;
    private final OrgUnitService orgUnits;
    private final RealmKindService refKinds;
    private final StructureLimit limit;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param orgUnits the org units of the realm of the request
     * @param refKinds the kinds of reference data of the realm of the request
     * @param limit how far ahead a caller who may read only the structure may read it
     */
    ExternalIdsResource(RequestContext context, ExternalIdService service, OrgUnitService orgUnits,
            RealmKindService refKinds, StructureLimit limit) {
        this.context = context;
        this.service = service;
        this.orgUnits = orgUnits;
        this.refKinds = refKinds;
        this.limit = limit;
    }

    @GET
    @Operation(
            operationId = "listExternalIds",
            summary = "Lists every ID that systems have for one resource.",
            description = "The ID of the system that made the resource comes first, then the others by system. "
                    + "The IDs of an org unit, a reference item, a kind of reference data, a hierarchy, and an "
                    + "attribute definition need the permission to read the org structure. The others need the "
                    + "full read.")
    @APIResponse(responseCode = "200", description = "The IDs.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<ExternalIdRepresentation> list(
            @Parameter(description = "The kind of resource, " + KINDS, required = true)
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource.", required = true)
            @QueryParam("resourceId") String resourceId) {
        return Calls.run(context, () -> {
            requireRead(resourceType);
            return service.list(resourceType, resourceId).stream().map(ExternalIdRepresentation::of).toList();
        });
    }

    @GET
    @Path("lookup")
    @Operation(
            operationId = "lookUpExternalId",
            summary = "Finds the resource that an ID of a system names.",
            description = "Every ID of every system counts, also the one of the system that made the resource.")
    @APIResponse(responseCode = "200", description = "The ID with its resource.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ExternalIdRepresentation lookUp(
            @Parameter(description = "The kind of resource, " + KINDS, required = true)
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The name of the system.", required = true)
            @QueryParam("source") String source,
            @Parameter(description = "The ID in that system.", required = true)
            @QueryParam("externalId") String externalId) {
        return Calls.run(context, () -> {
            requireRead(resourceType);
            ExternalIdService.View found = service.find(resourceType, source, externalId);
            // A unit that starts after the last date that a limited caller may read stays unknown to it.
            if (found.kind() == ExternalId.Kind.ORG_UNIT && limit.hides(
                    orgUnits.history(found.resourceId()).versions().getFirst().version().period().from())) {
                throw FuhitoException.notFound("external_id.unknown",
                        Map.of("source", found.source(), "externalId", found.externalId()));
            }
            return ExternalIdRepresentation.of(found);
        });
    }

    @PUT
    @Operation(
            operationId = "putExternalId",
            summary = "Stores the ID of one system for a resource, or replaces it.",
            description = "A resource has at most one ID for each system. One ID of one system names one resource "
                    + "of a kind. The ID of the system that made the resource can change here too. A request that "
                    + "replaces a stored ID needs the `version` of that ID that the caller read.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The stored ID.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ExternalIdRepresentation put(ExternalIdRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Calls.body(body);
            lockOwner(body.resourceType(), body.resourceId());
            requireVersion(body.resourceType(), body.resourceId(), body.source(), body.version());
            ExternalIdService.Put put = service.put(body.resourceType(), body.resourceId(), body.source(),
                    body.externalId());
            ExternalIdService.View view = put.view();
            // Whether the system made the resource does not depend on the ID, so the response has the value of a read.
            Checks.fixed("own", view.own(), body.own());
            var stored = ExternalIdRepresentation.of(view);
            context.recordChange(put.created() ? Change.CREATE : Change.UPDATE, RESOURCE_TYPE, view.rowId(),
                    path(view.rowId()), stored);
            return stored;
        });
    }

    @DELETE
    @Operation(
            operationId = "deleteExternalId",
            summary = "Removes the ID of one system from a resource.",
            description = "The resource keeps the name of the system that made it in `source`, also when this ID "
                    + "is the one of that system.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The ID is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The kind of resource, " + KINDS, required = true)
            @QueryParam("resourceType") String resourceType,
            @Parameter(description = "The ID of the resource.", required = true)
            @QueryParam("resourceId") String resourceId,
            @Parameter(description = "The name of the system.", required = true, schema = @Schema(maxLength = 64))
            @QueryParam("source") String source,
            @Parameter(
                    description = "The version of the ID that the caller read.",
                    required = true)
            @QueryParam("version") Long version) {
        context.requireWrite();
        return Calls.run(context, () -> {
            lockOwner(resourceType, resourceId);
            requireVersion(resourceType, resourceId, source, version);
            ExternalIdService.View view = service.remove(resourceType, resourceId, source);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, view.rowId(), path(view.rowId()), null);
            return Response.noContent().build();
        });
    }

    // The IDs are part of the resource that has them, so a write of one counts up the version of that resource.
    private void lockOwner(String resourceType, String resourceId) {
        ExternalId.Kind kind = Checks.optionalEnum("resourceType", ExternalId.Kind.class,
                Checks.required("resourceType", resourceType), null);
        if (resourceId == null) {
            return;
        }
        String collection = switch (kind) {
            case ORG_UNIT -> "org-units";
            case PERSON -> "people";
            case EMPLOYMENT -> "employments";
            case ASSIGNMENT -> "assignments";
            case QUALIFICATION -> "qualifications";
            case REPORTING_LINE -> "reporting-lines";
            case UNIT_ROLE -> "unit-roles";
            case CHANGE_ORDER -> "change-orders";
            case REF_ITEM -> "ref-items";
            case REF_KIND -> "ref-kinds";
            case HIERARCHY -> "hierarchies";
            case ATTRIBUTE_DEFINITION -> "attribute-definitions";
        };
        // The rows of a kind of reference data are locked by the code that its path names.
        List<String> ids = kind == ExternalId.Kind.REF_KIND ? refKinds.codeOf(resourceId).stream().toList()
                : List.of(resourceId);
        context.lock(Map.of(collection, ids));
    }

    // A new ID needs no version. JPA checks the version of the row again when it writes it.
    private void requireVersion(String resourceType, String resourceId, String source, Long version) {
        service.list(resourceType, resourceId).stream()
                .filter(stored -> stored.source().equals(source))
                .findFirst()
                .ifPresent(stored -> Checks.version(version, stored.version()));
    }

    private void requireRead(String resourceType) {
        if (ExternalIdService.isStructure(resourceType)) {
            context.requireReadStructure();
        } else {
            context.requireRead();
        }
    }

    private static String path(String rowId) {
        return "fuhito/external-ids/" + rowId;
    }
}
