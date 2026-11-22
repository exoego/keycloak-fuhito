package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The parts of the providers that Keycloak calls only for its help text or when it stops.
 */
class ProviderFactoriesTest {

    // A configuration with the values of some options. Any other option has its default.
    private static Config.Scope options(Map<String, Object> values) {
        return (Config.Scope) Proxy.newProxyInstance(ProviderFactoriesTest.class.getClassLoader(),
                new Class<?>[] {Config.Scope.class},
                (proxy, method, args) -> values.getOrDefault((String) args[0], args[1]));
    }

    @Test
    void describesTheOptionsOfTheTimers() {
        List<ProviderConfigProperty> options = new FuhitoAdminRealmResourceProviderFactory().getConfigMetadata();

        assertEquals(List.of("projection-timer-enabled boolean true", "projection-timer-interval Integer 60",
                "change-log-cleanup-interval Integer 900"),
                options.stream().map(option -> option.getName() + " " + option.getType() + " "
                        + option.getDefaultValue()).toList());
    }

    // The tables exist only after the migration of the database, so the timers wait for it.
    @Test
    void theTimersWaitForTheMigration() {
        var factory = new FuhitoAdminRealmResourceProviderFactory();
        factory.init(options(Map.of("projection-timer-enabled", false)));
        List<String> calls = new ArrayList<>();
        var sessions = (KeycloakSessionFactory) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {KeycloakSessionFactory.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });

        factory.postInit(sessions);

        assertEquals(List.of("register"), calls);
    }

    @Test
    void rejectsAnIntervalOfNoSecond() {
        var factory = new FuhitoAdminRealmResourceProviderFactory();

        for (String option : List.of("projection-timer-interval", "change-log-cleanup-interval")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> factory.init(options(Map.of(option, 0))));
            assertEquals("The option " + option + " must be a number of seconds of 1 or more, not 0.",
                    e.getMessage());
            assertDoesNotThrow(() -> factory.init(options(Map.of(option, 1))));
        }
    }

    @Test
    void closingHoldsNothingToRelease() {
        assertDoesNotThrow(() -> new FuhitoAdminRealmResourceProviderFactory().close());
        assertDoesNotThrow(() -> new FuhitoJpaEntityProviderFactory().close());
        assertDoesNotThrow(() -> new FuhitoEventListenerProviderFactory().close());
        assertDoesNotThrow(() -> new UserUpdateEvents((realmId, userId) -> { }).close());
    }

    // A realm lists the listeners that it calls. Keycloak calls a global one in every realm.
    @Test
    void theEventListenerHearsEveryRealm() {
        assertEquals(List.of(true, "fuhito"), List.of(new FuhitoEventListenerProviderFactory().isGlobal(),
                new FuhitoEventListenerProviderFactory().getId()));
    }

    @Test
    void theEntityListIsEmptyBecauseKeycloakFindsTheEntitiesItself() {
        @SuppressWarnings("removal")
        List<Class<?>> entities = new FuhitoJpaEntityProvider().getEntities();

        assertEquals(List.of(), entities);
    }
}
