package net.exoego.keycloak.fuhito.keycloak;

import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.jpa.RealmData;
import net.exoego.keycloak.fuhito.rest.FuhitoResource;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Builds the root resource of the admin REST API for one request.
 */
public class FuhitoAdminRealmResourceProvider implements AdminRealmResourceProvider {

    /**
     * Creates the provider.
     */
    public FuhitoAdminRealmResourceProvider() {
    }

    @Override
    public Object getResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
            AdminEventBuilder adminEvent) {
        var entityManager = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        var organizations = new KeycloakOrganizations(session, realm);
        // With a bad setting, the request context rejects every call before the rule is used.
        BusinessDay.Rule rule = RealmTime.UTC;
        FuhitoException timeSettingsError = null;
        try {
            rule = RealmTime.rule(realm);
        } catch (FuhitoException e) {
            timeSettingsError = e;
        }
        return new FuhitoResource(realm.getId(),
                new KeycloakRequestContext(session, realm, auth, adminEvent, entityManager, timeSettingsError),
                entityManager, rule,
                new UserDirectory() {
                    @Override
                    public boolean exists(String userId) {
                        return session.users().getUserById(realm, userId) != null;
                    }

                    @Override
                    public Optional<String> findIdByUsername(String username) {
                        return Optional.ofNullable(session.users().getUserByUsername(realm, username))
                                .map(UserModel::getId);
                    }
                }, organizations, organizations, new KeycloakUserAttributes(session, realm),
                userId -> RealmData.deleteUser(entityManager, realm.getId(), userId));
    }

    @Override
    public void close() {
    }
}
