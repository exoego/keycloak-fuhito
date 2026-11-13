package net.exoego.keycloak.fuhito.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.MembershipService;
import net.exoego.keycloak.fuhito.service.QualificationService;
import net.exoego.keycloak.fuhito.service.ReportingLineService;

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

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param membership the reads on the realm of the request
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     * @param qualifications the operations on qualifications of the realm
     * @param reportingLines the reads of managers and reports on the realm
     */
    public UsersResource(RequestContext context, MembershipService membership, EmploymentService employments,
            AssignmentService assignments, QualificationService qualifications,
            ReportingLineService reportingLines) {
        this.reportingLines = reportingLines;
        this.context = context;
        this.membership = membership;
        this.employments = employments;
        this.assignments = assignments;
        this.qualifications = qualifications;
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
                () -> UserHistoryRepresentation.of(membership.historyOf(userId, employments, assignments)));
    }
}
