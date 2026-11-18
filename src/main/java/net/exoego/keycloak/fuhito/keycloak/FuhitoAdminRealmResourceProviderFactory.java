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
     * The option that turns the timer of the projection on or off.
     */
    static final String PROJECTION_TIMER_OPTION = "projection-timer-enabled";

    private boolean projectionTimer = true;

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
        projectionTimer = config.getBoolean(PROJECTION_TIMER_OPTION, true);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (!projectionTimer) {
            return;
        }
        // The tables exist only after the migration of the database.
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                ProjectionSchedule.start(factory);
            }
        });
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(PROJECTION_TIMER_OPTION)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .helpText("Whether a timer rebuilds the projection once a day, after the day start of each realm"
                        + " that turned the projection on. false turns the timer off.")
                .defaultValue(true)
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
