package net.exoego.keycloak.fuhito.keycloak;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.jpa.JpaHierarchyStore;
import net.exoego.keycloak.fuhito.jpa.RealmData;
import net.exoego.keycloak.fuhito.jpa.RowLocks;
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
        // Each event runs in the transaction of the change in Keycloak, so both happen or neither does.
        factory.register(event -> {
            switch (event) {
                // Realms from before the extension, or from before its roles, get the roles at the start.
                case PostMigrationEvent migrated -> KeycloakModelUtils.runJobInTransaction(factory,
                        session -> session.realms().getRealmsStream().forEach(FuhitoRoles::addTo));
                // A realm has its row for writes and its default hierarchy from its start, so two first requests
                // never both make them.
                case RealmModel.RealmPostCreateEvent created -> {
                    EntityManager em = entityManager(created.getKeycloakSession());
                    String realmId = created.getCreatedRealm().getId();
                    RowLocks.lockRealm(em, realmId, false);
                    new JpaHierarchyStore(em, realmId).defaultId();
                    FuhitoRoles.addTo(created.getCreatedRealm());
                }
                case RealmModel.RealmRemovedEvent removed ->
                        RealmData.delete(entityManager(removed.getKeycloakSession()), removed.getRealm().getId());
                case UserModel.UserRemovedEvent removed ->
                        UserRemovalHandler.retire(entityManager(removed.getKeycloakSession()), removed.getRealm(),
                                removed.getUser());
                // Keycloak sends it only when the value changes, also for a write that records no admin event.
                case RealmModel.RealmAttributeUpdateEvent updated
                        when UserNames.ORDER_ATTRIBUTE.equals(updated.getAttributeName()) ->
                        NameOrderChangeHandler.record(updated.getKeycloakSession(), updated.getRealm().getId(),
                                updated.getAttributeValue());
                case OrganizationModel.OrganizationRemovedEvent removed -> {
                    KeycloakSession session = removed.getKeycloakSession();
                    OrganizationRemovalHandler.unlink(entityManager(session), session.getContext().getRealm().getId(),
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
