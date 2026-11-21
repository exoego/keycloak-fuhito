package net.exoego.keycloak.fuhito.rest;

import java.util.ArrayList;
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
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.ResourceType;
import net.exoego.keycloak.fuhito.service.AttributeDefinitionService;
import net.exoego.keycloak.fuhito.service.AttributeDefinitionService.Written;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The definitions of free attributes of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.REF_ITEMS)
public class AttributeDefinitionsResource {

    /**
     * The resource type of definitions of attributes in the admin event log.
     */
    public static final ResourceType RESOURCE_TYPE = ResourceType.FUHITO_ATTRIBUTE_DEFINITION;

    // What the endpoints that fill or clear say about it.
    private static final String FILL = "A create or a change with `required` true, and every delete, hold the realm "
            + "alone, as an import does. Other writes of the realm wait for them. A fill or a clear writes every row "
            + "or none. The version of each resource whose rows it changes counts up once, and the resource gets an "
            + "entry in the change log and an admin event `UPDATE` with no representation.";

    private final RequestContext context;
    private final AttributeDefinitionService service;
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public AttributeDefinitionsResource(RequestContext context, AttributeDefinitionService service,
            ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.externalIds = externalIds;
    }

    @GET
    @Operation(
            operationId = "listAttributeDefinitions",
            summary = "Lists the definitions of free attributes, by kind of resource and name.")
    @APIResponse(responseCode = "200", description = "The definitions.")
    @BadRequest
    @ApiErrors
    public List<AttributeDefinitionRepresentation> list(
            @Parameter(
                    description = "The kind of resource to keep.",
                    schema = @Schema(enumeration = {"ORG_UNIT", "PERSON", "EMPLOYMENT_TERMS", "ASSIGNMENT",
                        "QUALIFICATION", "REF_ITEM"}))
            @QueryParam("target") String target) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.list(target)));
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getAttributeDefinition", summary = "Returns one definition.")
    @APIResponse(responseCode = "200", description = "The definition.")
    @NotFound
    @BadRequest
    @ApiErrors
    public AttributeDefinitionRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireReadStructure();
        return Calls.run(context, () -> represent(service.get(id)));
    }

    @POST
    @Operation(
            operationId = "createAttributeDefinition",
            summary = "Defines a free attribute of a kind of resource.",
            description = "Later writes of the resource are checked against it. A write with a name that has no "
                    + "definition fails. A required attribute needs a value in every row of the resource, also in "
                    + "each past version of a resource with dates. When a row has none, the create needs "
                    + "`fillValue` and puts it into each such row. " + FILL)
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new definition.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AttributeDefinitionRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(AttributeDefinitionRepresentation body) {
        requireWrite(body != null && Boolean.TRUE.equals(body.required()));
        return Calls.run(context, () -> {
            Calls.body(body);
            Writes.requireNoSource(body.source(), body.externalId());
            Written written = externalIds.create(ExternalId.Kind.ATTRIBUTE_DEFINITION, body.source(),
                    body.externalId(), () -> service.create(body.target(), body.name(), body.type(), body.refKind(),
                            body.multiple(), body.required(), body.fillValue()), each -> each.definition().id());
            List<Write> writes = new ArrayList<>(List.of(new Write.OfAttributeDefinition(true, written.definition())));
            writes.addAll(written.values());
            Writes.recordAll(context, externalIds, writes);
            return Response.status(Response.Status.CREATED).entity(represent(written.definition())).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "replaceAttributeDefinition",
            summary = "Changes whether a definition is required.",
            description = "The kind of resource, the name, the type, `refKind`, and `multiple` never change. The body "
                    + "can leave them out or give the stored ones. Another value fails with 400 and "
                    + "`invalid.value_fixed`. To change the type, create a definition with another name, set its "
                    + "values through an import, and delete the old one with `clearValues=true`. A change from not "
                    + "required to required needs `fillValue` when a row of the resource has no value, also a row "
                    + "of a past version, and puts it into each such row. " + FILL + " With `source` and "
                    + "`externalId`, the definition gets that ID of that system. `source` with no `externalId` fails "
                    + "with 400 and `invalid.value_fixed`, because a definition keeps no system that made it.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The changed definition.")
    @BadRequest
    @NotFound
    @ApiErrors
    public AttributeDefinitionRepresentation replace(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(allOf = {AttributeDefinitionRepresentation.class, VersionCheckRepresentation.class})))
            AttributeDefinitionRepresentation body) {
        requireWrite(body != null && Boolean.TRUE.equals(body.required()));
        return Calls.guarded(context, body, () -> version(id), () -> {
            AttributeDefinition stored = service.get(id);
            Checks.fixed("target", stored.target().name(), body.target());
            Checks.fixed("name", stored.name(), body.name());
            Writes.requireNoSource(body.source(), body.externalId());
            Writes.requireRead(body, () -> represent(stored), AttributeDefinitionRepresentation.READ_ONLY);
            Written written = externalIds.write(ExternalId.Kind.ATTRIBUTE_DEFINITION, id, body.source(),
                    body.externalId(), () -> service.replace(id, body.type(), body.refKind(), body.multiple(),
                            body.required(), body.fillValue()));
            List<Write> writes = new ArrayList<>(List.of(new Write.OfAttributeDefinition(false, written.definition())));
            writes.addAll(written.values());
            Writes.recordAll(context, externalIds, writes);
            return represent(written.definition());
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteAttributeDefinition",
            summary = "Deletes a definition.",
            description = "While a row of the resource has a value of the name, also a row of a past version, the "
                    + "delete fails with 409 and `attribute.in_use`, which names the number of such rows in "
                    + "`count`. A row with null for the name has no value. With `clearValues=true`, it removes the "
                    + "name from each such row first. " + FILL)
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The definition is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response delete(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the definition that the caller read.",
                    required = true)
            @QueryParam("version") Long version,
            @Parameter(
                    description = "True to remove the name from every row of the resource with a value of it, "
                            + "then delete the definition.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("clearValues") boolean clearValues) {
        // Without the lock alone, a write that runs at the same time could store a value of the name.
        context.requireBulkWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            List<Write> writes = new ArrayList<>(service.delete(id, clearValues));
            writes.add(new Write.Removed(RESOURCE_TYPE, id, path(id)));
            Writes.recordAll(context, externalIds, writes);
            return Response.noContent().build();
        });
    }

    // A write that may make an attribute required holds the realm alone, so that no write of a row without a value
    // runs at the same time.
    private void requireWrite(boolean required) {
        if (required) {
            context.requireBulkWrite();
        } else {
            context.requireWrite();
        }
    }

    private static String path(String id) {
        return "fuhito/attribute-definitions/" + id;
    }

    // The IDs of every definition of a list are read with one query.
    private List<AttributeDefinitionRepresentation> represent(List<AttributeDefinition> definitions) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.ATTRIBUTE_DEFINITION,
                definitions.stream().map(AttributeDefinition::id).toList());
        return definitions.stream().map(definition -> AttributeDefinitionRepresentation.of(definition, ids)).toList();
    }

    private AttributeDefinitionRepresentation represent(AttributeDefinition definition) {
        return represent(List.of(definition)).getFirst();
    }

    private long version(String id) {
        return service.get(id).version();
    }
}
