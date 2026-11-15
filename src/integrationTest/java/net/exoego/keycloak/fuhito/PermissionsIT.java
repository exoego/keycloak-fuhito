package net.exoego.keycloak.fuhito;

import java.util.ArrayList;
import java.util.List;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an administrator may read with each role of Keycloak.
 */
@ResourceLock("master")
class PermissionsIT {

    private static final String REALM = "master";
    private static final String PASSWORD = "permissions-it-password";

    private static final List<Keycloak> CLIENTS = new ArrayList<>();
    private static Api structureReader;
    private static Api reader;
    private static String unit;

    @BeforeAll
    static void createAdministrators() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var master = admin.realm(REALM).toRepresentation();
        TestRealm.withTimeZone(master);
        admin.realm(REALM).update(master);
        Api all = new Api(REALM, admin.tokenManager().getAccessTokenString());
        var created = all.post("/org-units", "{\"name\": \"Permissions\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        unit = Api.json(created).get("id").asText();

        structureReader = administrator(admin, "structure-reader", "query-users");
        reader = administrator(admin, "people-reader", "view-users");
    }

    private static Api administrator(Keycloak admin, String username, String role) {
        var credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setEmailVerified(true);
        user.setFirstName("Permissions");
        user.setLastName(role);
        user.setEnabled(true);
        user.setCredentials(List.of(credential));
        String userId;
        try (Response response = admin.realm(REALM).users().create(user)) {
            assertEquals(201, response.getStatus());
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        String adminClientId = admin.realm(REALM).clients().findByClientId("master-realm").get(0).getId();
        var granted = admin.realm(REALM).clients().get(adminClientId).roles().get(role).toRepresentation();
        admin.realm(REALM).users().get(userId).roles().clientLevel(adminClientId).add(List.of(granted));

        Keycloak client = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(REALM)
                .clientId("admin-cli")
                .username(username)
                .password(PASSWORD)
                .build();
        // Closing the client logs out, and that makes its token invalid. So it stays open until the tests end.
        CLIENTS.add(client);
        return new Api(REALM, client.tokenManager().getAccessTokenString());
    }

    @AfterAll
    static void logOut() {
        CLIENTS.forEach(Keycloak::close);
    }

    private static List<String> structureReads() {
        return List.of("", "/org-units", "/org-units?legalEntity=true", "/org-units/" + unit, "/org-units/" + unit + "/children",
                "/org-units/" + unit + "/subtree", "/org-units/" + unit + "/path", "/org-units/" + unit + "/history",
                "/hierarchies", "/ref-items?kind=UNIT_TYPE");
    }

    private static List<String> peopleReads() {
        return List.of("/org-units/" + unit + "/members", "/people", "/change-log", "/change-orders",
                "/effective-changes");
    }

    @Test
    void aCallerWhoMayQueryUsersReadsTheStructureOnly() throws Exception {
        for (String path : structureReads()) {
            assertEquals(200, structureReader.get(path).statusCode(), path);
        }
        for (String path : peopleReads()) {
            assertEquals(403, structureReader.get(path).statusCode(), path);
        }
        assertEquals(403, structureReader.post("/org-units", "{\"name\": \"x\", \"validFrom\": \"2020-04-01\"}").statusCode());
    }

    @Test
    void aCallerWhoMayViewUsersReadsEverythingAndWritesNothing() throws Exception {
        for (String path : structureReads()) {
            assertEquals(200, reader.get(path).statusCode(), path);
        }
        for (String path : peopleReads()) {
            assertEquals(200, reader.get(path).statusCode(), path);
        }
        assertEquals(403, reader.post("/org-units", "{\"name\": \"x\", \"validFrom\": \"2020-04-01\"}").statusCode());
    }
}
