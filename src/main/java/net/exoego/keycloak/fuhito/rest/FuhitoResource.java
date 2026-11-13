package net.exoego.keycloak.fuhito.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.jpa.HierarchyEntity;
import net.exoego.keycloak.fuhito.jpa.JpaRefItemStore;
import net.exoego.keycloak.fuhito.service.RefItemService;

/**
 * The root of the admin REST API for one realm.
 */
public class FuhitoResource {

    private static final String VERSION = loadVersion();

    private final String realmId;
    private final RequestContext context;
    private final EntityManager entityManager;

    /**
     * Creates the resource for one request.
     *
     * @param realmId the ID of the realm of the request
     * @param context the request and its caller
     * @param entityManager the entity manager of the request
     */
    public FuhitoResource(String realmId, RequestContext context, EntityManager entityManager) {
        this.realmId = realmId;
        this.context = context;
        this.entityManager = entityManager;
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
