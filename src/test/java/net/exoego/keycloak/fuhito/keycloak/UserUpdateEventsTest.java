package net.exoego.keycloak.fuhito.keycloak;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserUpdateEventsTest {

    private final List<String> updated = new ArrayList<>();
    private final UserUpdateEvents events = new UserUpdateEvents((realmId, userId) -> updated.add(realmId + " " + userId));

    private static Event event(EventType type) {
        var event = new Event();
        event.setType(type);
        event.setRealmId("r1");
        event.setUserId("u1");
        return event;
    }

    private static AdminEvent adminEvent(String resourceType, OperationType operation, String path) {
        var event = new AdminEvent();
        event.setRealmId("r1");
        event.setResourceTypeAsString(resourceType);
        event.setOperationType(operation);
        event.setResourcePath(path);
        return event;
    }

    @Test
    void anUpdateOfTheProfileByTheUserPassesOnTheUser() {
        events.onEvent(event(EventType.UPDATE_PROFILE));
        events.onEvent(event(EventType.UPDATE_PROFILE_ERROR));
        events.onEvent(event(EventType.LOGIN));
        events.onEvent(event(EventType.UPDATE_EMAIL));

        assertEquals(List.of("r1 u1"), updated);
    }

    @Test
    void anUpdateOfAUserByTheAdminApiOrByScimPassesOnTheUser() {
        events.onEvent(adminEvent("USER", OperationType.UPDATE, "users/u1"), true);
        events.onEvent(adminEvent("USER", OperationType.UPDATE, "scim/v2/Users/u2"), false);
        events.onEvent(adminEvent("USER", OperationType.CREATE, "users/u3"), true);
        events.onEvent(adminEvent("USER", OperationType.ACTION, "users/u4/reset-password"), true);
        events.onEvent(adminEvent("GROUP", OperationType.UPDATE, "groups/g1"), true);
        events.onEvent(adminEvent("FUHITO_PERSON", OperationType.UPDATE, "fuhito/people/p1"), true);

        assertEquals(List.of("r1 u1", "r1 u2"), updated);
    }
}
