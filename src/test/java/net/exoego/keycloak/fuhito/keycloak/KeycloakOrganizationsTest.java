package net.exoego.keycloak.fuhito.keycloak;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.organization.OrganizationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeycloakOrganizationsTest {

    private static KeycloakSession sessionWith(OrganizationProvider provider) {
        return (KeycloakSession) Proxy.newProxyInstance(KeycloakOrganizationsTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class}, (proxy, method, args) -> {
                    assertEquals("getProvider", method.getName());
                    assertEquals(OrganizationProvider.class, args[0]);
                    return provider;
                });
    }

    private static OrganizationProvider turnedOff() {
        return (OrganizationProvider) Proxy.newProxyInstance(KeycloakOrganizationsTest.class.getClassLoader(),
                new Class<?>[] {OrganizationProvider.class}, (proxy, method, args) -> {
                    assertEquals("isEnabled", method.getName());
                    return false;
                });
    }

    @Test
    void thereAreNoOrganizationsWhenTheServerRunsWithoutTheFeature() {
        assertEquals(List.of(), new KeycloakOrganizations(sessionWith(null), null).organizations());
    }

    @Test
    void thereAreNoOrganizationsWhenTheRealmTurnedThemOff() {
        assertEquals(List.of(), new KeycloakOrganizations(sessionWith(turnedOff()), null).organizations());
    }
}
