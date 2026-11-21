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
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.AttributeDefinitionService;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
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
    public static final String RESOURCE_TYPE = "FUHITO_ATTRIBUTE_DEFINITION";

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
                    + "definition fails. Stored values are not checked until their next write.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new definition.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AttributeDefinitionRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response create(AttributeDefinitionRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Calls.body(body);
            Writes.requireNoSource(body.source(), body.externalId());
            var definition = represent(externalIds.create(ExternalId.Kind.ATTRIBUTE_DEFINITION, body.source(),
                    body.externalId(), () -> service.create(body.target(), body.name(), body.type(), body.refKind(),
                            body.multiple(), body.required()), AttributeDefinition::id));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, definition.id(), path(definition.id()), definition);
            return Response.status(Response.Status.CREATED).entity(definition).build();
        });
    }

    @PUT
    @Path("{id}")
    @Operation(
            operationId = "replaceAttributeDefinition",
            summary = "Replaces the type and the rule of a definition.",
            description = "The kind of resource and the name never change. The body can leave them out or give the "
                    + "stored ones. Another kind of resource or name fails with 400 and `invalid.value_fixed`. Stored "
                    + "values stay as they are. With `source` and `externalId`, the definition gets that ID of that "
                    + "system. `source` with no `externalId` fails with 400 and `invalid.value_fixed`, because a "
                    + "definition keeps no system that made it.")
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
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            AttributeDefinition stored = service.get(id);
            Checks.fixed("target", stored.target().name(), body.target());
            Checks.fixed("name", stored.name(), body.name());
            Writes.requireNoSource(body.source(), body.externalId());
            Writes.requireRead(body, () -> represent(stored), AttributeDefinitionRepresentation.READ_ONLY);
            var definition = represent(externalIds.write(ExternalId.Kind.ATTRIBUTE_DEFINITION, id, body.source(),
                    body.externalId(), () -> service.replace(id, body.type(), body.refKind(), body.multiple(),
                            body.required())));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), definition);
            return definition;
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteAttributeDefinition",
            summary = "Deletes a definition.",
            description = "Stored values stay as they are.")
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
            @QueryParam("version") Long version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
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
