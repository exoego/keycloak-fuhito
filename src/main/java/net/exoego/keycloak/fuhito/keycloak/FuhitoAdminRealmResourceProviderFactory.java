package net.exoego.keycloak.fuhito.keycloak;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;

/**
 * Serves the admin REST API of this extension under {@code /admin/realms/{realm}/fuhito}.
 */
public class FuhitoAdminRealmResourceProviderFactory implements AdminRealmResourceProviderFactory {

    /**
     * The provider ID. It is also the path segment of the API.
     */
    public static final String ID = "fuhito";

    /**
     * Creates the factory.
     */
    public FuhitoAdminRealmResourceProviderFactory() {
    }

    @Override
    public AdminRealmResourceProvider create(KeycloakSession session) {
        return new FuhitoAdminRealmResourceProvider();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }
}
