package net.exoego.keycloak.fuhito.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.Cursor;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.EmploymentView;
import net.exoego.keycloak.fuhito.service.ExternalIdLists;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.LeaveService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.OrgUnitState;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.PromotionService;
import net.exoego.keycloak.fuhito.service.TermsChange;
import net.exoego.keycloak.fuhito.service.Today;
import net.exoego.keycloak.fuhito.service.RemovalService;
import net.exoego.keycloak.fuhito.store.ResourceRemoval;
import net.exoego.keycloak.fuhito.store.WriteScopes;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The employments of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.EMPLOYMENTS)
public class EmploymentsResource {

    /**
     * The resource type of employments in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_EMPLOYMENT";

    private final RequestContext context;
    private final EmploymentService service;
    private final ExternalIdService externalIds;
    private final PersonService people;
    private final AssignmentService assignments;
    private final OrgUnitService orgUnits;
    private final PromotionService promotions;
    private final LeaveService leaves;
    private final RemovalService removals;
    private final Today today;
    private final WriteScopes scopes;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     * @param externalIds the IDs that systems have for the resources of the realm
     * @param people the operations on people of the realm
     * @param assignments the reads of assignments of the realm
     * @param orgUnits the operations on org units of the realm
     * @param promotions the promotion of the realm
     * @param leaves the start and the end of a leave in the realm
     * @param removals the removal of resources that were made by mistake
     * @param today the date of the realm
     * @param scopes finds the other resources that a write changes, to lock them
     */
    public EmploymentsResource(RequestContext context, EmploymentService service, ExternalIdService externalIds,
            PersonService people, AssignmentService assignments, OrgUnitService orgUnits, PromotionService promotions,
            LeaveService leaves, RemovalService removals, Today today, WriteScopes scopes) {
        this.scopes = scopes;
        this.externalIds = externalIds;
        this.today = today;
        this.removals = removals;
        this.leaves = leaves;
        this.promotions = promotions;
        this.orgUnits = orgUnits;
        this.context = context;
        this.service = service;
        this.people = people;
        this.assignments = assignments;
    }

    @GET
    @Operation(
            operationId = "listEmployments",
            summary = "Lists the employments of the realm, ordered by ID, each with all its terms.",
            description = "To read every employment, follow the `Link` header of each response.")
    @APIResponse(
            responseCode = "200",
            description = "The employments.",
            headers = {
                @Header(
                        name = "Link",
                        description = "The URL of the next page, with `rel=\"next\"`. It is there when the page "
                                + "is full. A full page can be the last one, and the next page is then empty.",
                        schema = @Schema(type = SchemaType.STRING))},
            content = @Content(mediaType = "application/json",
                    schema = @Schema(type = SchemaType.ARRAY, implementation = EmploymentRepresentation.class)))
    @BadRequest
    @ApiErrors
    public Response list(
            @Parameter(description = "A staff number to keep.")
            @QueryParam("staffNumber") String staffNumber,
            @Parameter(description = "The ID of the org unit that employs, to keep.")
            @QueryParam("employerId") String employerId,
            @Parameter(description = "The code of the external employer that employs, to keep. An item that is no "
                    + "longer active can be named too.")
            @QueryParam("externalEmployer") String externalEmployer,
            @Parameter(
                    description = "A date to keep the employments that hold on it. Leave it out for every date.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The earliest contract end to keep. It is the `contractEndDate` of the terms on "
                            + "`asOf`, or on today in the time zone of the realm when `asOf` is left out.",
                    schema = @Schema(format = "date"))
            @QueryParam("contractEndFrom") String contractEndFrom,
            @Parameter(
                    description = "The first date after the latest contract end to keep.",
                    schema = @Schema(format = "date"))
            @QueryParam("contractEndTo") String contractEndTo,
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
        context.requireRead();
        return Calls.run(context, () -> {
            Cursor.requireAlone(after, first);
            EmploymentService.Search search = new EmploymentService.Search(staffNumber, employerId,
                    externalEmployer, asOf, contractEndFrom, contractEndTo, today.ofRealm());
            return Pages.of(context, represent(service.list(search, Page.of(first, max), after)),
                    Page.of(first, max).max(),
                    employment -> Cursor.encode(Cursor.EMPLOYMENTS, employment.id()));
        });
    }

    @GET
    @Path("count")
    @Operation(
            operationId = "countEmployments",
            summary = "Counts the employments that `GET /employments` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of employments on all pages.")
    @BadRequest
    @ApiErrors
    public CountRepresentation count(
            @Parameter(description = "A staff number to keep.")
            @QueryParam("staffNumber") String staffNumber,
            @Parameter(description = "The ID of the org unit that employs, to keep.")
            @QueryParam("employerId") String employerId,
            @Parameter(description = "The code of the external employer that employs, to keep. An item that is no "
                    + "longer active can be named too.")
            @QueryParam("externalEmployer") String externalEmployer,
            @Parameter(
                    description = "A date to keep the employments that hold on it. Leave it out for every date.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "The earliest contract end to keep. It is the `contractEndDate` of the terms on "
                            + "`asOf`, or on today in the time zone of the realm when `asOf` is left out.",
                    schema = @Schema(format = "date"))
            @QueryParam("contractEndFrom") String contractEndFrom,
            @Parameter(
                    description = "The first date after the latest contract end to keep.",
                    schema = @Schema(format = "date"))
            @QueryParam("contractEndTo") String contractEndTo) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(service.count(new EmploymentService.Search(
                staffNumber, employerId, externalEmployer, asOf, contractEndFrom, contractEndTo,
                today.ofRealm()))));
    }

    @POST
    @Operation(operationId = "startEmployment", summary = "Starts an employment of a user.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The new employment.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = EmploymentRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response start(EmploymentRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Calls.body(body);
            var employment = represent(service.start(body.toNewEmployment(
                    Writes.personId(context, people, "personId", body.personId(), "userId", body.userId()))));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, employment.id(), "fuhito/employments/" + employment.id(),
                    employment);
            return Response.status(Response.Status.CREATED).entity(employment).build();
        });
    }

    @DELETE
    @Path("{id}")
    @Operation(
            operationId = "deleteEmployment",
            summary = "Deletes an employment that nothing refers to, with all its history.",
            description = "It works only when the employment has no assignment and no role over an org unit, and no employment continues it. Its terms go with it. End an employment that held for a while instead.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The resource is gone.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response remove(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the employment that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            removals.remove(ResourceRemoval.Kind.EMPLOYMENT, id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, "fuhito/employments/" + id, null);
            return Response.noContent().build();
        });
    }

    @PUT
    @Path("{id}/terms")
    @Operation(
            operationId = "changeEmploymentTerms",
            summary = "Replaces the terms of an employment from the `validFrom` date of the body.",
            description = "The terms are the employment type, the grade, the status with its leave reason, the work "
                    + "percentage, the end of a fixed-term contract, and the attributes.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation changeTerms(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EmploymentRepresentation.Terms body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            Writes.requireRead(body, () -> Writes.periodOn(represent(service.get(id)).terms(),
                    EmploymentRepresentation.Terms::validFrom, EmploymentRepresentation.Terms::validTo,
                    Checks.date("validFrom", body.validFrom())), EmploymentRepresentation.Terms.READ_ONLY);
            var employment = represent(service.changeTerms(id, new TermsChange(body.validFrom(),
                    body.employmentType(), body.grade(), body.status(), body.leaveReason(), body.workPercent(),
                    body.contractEndDate(), body.attributes())));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/terms", employment);
            return employment;
        });
    }

    @PUT
    @Path("{id}/end")
    @Operation(
            operationId = "endEmployment",
            summary = "Sets the end of an employment.",
            description = "An earlier end is how a person leaves. It also ends the terms and the assignments on "
                    + "that date. A later end extends a fixed-term employment. A later end that comes after the "
                    + "start of an employment that continues this one fails with 409 and "
                    + "`employment.continues_holds`.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation end(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            EmploymentEndRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> scopes.employment(id), () -> {
            var employment = represent(service.end(id, body.validTo(), body.endReason()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/end", employment);
            return employment;
        });
    }

    @POST
    @Path("{id}/promotion")
    @Operation(
            operationId = "promote",
            summary = "Promotes a person on a date, in one write.",
            description = "It sets the grade of the employment, and the job title or the head flag of one "
                    + "assignment, from the date on. Every value that the request does not name stays. The change "
                    + "lasts until the next change that is already stored. A failure writes nothing.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "200",
            description = "The employment and the assignment after the promotion.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = PromotionRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public PromotionRepresentation promote(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            PromotionRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> scopes.employment(id), () -> {
            PromotionService.Promotion promotion = promotions.promote(id, body.toRequest());
            var employment = represent(promotion.employment());
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/promotion",
                    employment);
            AssignmentRepresentation assignment = null;
            if (promotion.assignment() != null) {
                assignment = AssignmentsResource.represent(List.of(promotion.assignment()), externalIds).getFirst();
                context.recordChange(Change.UPDATE, AssignmentsResource.RESOURCE_TYPE, assignment.id(),
                        "fuhito/assignments/" + assignment.id() + "/details", assignment);
            }
            return PromotionRepresentation.of(employment, assignment);
        });
    }

    @POST
    @Path("{id}/leave")
    @Operation(
            operationId = "startLeave",
            summary = "Puts a person on leave from a date.",
            description = "The status of the employment is `ON_LEAVE` from the date. Every other value of the "
                    + "terms stays. With `validTo`, the status of before the leave comes back on that date, in "
                    + "the same write. A change of terms that is stored for a later date rejects the leave.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation startLeave(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            LeaveRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            var employment = represent(leaves.start(id, body.validFrom(), body.validTo(),
                    body.reason()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/leave", employment);
            return employment;
        });
    }

    @POST
    @Path("{id}/return")
    @Operation(
            operationId = "endLeave",
            summary = "Ends the leave of a person on a date.",
            description = "The status of the employment is `ACTIVE` from the date. Every other value of the terms "
                    + "stays. A return that was stored for a later date stays too, and changes nothing more.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation endLeave(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            ReturnRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> {
            var employment = represent(leaves.end(id, body.validFrom()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/return", employment);
            return employment;
        });
    }

    @POST
    @Path("{id}/transfer")
    @Operation(
            operationId = "transferEmployment",
            summary = "Moves a person to another employer on a date, in one write.",
            description = "The employment ends on the date. A new employment at the new employer starts on the "
                    + "same date. It gets the terms, the assignments, the reporting lines, and the roles over org "
                    + "units of the old one from the date on, also the ones that were stored for a later date. It "
                    + "names the old employment in `continuesEmploymentId`. It keeps the end of the old one, and the "
                    + "new employer must exist until that end. The contract in the terms of the date must not end "
                    + "before the date. An assignment, a line, or a role that "
                    + "starts on the date or later goes away from the old employment, and its copy takes its IDs "
                    + "of other systems. One that holds before the date only ends and keeps its IDs. Its copy "
                    + "gets none. A failure writes nothing. An employment with an external employer cannot move. "
                    + "End it and start another employment.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The two employments, and what the new one got.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = TransferRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response transfer(
            @Parameter(description = "The ID of the employment at the old employer.")
            @PathParam("id") String id,
            TransferRepresentation body) {
        context.requireWrite();
        return Calls.guarded(context, body, () -> version(id), () -> scopes.employment(id), () -> {
            return Response.status(Response.Status.CREATED)
                    .entity(record(List.of(service.transfer(id, body.toRequest()))).getFirst())
                    .build();
        });
    }

    @POST
    @Path("transfers")
    @Operation(
            operationId = "transferEmployments",
            summary = "Moves every person of one employer to another employer on a date, in one write.",
            description = "It is for a merger of two legal entities. Each employment that holds on the day before "
                    + "and on the date is transferred, as `POST /employments/{id}/transfer` does for one. With "
                    + "`endEmployer`, the old employer ends on the same date. A failure writes nothing. Both "
                    + "employers are legal entities, so an employment with an external employer never moves.")
    @Parameter(ref = "changeOrder")
    @APIResponse(
            responseCode = "201",
            description = "The old and the new employment of each person.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = TransfersRepresentation.class)))
    @BadRequest
    @NotFound
    @ApiErrors
    public Response transferAll(TransfersRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Calls.body(body);
            Map<String, Set<String>> changed = new HashMap<>(scopes.employer(body.fromEmployerId()));
            if (Boolean.TRUE.equals(body.endEmployer()) && body.fromEmployerId() != null) {
                changed.put("org-units", Set.of(body.fromEmployerId()));
            }
            context.lock(changed);
            List<TransfersRepresentation.Moved> moved = record(service.transferAll(body.toRequest())).stream()
                    .map(transfer -> new TransfersRepresentation.Moved(transfer.started().personId(),
                            transfer.ended().id(), transfer.started().id()))
                    .toList();
            OrgUnitRepresentation endedEmployer = null;
            if (Boolean.TRUE.equals(body.endEmployer())) {
                String employerId = body.fromEmployerId();
                OrgUnitState ended = orgUnits.end(employerId, body.validFrom());
                endedEmployer = OrgUnitRepresentation.of(ended,
                        externalIds.lists(ExternalId.Kind.ORG_UNIT, List.of(ended.unit().id())),
                        context.nameLanguage());
                context.recordChange(Change.UPDATE, OrgUnitsResource.RESOURCE_TYPE, employerId,
                        "fuhito/org-units/" + employerId + "/end", endedEmployer);
            }
            return Response.status(Response.Status.CREATED)
                    .entity(TransfersRepresentation.of(moved, endedEmployer))
                    .build();
        });
    }

    // Every part of a transfer gets its own entry in the admin events and in the change log.
    private List<TransferRepresentation> record(List<EmploymentService.Transfer> transfers) {
        Iterator<EmploymentRepresentation> employments = represent(transfers.stream()
                .flatMap(transfer -> Stream.of(transfer.ended(), transfer.started()))
                .toList()).iterator();
        Iterator<AssignmentRepresentation> newAssignments = AssignmentsResource.represent(assignments.getAll(
                transfers.stream().flatMap(transfer -> transfer.assignmentIds().stream()).toList()), externalIds)
                .iterator();
        Iterator<ReportingLineRepresentation> newLines = ReportingLinesResource.represent(transfers.stream()
                .flatMap(transfer -> transfer.reportingLines().stream()).toList(), externalIds).iterator();
        Iterator<UnitRoleRepresentation> newRoles = UnitRolesResource.represent(transfers.stream()
                .flatMap(transfer -> transfer.unitRoles().stream()).toList(), externalIds).iterator();
        List<RequestContext.ChangeRecord> changes = new ArrayList<>();
        List<TransferRepresentation> recorded = new ArrayList<>();
        for (EmploymentService.Transfer transfer : transfers) {
            var ended = employments.next();
            var started = employments.next();
            changes.add(new RequestContext.ChangeRecord(Change.UPDATE, RESOURCE_TYPE, ended.id(),
                    "fuhito/employments/" + ended.id() + "/transfer", ended));
            changes.add(new RequestContext.ChangeRecord(Change.CREATE, RESOURCE_TYPE, started.id(),
                    "fuhito/employments/" + started.id(), started));
            var assignmentsOfTransfer = take(newAssignments, transfer.assignmentIds().size());
            assignmentsOfTransfer.forEach(assignment -> changes.add(new RequestContext.ChangeRecord(Change.CREATE,
                    AssignmentsResource.RESOURCE_TYPE, assignment.id(), "fuhito/assignments/" + assignment.id(),
                    assignment)));
            var linesOfTransfer = take(newLines, transfer.reportingLines().size());
            linesOfTransfer.forEach(line -> changes.add(new RequestContext.ChangeRecord(Change.CREATE,
                    ReportingLinesResource.RESOURCE_TYPE, line.id(), "fuhito/reporting-lines/" + line.id(), line)));
            var rolesOfTransfer = take(newRoles, transfer.unitRoles().size());
            rolesOfTransfer.forEach(role -> changes.add(new RequestContext.ChangeRecord(Change.CREATE,
                    UnitRolesResource.RESOURCE_TYPE, role.id(), "fuhito/unit-roles/" + role.id(), role)));
            recorded.add(TransferRepresentation.of(ended, started, assignmentsOfTransfer, linesOfTransfer,
                    rolesOfTransfer));
        }
        context.recordChanges(changes);
        return recorded;
    }

    private static <T> List<T> take(Iterator<T> items, int count) {
        List<T> taken = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            taken.add(items.next());
        }
        return taken;
    }

    @DELETE
    @Path("{id}/end")
    @Operation(
            operationId = "removeEmploymentEnd",
            summary = "Removes the end of an employment, for example to take back a resignation.",
            description = "The end reason goes with the end, because an employment with no end has no end reason. "
                    + "An employment that another one continues, as after a transfer, keeps its end. The "
                    + "request then fails with 409 and `employment.continues_holds`.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The employment with the terms of every period.")
    @BadRequest
    @NotFound
    @ApiErrors
    public EmploymentRepresentation removeEnd(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id,
            @Parameter(
                    description = "The version of the employment that the caller read.",
                    required = true,
                    schema = @Schema(type = SchemaType.INTEGER, format = "int64"))
            @QueryParam("version") String version) {
        context.requireWrite();
        return Calls.guarded(context, version, () -> version(id), () -> {
            var employment = represent(service.removeEnd(id));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/end", employment);
            return employment;
        });
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getEmployment", summary = "Returns an employment with the terms of every period.")
    @APIResponse(responseCode = "200", description = "The employment.")
    @NotFound
    @BadRequest
    @ApiErrors
    public EmploymentRepresentation get(
            @Parameter(description = "The ID of the resource.")
            @PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> represent(service.get(id)));
    }

    // The IDs of every employment of a list are read with one query.
    private List<EmploymentRepresentation> represent(List<EmploymentView> views) {
        ExternalIdLists ids = externalIds.lists(ExternalId.Kind.EMPLOYMENT,
                views.stream().map(view -> view.employment().id()).toList());
        return views.stream().map(view -> EmploymentRepresentation.of(view, ids)).toList();
    }

    private EmploymentRepresentation represent(EmploymentView view) {
        return represent(List.of(view)).getFirst();
    }

    private long version(String id) {
        return service.get(id).employment().version();
    }
}
