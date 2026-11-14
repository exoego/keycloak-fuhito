package net.exoego.keycloak.fuhito.rest;

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

    /**
     * Changes many org units on one date in one transaction.
     * When one step fails, nothing is written, and the error has the position of the step in {@code params.step}.
     *
     * @param body the effective date and the steps
     * @return what each step did
     */
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
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var results = service.apply(body.effectiveDate(), body.steps()).stream()
                    .map(result -> new ReorganizationRepresentation.Result(result.op(),
                            OrgUnitRepresentation.of(result.unit())))
                    .toList();
            for (var result : results) {
                String path = "fuhito/org-units/" + result.unit().id();
                switch (result.op()) {
                    case "create" -> record(Change.CREATE, path, result.unit());
                    case "move" -> record(Change.UPDATE, path + "/parent", result.unit());
                    case "end" -> record(Change.UPDATE, path + "/end", result.unit());
                    default -> record(Change.UPDATE, path, result.unit());
                }
            }
            return new ReorganizationRepresentation(null, null, results);
        });
    }

    private void record(Change change, String path, OrgUnitRepresentation unit) {
        context.recordChange(change, OrgUnitsResource.RESOURCE_TYPE, unit.id(), path, unit);
    }
}
