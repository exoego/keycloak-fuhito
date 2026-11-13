package net.exoego.keycloak.fuhito;

import java.util.UUID;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A new realm for one test class, with helpers to fill it.
 */
final class TestRealm {

    final String name;
    final Api api;
    private final Keycloak admin;

    private TestRealm(String name, Keycloak admin) {
        this.name = name;
        this.admin = admin;
        this.api = new Api(name, admin.tokenManager().getAccessTokenString());
    }

    static TestRealm create(String prefix) {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var realm = new RealmRepresentation();
        realm.setRealm(prefix + "-" + UUID.randomUUID());
        realm.setEnabled(true);
        admin.realms().create(realm);
        return new TestRealm(realm.getRealm(), admin);
    }

    String id() {
        return admin.realm(name).toRepresentation().getId();
    }

    /** Creates a user and returns its ID. */
    String user(String username) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = admin.realm(name).users().create(user)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    void removeUser(String userId) {
        admin.realm(name).users().delete(userId).close();
    }

    void remove() {
        admin.realm(name).remove();
    }

    /** Creates a reference item and returns its ID. */
    String refItem(String kind, String code, String itemName) throws Exception {
        var response = api.post("/ref-items",
                "{\"kind\": \"" + kind + "\", \"code\": \"" + code + "\", \"name\": \"" + itemName + "\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }
}
