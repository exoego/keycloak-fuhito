package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.ExternalIdService;
import net.exoego.keycloak.fuhito.service.ImportReport.Write;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.Page;
import net.exoego.keycloak.fuhito.service.PersonService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import net.exoego.keycloak.fuhito.service.UnitRoleService;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What is stored about the person of a Keycloak user. Each path is a shortcut to the same path of the person.
 */
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.USERS)
public class UsersResource {

    private final RequestContext context;
    private final MembershipService membership;
    private final EmploymentService employments;
    private final AssignmentService assignments;
    private final QualificationService qualifications;
    private final UnitRoleService unitRoles;
    private final ReportingLineService reportingLines;
    private final ExternalIdService externalIds;
    private final UserDirectory users;
    private final PersonService people;
    private final PersonData data;

    /**
     * The services that read what is stored about a person.
     *
     * @param membership the reads of assignments and of the history
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     * @param qualifications the operations on qualifications of the realm
     * @param reportingLines the reads of managers and reports on the realm
     * @param unitRoles the reads of roles over org units on the realm
     * @param externalIds the IDs that systems have for the resources of the realm
     */
    public record Reads(MembershipService membership, EmploymentService employments, AssignmentService assignments,
            QualificationService qualifications, ReportingLineService reportingLines, UnitRoleService unitRoles,
            ExternalIdService externalIds) {
    }

    UsersResource(RequestContext context, Reads reads, UserDirectory users, PersonService people, PersonData data) {
        this.context = context;
        this.membership = reads.membership();
        this.employments = reads.employments();
        this.assignments = reads.assignments();
        this.qualifications = reads.qualifications();
        this.reportingLines = reads.reportingLines();
        this.unitRoles = reads.unitRoles();
        this.externalIds = reads.externalIds();
        this.users = users;
        this.people = people;
        this.data = data;
    }

    @POST
    @Path("{userId}/mask")
    @Operation(
            operationId = "maskUser",
            summary = "Masks the personal data of a user who left. It cannot be undone.",
            description = "It clears the staff number, the numbers of qualifications, the external IDs, and the "
                    + "free attributes. Every record keeps its ID, its org unit, its job title, its occupation, and "
                    + "its dates. The entries of the change log stay as they are.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The person and the time of the masking.")
    @BadRequest
    @NotFound
    @ApiErrors
    public MaskRepresentation mask(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
            @Parameter(
                    description = """
                            True to also empty the first and the last name of the linked Keycloak user. \
                            It also needs the permission of Keycloak to manage that user. \
                            The username, the email, and the attributes stay. \
                            False leaves the Keycloak user as it is, so responses still show its name. \
                            A read-only user, for example from a user store, cannot be changed. \
                            The masking then fails with 409 and `person.keycloak_name_read_only`, \
                            and nothing is written.""",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("clearKeycloakName") boolean clearKeycloakName) {
        context.requireWrite();
        return Calls.run(context, () -> {
            // The check reads the person, so the lock comes first.
            context.lock(data.ofUser(userId));
            requireKnown(userId);
            if (clearKeycloakName) {
                context.requireManageUser(userId);
            }
            // A user with nothing stored gets a person, so that the masking is remembered.
            PersonService.Resolved resolved = people.forUser(userId);
            if (resolved.created() != null) {
                Writes.record(context, externalIds, new Write.OfPerson(resolved.created()));
            }
            return data.mask(resolved.person(), clearKeycloakName);
        });
    }

    @Schema(
            name = "Mask",
            description = "The result of a masking.",
            requiredProperties = {"personId", "version", "maskedAt", "keycloakNameCleared"})
    public record MaskRepresentation(
            String personId,
            @Schema(description = "The version of the person after the mask.")
            long version,
            @Schema(description = "The ID of the Keycloak user that the person is linked to.")
            String userId,
            @Schema(description = "When the data was first masked, in UTC.", format = "date-time")
            String maskedAt,
            @Schema(description = """
                    True when this request emptied the name of the linked Keycloak user. \
                    False when the request did not ask for it, or the person has no linked user.""")
            boolean keycloakNameCleared) {
    }

    @DELETE
    @Path("{userId}/data")
    @Operation(
            operationId = "eraseUserData",
            summary = "Erases everything about one user for good.",
            description = "These are the employments with their terms and assignments, the qualifications, and the "
                    + "reporting lines of the user as a report and as a manager. The change log keeps its entries "
                    + "about them, because they hold only IDs. Removal of a user from Keycloak does not do this. "
                    + "This endpoint is for a request to delete personal data.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "204", description = "The data was erased.")
    @BadRequest
    @NotFound
    @ApiErrors
    public Response erase(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId) {
        context.requireWrite();
        return Calls.run(context, () -> {
            context.lock(data.ofUser(userId));
            requireKnown(userId);
            people.ofUser(userId).ifPresent(person -> data.erase(person.id()));
            return Response.noContent().build();
        });
    }

    @GET
    @Path("{userId}/managers")
    @Operation(
            operationId = "listUserManagers",
            summary = "Lists the managers of a user on a date, for each assignment of the user.",
            description = "An assignment with no stored solid line takes the head of its org unit, or of the "
                    + "nearest unit above.")
    @APIResponse(responseCode = "200", description = "The managers.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<ReportingRepresentation> managers(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> reportingLines.managersOf(userId, asOf).stream()
                .map(ReportingRepresentation::of)
                .toList());
    }

    @GET
    @Path("{userId}/reports")
    @Operation(operationId = "listUserReports", summary = "Lists the direct reports of a user on a date.")
    @APIResponse(responseCode = "200", description = "The direct reports.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<ReportingRepresentation> reports(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
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
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> reportingLines.reportsOf(userId, asOf, Page.of(first, max)).stream()
                .map(ReportingRepresentation::of)
                .toList());
    }

    @GET
    @Path("{userId}/reports/count")
    @Operation(
            operationId = "countUserReports",
            summary = "Counts the direct reports that `GET /users/{userId}/reports` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of direct reports on all pages.")
    @BadRequest
    @NotFound
    @ApiErrors
    public CountRepresentation countReports(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> new CountRepresentation(reportingLines.countReportsOf(userId, asOf)));
    }

    @GET
    @Path("{userId}/qualifications")
    @Operation(operationId = "listUserQualifications", summary = "Lists the qualifications of a user, oldest first.")
    @APIResponse(responseCode = "200", description = "The qualifications.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<QualificationRepresentation> qualifications(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm. It cannot be "
                            + "used with `all=true`.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to return every qualification, expired ones and future ones included. It "
                            + "cannot be used with `asOf`. A request with both fails with 400 and "
                            + "`invalid.not_together`.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("all") boolean all) {
        context.requireRead();
        return Calls.run(context, () -> QualificationsResource.represent(qualifications.ofUser(userId, asOf, all),
                externalIds));
    }

    @GET
    @Path("{userId}/assignments")
    @Operation(
            operationId = "listUserAssignments",
            summary = "Lists the assignments of a user that hold on a date.",
            description = "The primary assignment comes first.")
    @APIResponse(responseCode = "200", description = "The assignments of the user on the date.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<MemberRepresentation> assignments(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to return each org unit with its path from the root.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("includePath") boolean includePath) {
        context.requireRead();
        Locale language = context.nameLanguage();
        return Calls.run(context, () -> membership.assignmentsOf(userId, asOf, includePath).stream()
                .map(member -> MemberRepresentation.of(member, language))
                .toList());
    }

    @GET
    @Path("{userId}/history")
    @Operation(
            operationId = "getUserHistory",
            summary = "Returns every employment and every assignment of a user, with all their periods.")
    @APIResponse(responseCode = "200", description = "The history of the user.")
    @NotFound
    @BadRequest
    @ApiErrors
    public UserHistoryRepresentation history(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId) {
        context.requireRead();
        return Calls.run(context,
                () -> UserHistoryRepresentation.of(membership.historyOf(userId, employments, assignments),
                        people.ofUser(userId).flatMap(person -> data.maskedAt(person.id())), externalIds));
    }

    @GET
    @Path("{userId}/unit-roles")
    @Operation(operationId = "listUserUnitRoles", summary = "Lists the roles over org units of a user on a date.")
    @APIResponse(responseCode = "200", description = "The roles, in the order of their start.")
    @BadRequest
    @NotFound
    @ApiErrors
    public List<UnitRoleRepresentation> unitRoles(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
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
            @QueryParam("max") Integer max) {
        context.requireRead();
        return Calls.run(context, () -> {
            // A user with no person has no roles, but a bad date fails all the same.
            Checks.optionalDate("asOf", asOf);
            requireKnown(userId);
            return people.ofUser(userId)
                    .map(person -> UnitRolesResource.represent(
                            unitRoles.ofPerson(person.id(), asOf, Page.of(first, max)), externalIds))
                    .orElse(List.of());
        });
    }

    @GET
    @Path("{userId}/unit-roles/count")
    @Operation(
            operationId = "countUserUnitRoles",
            summary = "Counts the roles that `GET /users/{userId}/unit-roles` lists with the same filters.")
    @APIResponse(responseCode = "200", description = "The number of roles on all pages.")
    @BadRequest
    @NotFound
    @ApiErrors
    public CountRepresentation countUnitRoles(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId,
            @Parameter(
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> {
            Checks.optionalDate("asOf", asOf);
            requireKnown(userId);
            return new CountRepresentation(people.ofUser(userId)
                    .map(person -> unitRoles.countOfPerson(person.id(), asOf))
                    .orElse(0L));
        });
    }

    private void requireKnown(String userId) {
        if (!users.isKnown(userId)) {
            throw FuhitoException.notFound("user.not_found", Map.of("userId", userId));
        }
    }
}
