package net.exoego.keycloak.fuhito.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.Properties;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.jpa.HierarchyEntity;
import net.exoego.keycloak.fuhito.jpa.JpaAssignmentStore;
import net.exoego.keycloak.fuhito.jpa.JpaEmploymentStore;
import net.exoego.keycloak.fuhito.jpa.JpaOrgUnitStore;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.service.AssignmentService;
import net.exoego.keycloak.fuhito.service.EmploymentService;
import net.exoego.keycloak.fuhito.service.OrgUnitService;
import net.exoego.keycloak.fuhito.service.RefItemService;
import net.exoego.keycloak.fuhito.store.UserDirectory;

/**
 * The root of the admin REST API for one realm.
 */
public class FuhitoResource {

    private static final String VERSION = loadVersion();

    private final String realmId;
    private final RequestContext context;
    private final EntityManager entityManager;
    private final UserDirectory users;

    /**
     * Creates the resource for one request.
     *
     * @param realmId the ID of the realm of the request
     * @param context the request and its caller
     * @param entityManager the entity manager of the request
     * @param users the users of the realm
     */
    public FuhitoResource(String realmId, RequestContext context, EntityManager entityManager, UserDirectory users) {
        this.realmId = realmId;
        this.context = context;
        this.entityManager = entityManager;
        this.users = users;
    }

    /**
     * Returns the assignments of the realm.
     *
     * @return the sub-resource
     */
    @Path("assignments")
    public AssignmentsResource assignments() {
        return new AssignmentsResource(context, new AssignmentService(new JpaAssignmentStore(entityManager, realmId),
                new JpaEmploymentStore(entityManager, realmId), new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId)));
    }

    /**
     * Returns the employments of the realm.
     *
     * @return the sub-resource
     */
    @Path("employments")
    public EmploymentsResource employments() {
        return new EmploymentsResource(context, new EmploymentService(new JpaEmploymentStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), users));
    }

    /**
     * Returns the reference items of the realm.
     *
     * @return the sub-resource
     */
    @Path("ref-items")
    public RefItemsResource refItems() {
        return new RefItemsResource(context, new RefItemService(new JpaRefItemStore(entityManager, realmId)));
    }

    /**
     * Returns the org units of the realm.
     *
     * @return the sub-resource
     */
    @Path("org-units")
    public OrgUnitsResource orgUnits() {
        return new OrgUnitsResource(context, new OrgUnitService(new JpaOrgUnitStore(entityManager, realmId),
                new JpaRefItemStore(entityManager, realmId), Clock.systemDefaultZone()));
    }

    /**
     * Returns the version of this extension.
     *
     * @return the version
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public VersionRepresentation version() {
        context.requireRead();
        return new VersionRepresentation(VERSION);
    }

    /**
     * Lists the hierarchies of the realm.
     *
     * @return the hierarchies, ordered by code
     */
    @GET
    @Path("hierarchies")
    @Produces(MediaType.APPLICATION_JSON)
    public List<HierarchyRepresentation> hierarchies() {
        context.requireRead();
        return entityManager
                .createQuery("select h from HierarchyEntity h where h.realmId = :realmId order by h.code",
                        HierarchyEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream()
                .map(h -> new HierarchyRepresentation(h.getId(), h.getCode(), h.getName()))
                .toList();
    }

    private static String loadVersion() {
        Properties properties = new Properties();
        try (InputStream in = FuhitoResource.class.getResourceAsStream("/META-INF/fuhito/version.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }
}
