package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.provider.ProviderConfigProperty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parts of the providers that Keycloak calls only for its help text or when it stops.
 */
class ProviderFactoriesTest {

    // A configuration where every option has the same value.
    private static Config.Scope everyOptionIs(Object value) {
        return (Config.Scope) Proxy.newProxyInstance(ProviderFactoriesTest.class.getClassLoader(),
                new Class<?>[] {Config.Scope.class}, (proxy, method, args) -> value);
    }

    @Test
    void describesTheOptionOfTheProjectionTimer() {
        List<ProviderConfigProperty> options = new FuhitoAdminRealmResourceProviderFactory().getConfigMetadata();

        assertEquals(1, options.size());
        assertEquals("projection-timer-enabled", options.get(0).getName());
        assertEquals(true, options.get(0).getDefaultValue());
        assertEquals("boolean", options.get(0).getType());
    }

    @Test
    void falseStartsNoTimer() {
        var factory = new FuhitoAdminRealmResourceProviderFactory();
        factory.init(everyOptionIs(false));

        // With a timer, this would ask the session factory, which is missing here.
        assertDoesNotThrow(() -> factory.postInit(null));
    }

    @Test
    void closingHoldsNothingToRelease() {
        assertDoesNotThrow(() -> new FuhitoAdminRealmResourceProviderFactory().close());
        assertDoesNotThrow(() -> new FuhitoJpaEntityProviderFactory().close());
    }

    @Test
    void theEntityListIsEmptyBecauseKeycloakFindsTheEntitiesItself() {
        @SuppressWarnings("removal")
        List<Class<?>> entities = new FuhitoJpaEntityProvider().getEntities();

        assertEquals(List.of(), entities);
    }
}
