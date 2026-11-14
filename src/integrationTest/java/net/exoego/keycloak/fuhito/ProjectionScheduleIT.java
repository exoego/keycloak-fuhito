package net.exoego.keycloak.fuhito;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Keycloak of its own with a short interval. The shared one keeps the default, so that its tests see no
 * rebuild that they did not ask for.
 */
class ProjectionScheduleIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static void createRealm(Keycloak admin, String name, String timeZone) {
        var realm = new RealmRepresentation();
        realm.setRealm(name);
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        Map<String, String> attributes = new HashMap<>(Map.of("fuhito.projection.enabled", "true"));
        if (timeZone != null) {
            attributes.put("fuhito.timeZone", timeZone);
        }
        realm.setAttributes(attributes);
        admin.realms().create(realm);
    }

    private static String post(Api api, String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = Api.json(response);
        return json.has("id") ? json.get("id").asText() : "";
    }

    private static List<String> groupNames(KeycloakContainer keycloak, String token, String realm, String organization)
            throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(keycloak.getAuthServerUrl()
                        + "/admin/realms/" + realm + "/organizations/" + organization + "/groups"))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("name").stream().sorted().toList();
    }

    private static List<String> waitForGroups(KeycloakContainer keycloak, String token, String realm,
            String organization, List<String> expected) throws Exception {
        List<String> names = List.of();
        for (int i = 0; i < 60; i++) {
            names = groupNames(keycloak, token, realm, organization);
            if (names.equals(expected)) {
                break;
            }
            Thread.sleep(500);
        }
        return names;
    }

    @Test
    void theTimerRebuildsTheProjectionWithNoCallToTheEndpoint() throws Exception {
        try (Network network = Network.newNetwork();
                PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
                        .withNetwork(network)
                        .withNetworkAliases("schedule-postgres")
                        .withDatabaseName("keycloak")
                        .withUsername("keycloak")
                        .withPassword("keycloak");
                KeycloakContainer keycloak =
                        new KeycloakContainer("quay.io/keycloak/keycloak:" + System.getProperty("keycloak.version"))
                                .withNetwork(network)
                                .withProviderLibsFrom(List.of(new File(System.getProperty("provider.jar"))))
                                .withEnv("KC_DB", "postgres")
                                .withEnv("KC_DB_URL", "jdbc:postgresql://schedule-postgres:5432/keycloak")
                                .withEnv("KC_DB_USERNAME", "keycloak")
                                .withEnv("KC_DB_PASSWORD", "keycloak")
                                .withEnv("KC_SPI_ADMIN_REALM_RESTAPI_EXTENSION__FUHITO__PROJECTION_INTERVAL_SECONDS",
                                        "2")) {
            postgres.start();
            keycloak.start();
            Keycloak admin = keycloak.getKeycloakAdminClient();
            String token = admin.tokenManager().getAccessTokenString();
            // This realm has no time zone. The timer skips it and goes on to the next realm.
            createRealm(admin, "a-broken", null);
            String brokenOrganization = OrganizationLinkIT.createOrganization(admin, "a-broken", "broken");
            // Two units below one parent have the same name here, so every rebuild of this realm fails.
            createRealm(admin, "a-failing", "UTC");
            String failingOrganization = OrganizationLinkIT.createOrganization(admin, "a-failing", "failing");
            Api failing = new Api(keycloak.getAuthServerUrl(), "a-failing", token);
            String clinic = post(failing, "/org-units", "{\"name\": \"Clinic\", \"validFrom\": \"2020-04-01\"}");
            for (int i = 0; i < 2; i++) {
                post(failing, "/org-units", "{\"name\": \"Office\", \"parentId\": \"" + clinic
                        + "\", \"validFrom\": \"2020-04-01\"}");
            }
            assertEquals(200, failing.put("/org-units/" + clinic + "/organization",
                    "{\"organizationId\": \"" + failingOrganization + "\"}").statusCode());
            createRealm(admin, "b-working", "UTC");
            String organization = OrganizationLinkIT.createOrganization(admin, "b-working", "hospital");
            Api api = new Api(keycloak.getAuthServerUrl(), "b-working", token);
            String hospital = post(api, "/org-units", "{\"name\": \"Hospital\", \"validFrom\": \"2020-04-01\"}");
            String nursing = post(api, "/org-units", "{\"name\": \"Nursing\", \"parentId\": \"" + hospital
                    + "\", \"validFrom\": \"2020-04-01\"}");
            var user = new UserRepresentation();
            user.setUsername("nurse");
            user.setEnabled(true);
            String nurse;
            try (Response response = admin.realm("b-working").users().create(user)) {
                nurse = CreatedResponseUtil.getCreatedId(response);
            }
            post(api, "/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + nursing
                    + "\", \"validFrom\": \"2020-04-01\"}");
            assertEquals(200, api.put("/org-units/" + hospital + "/organization",
                    "{\"organizationId\": \"" + organization + "\"}").statusCode());

            assertEquals(List.of("Nursing"), waitForGroups(keycloak, token, "b-working", organization,
                    List.of("Nursing")));

            // A later change is picked up by a later run.
            post(api, "/org-units", "{\"name\": \"Pharmacy\", \"parentId\": \"" + hospital
                    + "\", \"validFrom\": \"2020-04-01\"}");
            assertEquals(List.of("Nursing", "Pharmacy"), waitForGroups(keycloak, token, "b-working", organization,
                    List.of("Nursing", "Pharmacy")));
            assertEquals(List.of(), groupNames(keycloak, token, "a-broken", brokenOrganization));
            String logs = keycloak.getLogs();
            assertEquals(true, logs.contains("The projection runs every 2 seconds"), "the start is logged");
            assertEquals(true, logs.contains("skips realm a-broken"), "the skipped realm is logged");
            assertEquals(List.of(), groupNames(keycloak, token, "a-failing", failingOrganization));
            assertEquals(true, logs.contains("realm a-failing failed with projection.duplicate_name"),
                    "the failure is logged");
        }
    }
}
