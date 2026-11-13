package net.exoego.keycloak.fuhito.keycloak;

import java.util.List;

import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;

/**
 * Tells Keycloak where the Liquibase changelog of this extension is.
 */
public class FuhitoJpaEntityProvider implements JpaEntityProvider {

    static final String CHANGELOG_LOCATION = "META-INF/fuhito/changelog.xml";

    /**
     * Creates the provider.
     */
    public FuhitoJpaEntityProvider() {
    }

    // Keycloak finds entities at build time and ignores this list. The interface still requires the method.
    @Override
    @SuppressWarnings("removal")
    public List<Class<?>> getEntities() {
        return List.of();
    }

    @Override
    public String getChangelogLocation() {
        return CHANGELOG_LOCATION;
    }

    @Override
    public String getFactoryId() {
        return FuhitoJpaEntityProviderFactory.ID;
    }

    @Override
    public void close() {
    }
}
