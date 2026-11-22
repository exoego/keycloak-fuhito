package net.exoego.keycloak.fuhito.keycloak;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Listens to the events of every realm, also of a realm that stores no events and lists no event listener.
 */
public class FuhitoEventListenerProviderFactory implements EventListenerProviderFactory {

    /**
     * The provider ID.
     */
    public static final String ID = "fuhito";

    /**
     * Creates the factory.
     */
    public FuhitoEventListenerProviderFactory() {
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new UserUpdateEvents((realmId, userId) -> UserNameChangeHandler.record(session, realmId, userId));
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }
}
