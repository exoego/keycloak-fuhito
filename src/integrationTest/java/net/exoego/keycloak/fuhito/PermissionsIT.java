package net.exoego.keycloak.fuhito;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * What an administrator may read with each role of Keycloak.
 */
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

        structureReader = administrator(admin, REALM, "master-realm", "structure-reader", "query-users");
        reader = administrator(admin, REALM, "master-realm", "people-reader", "view-users");
    }

    static Api administrator(Keycloak admin, String realm, String adminClient, String username,
            String role) {
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
        try (Response response = admin.realm(realm).users().create(user)) {
            assertEquals(201, response.getStatus());
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        String adminClientId = admin.realm(realm).clients().findByClientId(adminClient).get(0).getId();
        var granted = admin.realm(realm).clients().get(adminClientId).roles().get(role).toRepresentation();
        admin.realm(realm).users().get(userId).roles().clientLevel(adminClientId).add(List.of(granted));

        Keycloak client = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(realm)
                .clientId("admin-cli")
                .username(username)
                .password(PASSWORD)
                .build();
        // Closing the client logs out, and that makes its token invalid. So it stays open until the tests end.
        CLIENTS.add(client);
        return new Api(realm, client.tokenManager().getAccessTokenString());
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

    @Test
    void aRealmLimitsHowFarAheadACallerWhoMayQueryUsersReadsTheStructure() throws Exception {
        TestRealm realm = TestRealm.create("structure-limit", Map.of("fuhito.structure.daysAhead", "30"));
        try {
            Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
            Api limited = administrator(admin, realm.name, "realm-management", "limited", "query-users");
            Api everything = administrator(admin, realm.name, "realm-management", "everything", "view-users");
            LocalDate lastDay = LocalDate.now(ZoneOffset.UTC).plusDays(30);
            String before = unit(realm.api, "Before");
            String unit = unit(realm.api, "Now");
            String after = unit(realm.api, "After");
            succeed(realm.api, lastDay, before, unit);
            succeed(realm.api, lastDay.plusDays(1), unit, after);

            assertEquals(200, limited.get("/org-units/" + unit + "?asOf=" + lastDay).statusCode());
            var tooFar = limited.get("/org-units/" + unit + "?asOf=" + lastDay.plusDays(1));
            assertEquals(403, tooFar.statusCode());
            assertEquals("structure.too_far_ahead", Api.json(tooFar).get("error").asText());
            assertEquals(403, limited.get("/org-units?asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(403, limited.get("/org-units/" + unit + "/history").statusCode());
            assertEquals(200, everything.get("/org-units/" + after + "?asOf=" + lastDay.plusDays(1)).statusCode());
            assertEquals(200, everything.get("/org-units/" + unit + "/history").statusCode());

            JsonNode seen = Api.json(limited.get("/org-units/" + unit + "/succession"));
            assertEquals(List.of(before), seen.get("predecessors").findValuesAsText("orgUnitId"));
            assertEquals(0, seen.get("successors").size());
            JsonNode all = Api.json(everything.get("/org-units/" + unit + "/succession"));
            assertEquals(List.of(after), all.get("successors").findValuesAsText("orgUnitId"));

            var representation = admin.realm(realm.name).toRepresentation();
            representation.getAttributes().put("fuhito.structure.daysAhead", "soon");
            admin.realm(realm.name).update(representation);
            var invalid = limited.get("/org-units/" + unit);
            assertEquals(409, invalid.statusCode());
            assertEquals("structure.days_ahead_invalid", Api.json(invalid).get("error").asText());
            assertEquals(200, everything.get("/org-units/" + unit).statusCode());
        } finally {
            realm.remove();
        }
    }

    private static String unit(Api api, String name) throws Exception {
        var created = api.post("/org-units", "{\"name\": \"" + name + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        return Api.json(created).get("id").asText();
    }

    private static void succeed(Api api, LocalDate date, String id, String successorId) throws Exception {
        var done = api.post("/reorganizations", "{\"effectiveDate\": \"" + date + "\", \"steps\": [{\"op\": "
                + "\"succeed\", \"id\": \"" + id + "\", \"successorIds\": [\"" + successorId + "\"]}]}");
        assertEquals(200, done.statusCode(), done.body());
    }
}
