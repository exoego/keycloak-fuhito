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

/**
 * What is stored about the users of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
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

    /**
     * Masks the personal data of a user who left. It cannot be undone.
     * It clears the staff number, the numbers of qualifications, the external IDs, and the free attributes,
     * also in the change log. Every record keeps its ID, its org unit, its job title, its occupation, and its
     * dates. So an application that refers to a record by ID still finds it.
     *
     * @param userId the ID of the user. The user may be removed from Keycloak already.
     * @return the user ID and the time of the masking
     */
    @POST
    @Path("{userId}/mask")
    public MaskRepresentation mask(@PathParam("userId") String userId) {
        context.requireWrite();
        return Calls.run(context, () -> {
            var mask = new MaskRepresentation(userId, masking.mask(userId).toString());
            context.recordChange(Change.UPDATE, "FUHITO_USER_DATA", userId, "fuhito/users/" + userId + "/mask", mask);
            return mask;
        });
    }

    /**
     * The result of a masking.
     *
     * @param userId the ID of the user
     * @param maskedAt when the data was first masked, in UTC
     */
    public record MaskRepresentation(String userId, String maskedAt) {
    }

    /**
     * Erases everything about one user for good. These are the employments with their terms and assignments,
     * the qualifications, the reporting lines of the user as a report and as a manager, and the change log
     * entries about them.
     * Removal of a user from Keycloak does not do this. This endpoint is for a request to delete personal data.
     *
     * @param userId the ID of the user. The user may be removed from Keycloak already.
     * @return 204
     */
    @DELETE
    @Path("{userId}/data")
    public Response erase(@PathParam("userId") String userId) {
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

    /**
     * Lists the managers of a user on a date, for each assignment of the user.
     * An assignment with no stored solid line takes the head of its org unit, or of the nearest unit above.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @return the managers
     */
    @GET
    @Path("{userId}/managers")
    public List<ReportingRepresentation> managers(@PathParam("userId") String userId,
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> reportingLines.managersOf(userId, asOf).stream()
                .map(ReportingRepresentation::of)
                .toList());
    }

    /**
     * Lists the direct reports of a user on a date.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @return the direct reports
     */
    @GET
    @Path("{userId}/reports")
    public List<ReportingRepresentation> reports(@PathParam("userId") String userId,
            @QueryParam("asOf") String asOf) {
        context.requireRead();
        return Calls.run(context, () -> reportingLines.reportsOf(userId, asOf).stream()
                .map(ReportingRepresentation::of)
                .toList());
    }

    /**
     * Lists the qualifications of a user, oldest first.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @param all true to return every qualification, expired ones and future ones included
     * @return the qualifications
     */
    @GET
    @Path("{userId}/qualifications")
    public List<QualificationRepresentation> qualifications(@PathParam("userId") String userId,
            @QueryParam("asOf") String asOf, @QueryParam("all") boolean all) {
        context.requireRead();
        return Calls.run(context, () -> qualifications.ofUser(userId, asOf, all).stream()
                .map(QualificationRepresentation::of)
                .toList());
    }

    /**
     * Lists the assignments of a user that hold on a date. The primary assignment comes first.
     *
     * @param userId the ID of the user
     * @param asOf the date as {@code YYYY-MM-DD}. The default is today in the time zone of the server.
     * @param includePath true to return each org unit with its path from the root
     * @return the assignments of the user on the date
     */
    @GET
    @Path("{userId}/assignments")
    public List<MemberRepresentation> assignments(@PathParam("userId") String userId,
            @QueryParam("asOf") String asOf, @QueryParam("includePath") boolean includePath) {
        context.requireRead();
        return Calls.run(context, () -> membership.assignmentsOf(userId, asOf, includePath).stream()
                .map(MemberRepresentation::of)
                .toList());
    }

    /**
     * Returns every employment and every assignment of a user, with all their periods.
     *
     * @param userId the ID of the user
     * @return the history of the user
     */
    @GET
    @Path("{userId}/history")
    public UserHistoryRepresentation history(@PathParam("userId") String userId) {
        context.requireRead();
        return Calls.run(context,
                () -> UserHistoryRepresentation.of(membership.historyOf(userId, employments, assignments),
                        masking.maskedAt(userId)));
    }
}
