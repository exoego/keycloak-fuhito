package net.exoego.keycloak.fuhito.keycloak;

import java.util.function.BiConsumer;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

/**
 * Passes on the events of Keycloak that can change the name of a user. Keycloak sends them in the transaction of
 * the change.
 */
final class UserUpdateEvents implements EventListenerProvider {

    private final BiConsumer<String, String> updated;

    /**
     * Creates the listener.
     *
     * @param updated takes the ID of the realm and the ID of the user that an event updated
     */
    UserUpdateEvents(BiConsumer<String, String> updated) {
        this.updated = updated;
    }

    // The account console and the required action to update the profile.
    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.UPDATE_PROFILE) {
            updated.accept(event.getRealmId(), event.getUserId());
        }
    }

    // The admin API and the SCIM server of Keycloak. The path of the event ends with the ID of the user.
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() == ResourceType.USER && event.getOperationType() == OperationType.UPDATE) {
            updated.accept(event.getRealmId(), event.getResourceId());
        }
    }

    @Override
    public void close() {
    }
}
