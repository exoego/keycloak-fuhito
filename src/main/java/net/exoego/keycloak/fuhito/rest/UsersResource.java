package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;
import net.exoego.keycloak.fuhito.service.UserMaskingService;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What is stored about the users of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = Tags.USERS)
public class UsersResource {

    private final RequestContext context;
    private final MembershipService membership;
    private final EmploymentService employments;
    private final AssignmentService assignments;
    private final QualificationService qualifications;
    private final ReportingLineService reportingLines;
    private final UserDirectory users;
    private final Consumer<String> eraseUser;
    private final UserMaskingService masking;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param membership the reads on the realm of the request
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     * @param qualifications the operations on qualifications of the realm
     * @param reportingLines the reads of managers and reports on the realm
     * @param users the users of the realm
     * @param eraseUser removes every row and every change log entry about one user
     * @param masking the masking of personal data of the realm
     */
    public UsersResource(RequestContext context, MembershipService membership, EmploymentService employments,
            AssignmentService assignments, QualificationService qualifications,
            ReportingLineService reportingLines, UserDirectory users, Consumer<String> eraseUser,
            UserMaskingService masking) {
        this.masking = masking;
        this.users = users;
        this.eraseUser = eraseUser;
        this.reportingLines = reportingLines;
        this.context = context;
        this.membership = membership;
        this.employments = employments;
        this.assignments = assignments;
        this.qualifications = qualifications;
    }

    @POST
    @Path("{userId}/mask")
    @Operation(
            operationId = "maskUser",
            summary = "Masks the personal data of a user who left. It cannot be undone.",
            description = "It clears the staff number, the numbers of qualifications, the external IDs, and the "
                    + "free attributes, also in the change log. Every record keeps its ID, its org unit, its job "
                    + "title, its occupation, and its dates.")
    @Parameter(ref = "changeOrder")
    @APIResponse(responseCode = "200", description = "The user ID and the time of the masking.")
    @BadRequest
    @NotFound
    @ApiErrors
    public MaskRepresentation mask(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId) {
        context.requireWrite();
        return Calls.run(context, () -> {
            var mask = new MaskRepresentation(userId, masking.mask(userId).toString());
            context.recordChange(Change.UPDATE, "FUHITO_USER_DATA", userId, "fuhito/users/" + userId + "/mask", mask);
            return mask;
        });
    }

    @Schema(name = "Mask", description = "The result of a masking.", requiredProperties = {"userId", "maskedAt"})
    public record MaskRepresentation(
            String userId,
            @Schema(description = "When the data was first masked, in UTC.", format = "date-time")
            String maskedAt) {
    }

    @DELETE
    @Path("{userId}/data")
    @Operation(
            operationId = "eraseUserData",
            summary = "Erases everything about one user for good.",
            description = "These are the employments with their terms and assignments, the qualifications, the "
                    + "reporting lines of the user as a report and as a manager, and the change log entries about "
                    + "them. Removal of a user from Keycloak does not do this. This endpoint is for a request to "
                    + "delete personal data.")
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
            if (!users.isKnown(userId)) {
                throw FuhitoException.notFound("user.not_found", Map.of("userId", userId));
            }
            eraseUser.accept(userId);
            // The entry says that data was erased and by whom. It has nothing about the person but the ID.
            context.recordChange(Change.DELETE, "FUHITO_USER_DATA", userId, "fuhito/users/" + userId + "/data", null);
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
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> reportingLines.reportsOf(userId, asOf).stream()
                .map(ReportingRepresentation::of)
                .toList());
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
                    description = "The date to read. The default is today in the time zone of the realm.",
                    schema = @Schema(format = "date"))
            @QueryParam("asOf") String asOf,
            @Parameter(
                    description = "True to return every qualification, expired ones and future ones included.",
                    schema = @Schema(defaultValue = "false"))
            @QueryParam("all") boolean all) {
        context.requireRead();
        return Calls.run(context, () -> qualifications.ofUser(userId, asOf, all).stream()
                .map(QualificationRepresentation::of)
                .toList());
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
        return Calls.run(context, () -> membership.assignmentsOf(userId, asOf, includePath).stream()
                .map(MemberRepresentation::of)
                .toList());
    }

    @GET
    @Path("{userId}/history")
    @Operation(
            operationId = "getUserHistory",
            summary = "Returns every employment and every assignment of a user, with all their periods.")
    @APIResponse(responseCode = "200", description = "The history of the user.")
    @NotFound
    @ApiErrors
    public UserHistoryRepresentation history(
            @Parameter(description = "The ID of the Keycloak user.")
            @PathParam("userId") String userId) {
        context.requireRead();
        return Calls.run(context,
                () -> UserHistoryRepresentation.of(membership.historyOf(userId, employments, assignments),
                        masking.maskedAt(userId)));
    }
}
