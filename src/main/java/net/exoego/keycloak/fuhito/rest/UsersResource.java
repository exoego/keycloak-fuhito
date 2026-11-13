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

/**
 * What is stored about the users of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
public class UsersResource {

    private final RequestContext context;
    private final MembershipService membership;
    private final EmploymentService employments;
    private final AssignmentService assignments;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param membership the reads on the realm of the request
     * @param employments the operations on employments of the realm
     * @param assignments the operations on assignments of the realm
     */
    public UsersResource(RequestContext context, MembershipService membership, EmploymentService employments,
            AssignmentService assignments) {
        this.context = context;
        this.membership = membership;
        this.employments = employments;
        this.assignments = assignments;
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
