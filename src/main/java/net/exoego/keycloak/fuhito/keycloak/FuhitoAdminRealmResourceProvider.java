package net.exoego.keycloak.fuhito.keycloak;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.BusinessDay;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.jpa.RealmData;
import net.exoego.keycloak.fuhito.rest.FuhitoResource;
import net.exoego.keycloak.fuhito.store.UserDirectory;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;
import org.keycloak.storage.ReadOnlyException;

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
        return resource(session, session, realm, auth, adminEvent);
    }

    // The request session knows the request. The other session does the reads and the writes.
    private static FuhitoResource resource(KeycloakSession request, KeycloakSession session, RealmModel realm,
            AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
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
                new KeycloakRequestContext(request, realm, auth, adminEvent, entityManager, timeSettingsError),
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

                    @Override
                    public Optional<String> displayName(String userId) {
                        return Optional.ofNullable(session.users().getUserById(realm, userId))
                                .map(user -> UserNames.of(user.getFirstName(), user.getLastName(),
                                        realm.getAttribute(UserNames.ORDER_ATTRIBUTE)))
                                .filter(name -> !name.isEmpty());
                    }

                    @Override
                    public boolean clearName(String userId) {
                        UserModel user = session.users().getUserById(realm, userId);
                        if (user == null) {
                            return false;
                        }
                        try {
                            user.setFirstName(null);
                            user.setLastName(null);
                        } catch (ReadOnlyException e) {
                            throw FuhitoException.conflict("person.keycloak_name_read_only",
                                    Map.of("userId", userId)).causedBy(e);
                        }
                        return true;
                    }
                }, organizations, new KeycloakUserAttributes(session, realm),
                personId -> RealmData.deletePerson(entityManager, realm.getId(), personId),
                new ProjectionTransactions(session.getKeycloakSessionFactory(), realm.getId(), rule),
                new FuhitoResource.Pieces() {
                    @Override
                    public <T> T alone(String job, Supplier<T> run) {
                        return OneAtATime.run(session.getKeycloakSessionFactory(), job, realm.getId(), run);
                    }

                    @Override
                    public <T> T inNewTransaction(boolean rollBack, Function<FuhitoResource, T> piece) {
                        return KeycloakModelUtils.runJobInTransactionWithResult(session.getKeycloakSessionFactory(),
                                own -> {
                                    RealmModel ownRealm = own.realms().getRealm(realm.getId());
                                    own.getContext().setRealm(ownRealm);
                                    T result = piece.apply(resource(request, own, ownRealm, auth,
                                            new AdminEventBuilder(ownRealm, auth.adminAuth(), own,
                                                    request.getContext().getConnection())));
                                    if (rollBack) {
                                        own.getTransactionManager().setRollbackOnly();
                                    }
                                    return result;
                                });
                    }
                });
    }

    @Override
    public void close() {
    }
}
