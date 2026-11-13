package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.EmploymentService;

/**
 * The employments of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmploymentsResource {

    /**
     * The resource type of employments in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_EMPLOYMENT";

    private final RequestContext context;
    private final EmploymentService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public EmploymentsResource(RequestContext context, EmploymentService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Starts an employment of a user.
     *
     * @param body the user, the dates, and the first terms
     * @return 201 with the new employment
     */
    @POST
    public Response start(EmploymentRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var employment = EmploymentRepresentation.of(service.start(body.toNewEmployment()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, "fuhito/employments/" + employment.id(), employment);
            return Response.status(Response.Status.CREATED).entity(employment).build();
        });
    }

    /**
     * Returns an employment with the terms of every period.
     *
     * @param id the ID
     * @return the employment
     */
    @GET
    @Path("{id}")
    public EmploymentRepresentation get(@PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> EmploymentRepresentation.of(service.get(id)));
    }
}
