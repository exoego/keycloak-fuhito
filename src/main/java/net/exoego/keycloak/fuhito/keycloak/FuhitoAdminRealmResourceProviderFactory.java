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

    /**
     * The option with the number of seconds between two checks of the timer of the projection.
     */
    static final String PROJECTION_INTERVAL_OPTION = "projection-timer-interval";

    /**
     * The option with the number of seconds between two runs of the cleanup of the change log.
     */
    static final String CLEANUP_INTERVAL_OPTION = "change-log-cleanup-interval";

    private static final int DEFAULT_PROJECTION_SECONDS = 60;

    // A realm keeps a propagated change for a number of days, so a run a day is enough.
    private static final int DEFAULT_CLEANUP_SECONDS = 86_400;

    private boolean projectionTimer = true;

    private int projectionSeconds = DEFAULT_PROJECTION_SECONDS;

    private int cleanupSeconds = DEFAULT_CLEANUP_SECONDS;

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
        projectionSeconds = seconds(config, PROJECTION_INTERVAL_OPTION, DEFAULT_PROJECTION_SECONDS);
        cleanupSeconds = seconds(config, CLEANUP_INTERVAL_OPTION, DEFAULT_CLEANUP_SECONDS);
    }

    private static int seconds(Config.Scope config, String option, int defaultSeconds) {
        int seconds = config.getInt(option, defaultSeconds);
        if (seconds < 1) {
            throw new IllegalArgumentException("The option " + option
                    + " must be a number of seconds of 1 or more, not " + seconds + ".");
        }
        return seconds;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // The tables exist only after the migration of the database.
        factory.register(event -> {
            if (event instanceof PostMigrationEvent) {
                ChangeLogSchedule.start(factory, cleanupSeconds * 1000L);
                if (projectionTimer) {
                    ProjectionSchedule.start(factory, projectionSeconds * 1000L);
                }
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
                .property()
                .name(PROJECTION_INTERVAL_OPTION)
                .type(ProviderConfigProperty.INTEGER_TYPE)
                .helpText("The number of seconds between two checks of the timer of the projection for the realms"
                        + " that are due.")
                .defaultValue(DEFAULT_PROJECTION_SECONDS)
                .add()
                .property()
                .name(CLEANUP_INTERVAL_OPTION)
                .type(ProviderConfigProperty.INTEGER_TYPE)
                .helpText("The number of seconds between two runs of the task that removes the propagated changes"
                        + " that each realm keeps no longer. By default it runs once a day. The first run comes"
                        + " 15 minutes after the start, or after one interval when that is shorter.")
                .defaultValue(DEFAULT_CLEANUP_SECONDS)
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
