package net.exoego.keycloak.fuhito.keycloak;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.jpa.JpaHierarchyStore;
import net.exoego.keycloak.fuhito.jpa.RealmData;
import org.keycloak.Config;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.PostMigrationEvent;

/**
 * Registers the JPA entities and the Liquibase changelog of this extension.
 */
public class FuhitoJpaEntityProviderFactory implements JpaEntityProviderFactory {

    /**
     * The provider ID. Keycloak also uses it in the name of the Liquibase changelog table.
     */
    public static final String ID = "fuhito";

    /**
     * Creates the factory.
     */
    public FuhitoJpaEntityProviderFactory() {
    }

    @Override
    public JpaEntityProvider create(KeycloakSession session) {
        return new FuhitoJpaEntityProvider();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Each event runs in the transaction that removes the realm or the user, so both happen or neither does.
        factory.register(event -> {
            switch (event) {
                // Realms from before the extension, or from before its roles, get the roles at the start.
                case PostMigrationEvent migrated -> KeycloakModelUtils.runJobInTransaction(factory,
                        session -> session.realms().getRealmsStream().forEach(FuhitoRoles::addTo));
                // A realm has its default hierarchy from its start, so two first requests never both make it.
                case RealmModel.RealmPostCreateEvent created -> {
                    new JpaHierarchyStore(entityManager(created.getKeycloakSession()),
                            created.getCreatedRealm().getId()).defaultId();
                    FuhitoRoles.addTo(created.getCreatedRealm());
                }
                case RealmModel.RealmRemovedEvent removed ->
                        RealmData.delete(entityManager(removed.getKeycloakSession()), removed.getRealm().getId());
                case UserModel.UserRemovedEvent removed ->
                        UserRemovalHandler.retire(entityManager(removed.getKeycloakSession()), removed.getRealm(),
                                removed.getUser().getId());
                case OrganizationModel.OrganizationRemovedEvent removed -> {
                    KeycloakSession session = removed.getKeycloakSession();
                    RealmData.unlinkOrganization(entityManager(session), session.getContext().getRealm().getId(),
                            removed.getOrganization().getId());
                }
                default -> { }
            }
        });
    }

    private static EntityManager entityManager(KeycloakSession session) {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }
}
