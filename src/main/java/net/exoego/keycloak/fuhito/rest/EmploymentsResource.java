package net.exoego.keycloak.fuhito.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.Checks;
import net.exoego.keycloak.fuhito.rest.RequestContext.Change;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.TermsChange;

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
            context.recordChange(Change.CREATE, RESOURCE_TYPE, employment.id(), "fuhito/employments/" + employment.id(),
                    employment);
            return Response.status(Response.Status.CREATED).entity(employment).build();
        });
    }

    /**
     * Replaces the terms of an employment from the {@code validFrom} date of the body.
     * The terms are the employment type, the grade, the status, the work percentage, and the attributes.
     *
     * @param id the ID
     * @param body the new terms and their first date
     * @return the employment with the terms of every period
     */
    @PUT
    @Path("{id}/terms")
    public EmploymentRepresentation changeTerms(@PathParam("id") String id, EmploymentRepresentation.Terms body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var employment = EmploymentRepresentation.of(service.changeTerms(id, new TermsChange(body.validFrom(),
                    body.employmentType(), body.grade(), body.status(), body.workPercent(), body.attributes())));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/terms", employment);
            return employment;
        });
    }

    /**
     * Sets the end of an employment.
     * An earlier end is how a person leaves. It also ends the terms and the assignments on that date.
     * A later end extends a fixed-term employment.
     *
     * @param id the ID
     * @param body the first date on which the employment no longer holds, and the end reason
     * @return the employment with the terms of every period
     */
    @PUT
    @Path("{id}/end")
    public EmploymentRepresentation end(@PathParam("id") String id, EndRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var employment = EmploymentRepresentation.of(service.end(id, body.validTo(), body.endReason()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/end", employment);
            return employment;
        });
    }

    /**
     * Removes the end of an employment, for example to take back a resignation.
     *
     * @param id the ID
     * @return the employment with the terms of every period
     */
    @DELETE
    @Path("{id}/end")
    public EmploymentRepresentation removeEnd(@PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            var employment = EmploymentRepresentation.of(service.removeEnd(id));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, "fuhito/employments/" + id + "/end", employment);
            return employment;
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
