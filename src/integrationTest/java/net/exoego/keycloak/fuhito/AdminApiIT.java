package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiIT {

    private static final String REALM = "master";
    private static final String OTHER_ADMIN = "clients-viewer";
    private static final String OTHER_ADMIN_PASSWORD = "clients-viewer-password";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String adminToken;
    private static Keycloak otherAdmin;
    private static String otherAdminToken;

    @BeforeAll
    static void createTokens() {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        adminToken = admin.tokenManager().getAccessTokenString();
        // These tests use the master realm, which needs a time zone like any other realm.
        var master = admin.realm(REALM).toRepresentation();
        TestRealm.withTimeZone(master);
        admin.realm(REALM).update(master);

        var credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(OTHER_ADMIN_PASSWORD);
        credential.setTemporary(false);
        var user = new UserRepresentation();
        user.setUsername(OTHER_ADMIN);
        user.setEmail("clients-viewer@example.com");
        user.setEmailVerified(true);
        user.setFirstName("Clients");
        user.setLastName("Viewer");
        user.setEnabled(true);
        user.setCredentials(List.of(credential));
        String userId;
        try (Response response = admin.realm(REALM).users().create(user)) {
            assertEquals(201, response.getStatus());
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        // An admin who may view clients, and may not view users.
        String adminClientId = admin.realm(REALM).clients().findByClientId("master-realm").get(0).getId();
        var viewClients = admin.realm(REALM).clients().get(adminClientId).roles().get("view-clients").toRepresentation();
        admin.realm(REALM).users().get(userId).roles().clientLevel(adminClientId).add(List.of(viewClients));

        otherAdmin = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(REALM)
                .clientId("admin-cli")
                .username(OTHER_ADMIN)
                .password(OTHER_ADMIN_PASSWORD)
                .build();
        otherAdminToken = otherAdmin.tokenManager().getAccessTokenString();
    }

    // Closing the client logs out, and that makes its token invalid. So it stays open until the tests end.
    @AfterAll
    static void logOut() {
        otherAdmin.close();
    }

    @Test
    void returnsTheVersion() throws Exception {
        HttpResponse<String> response = get("", adminToken);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().matches("\\{\"version\":\"[^\"$]+\"}"), response.body());
    }

    @Test
    void readsHierarchiesThroughTheEntityManager() throws Exception {
        HttpResponse<String> response = get("/hierarchies", adminToken);

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }

    @Test
    void rejectsARequestWithoutAToken() throws Exception {
        assertEquals(401, get("", null).statusCode());
    }

    @Test
    void rejectsACallerWithoutThePermission() throws Exception {
        assertEquals(403, get("", otherAdminToken).statusCode());
        assertEquals(403, get("/hierarchies", otherAdminToken).statusCode());
        assertEquals(403, new Api(REALM, otherAdminToken).post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"forbidden\", \"name\": \"x\"}").statusCode());
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(
                URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + REALM + "/fuhito" + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
