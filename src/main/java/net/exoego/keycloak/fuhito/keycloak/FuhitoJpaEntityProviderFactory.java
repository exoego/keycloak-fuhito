package net.exoego.keycloak.fuhito.keycloak;

import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers the JPA entities and the Liquibase changelog of this extension.
 */
public class FuhitoJpaEntityProviderFactory implements JpaEntityProviderFactory {

    /**
     * The provider ID. Keycloak also uses it in the name of the Liquibase changelog table.
     */
    public static final String ID = "fuhito";

    /**
     * Creates the factory.
     */
    public FuhitoJpaEntityProviderFactory() {
    }

    @Override
    public JpaEntityProvider create(KeycloakSession session) {
        return new FuhitoJpaEntityProvider();
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
