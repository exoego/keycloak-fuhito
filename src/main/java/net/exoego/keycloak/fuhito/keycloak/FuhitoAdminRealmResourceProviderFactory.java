package net.exoego.keycloak.fuhito.keycloak;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.PostMigrationEvent;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
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
     * The option with the number of seconds between two runs of the projection. 0 turns the timer off.
     */
    static final String PROJECTION_INTERVAL_OPTION = "projection-interval-seconds";

    static final long DEFAULT_PROJECTION_INTERVAL_SECONDS = 900;

    private long projectionIntervalSeconds = DEFAULT_PROJECTION_INTERVAL_SECONDS;

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
        projectionIntervalSeconds = config.getLong(PROJECTION_INTERVAL_OPTION, DEFAULT_PROJECTION_INTERVAL_SECONDS);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (projectionIntervalSeconds <= 0) {
            return;
        }
        // The tables exist only after the migration of the database.
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                ProjectionSchedule.start(factory, projectionIntervalSeconds);
            }
        });
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(PROJECTION_INTERVAL_OPTION)
                .type("long")
                .helpText("The number of seconds between two runs of the projection into organization groups."
                        + " 0 turns the timer off.")
                .defaultValue(DEFAULT_PROJECTION_INTERVAL_SECONDS)
                .add()
                .build();
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }
}
