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
import net.exoego.keycloak.fuhito.service.QualificationService;

/**
 * The qualifications of one realm.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QualificationsResource {

    /**
     * The resource type of qualifications in the admin event log.
     */
    public static final String RESOURCE_TYPE = "FUHITO_QUALIFICATION";

    private final RequestContext context;
    private final QualificationService service;

    /**
     * Creates the resource for one request.
     *
     * @param context the request and its caller
     * @param service the operations on the realm of the request
     */
    public QualificationsResource(RequestContext context, QualificationService service) {
        this.context = context;
        this.service = service;
    }

    /**
     * Adds a qualification of a user.
     *
     * @param body the user, the kind, the number, and the dates
     * @return 201 with the new qualification
     */
    @POST
    public Response add(QualificationRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var qualification = QualificationRepresentation.of(service.add(body.toValues()));
            context.recordChange(Change.CREATE, RESOURCE_TYPE, qualification.id(), path(qualification.id()),
                    qualification);
            return Response.status(Response.Status.CREATED).entity(qualification).build();
        });
    }

    /**
     * Returns one qualification.
     *
     * @param id the ID
     * @return the qualification
     */
    @GET
    @Path("{id}")
    public QualificationRepresentation get(@PathParam("id") String id) {
        context.requireRead();
        return Calls.run(context, () -> QualificationRepresentation.of(service.get(id)));
    }

    /**
     * Replaces the kind, the number, the dates, and the attributes of a qualification.
     *
     * @param id the ID
     * @param body the new values
     * @return the changed qualification
     */
    @PUT
    @Path("{id}")
    public QualificationRepresentation replace(@PathParam("id") String id, QualificationRepresentation body) {
        context.requireWrite();
        return Calls.run(context, () -> {
            Checks.required("body", body);
            var qualification = QualificationRepresentation.of(service.replace(id, body.toValues()));
            context.recordChange(Change.UPDATE, RESOURCE_TYPE, id, path(id), qualification);
            return qualification;
        });
    }

    /**
     * Deletes a qualification.
     *
     * @param id the ID
     * @return 204
     */
    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") String id) {
        context.requireWrite();
        return Calls.run(context, () -> {
            service.delete(id);
            context.recordChange(Change.DELETE, RESOURCE_TYPE, id, path(id), null);
            return Response.noContent().build();
        });
    }

    private static String path(String id) {
        return "fuhito/qualifications/" + id;
    }
}
