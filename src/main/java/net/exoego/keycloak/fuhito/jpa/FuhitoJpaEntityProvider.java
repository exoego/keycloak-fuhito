package net.exoego.keycloak.fuhito.jpa;

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

    // Keycloak ignores this in production and finds entities at build time. The interface still requires it.
    @Override
    @SuppressWarnings("removal")
    public List<Class<?>> getEntities() {
        return List.of(HierarchyEntity.class);
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
