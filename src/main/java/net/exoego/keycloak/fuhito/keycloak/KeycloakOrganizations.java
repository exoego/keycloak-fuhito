package net.exoego.keycloak.fuhito.keycloak;

import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.organization.OrganizationProvider;

/**
 * The only class that calls the Organizations API of Keycloak. That API changes between minor versions.
 */
final class KeycloakOrganizations implements OrganizationDirectory {

    private final KeycloakSession session;

    KeycloakOrganizations(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public boolean exists(String organizationId) {
        OrganizationProvider provider = session.getProvider(OrganizationProvider.class);
        return provider != null && provider.isEnabled() && provider.getById(organizationId) != null;
    }
}
