package net.exoego.keycloak.fuhito.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.ReorganizationService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Reorganizations of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.ORG_UNITS)
public class ReorganizationsResource {

    private final RequestContext context;
    private final ReorganizationService service;
    private final ExternalIdService externalIds;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public ReorganizationsResource(RequestContext context, ReorganizationService service,
            ExternalIdService externalIds) {
        this.context = context;
        this.service = service;
        this.externalIds = externalIds;
    }

    @POST
    @Operation(
            operationId = "applyReorganization",
            summary = "Changes many org units on one date, in one transaction.",
            description = "A failed step writes nothing. A step that changes a stored unit names the `version` of "
                    + "the unit that the caller read. An old version fails with 409 and `version.conflict`, and "
                    + "`step` in the values of the error. A step that gives a field that its operation does not read "
                    + "fails with 400 and `reorganization.field_unused` before any step runs.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "What each step did.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ReorganizationRepresentation apply(ReorganizationRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            // The service checks the empty values of each step, so that the error names the step.
            Checks.required("body", body);
            List<ReorganizationRepresentation.Result> results = new ArrayList<>();
            // A second request that changes the same unit waits for the first one.
            List<ReorganizationService.Result> applied = service.apply(body.effectiveDate(), body.steps(),
                    ids -> context.lock(Map.of("org-units", ids)));
            ExternalIdLists ids = externalIds.lists(ExternalId.Kind.ORG_UNIT,
                    applied.stream().map(result -> result.unit().unit().id()).distinct().toList());
            Locale language = context.nameLanguage();
            for (ReorganizationService.Result result : applied) {
                var unit = OrgUnitRepresentation.of(result.unit(), ids, language);
                String path = "fuhito/org-units/" + unit.id();
                List<String> successorIds = null;
                switch (result.op()) {
                    case "create" -> record(Change.CREATE, path, unit);
                    case "move" -> record(Change.UPDATE, path + "/parent", unit);
                    case "end" -> record(Change.UPDATE, path + "/end", unit);
                    case "succeed" -> {
                        record(Change.UPDATE, path + "/end", unit);
                        var successions = OrgUnitSuccessionsRepresentation.of(result.successions());
                        context.recordChange(Change.UPDATE, OrgUnitsResource.RESOURCE_TYPE, unit.id(),
                                path + "/succession", successions);
                        successorIds = successions.successors().stream()
                                .map(OrgUnitSuccessionsRepresentation.Link::orgUnitId)
                                .toList();
                    }
                    default -> record(Change.UPDATE, path, unit);
                }
                results.add(new ReorganizationRepresentation.Result(result.op(), unit, successorIds));
            }
            return new ReorganizationRepresentation(null, null, results);
        });
    }

    private void record(Change change, String path, OrgUnitRepresentation unit) {
        context.recordChange(change, OrgUnitsResource.RESOURCE_TYPE, unit.id(), path, unit);
    }
}
