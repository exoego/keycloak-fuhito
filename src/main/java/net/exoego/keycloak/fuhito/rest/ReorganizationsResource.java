package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import java.util.ArrayList;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
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

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public ReorganizationsResource(RequestContext context, ReorganizationService service) {
        this.context = context;
        this.service = service;
    }

    @POST
    @Operation(
            operationId = "applyReorganization",
            summary = "Changes many org units on one date, in one transaction.",
            description = "A failed step writes nothing.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "What each step did.")
    @BadRequest
    @NotFound
    @ApiErrors
    public ReorganizationRepresentation apply(ReorganizationRepresentation body) {
        context.requireWrite();
        return Calls.once(context, body, ReorganizationRepresentation.class, () -> Calls.run(context, () -> {
            Checks.required("body", body);
            List<ReorganizationRepresentation.Result> results = new ArrayList<>();
            for (ReorganizationService.Result result : service.apply(body.effectiveDate(), body.steps())) {
                var unit = OrgUnitRepresentation.of(result.unit());
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
        }));
    }

    private void record(Change change, String path, OrgUnitRepresentation unit) {
        context.recordChange(change, OrgUnitsResource.RESOURCE_TYPE, unit.id(), path, unit);
    }
}
