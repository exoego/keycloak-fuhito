package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenMapperIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "token-mapper-password";

    private static Keycloak admin;
    private static String realm;
    private static Api api;

    @BeforeAll
    static void createARealmWithAClientThatHasTheMapper() {
        admin = KEYCLOAK.getKeycloakAdminClient();
        var realmRepresentation = new RealmRepresentation();
        realm = "token-mapper-" + UUID.randomUUID();
        realmRepresentation.setRealm(realm);
        realmRepresentation.setEnabled(true);
        TestRealm.withTimeZone(realmRepresentation);
        admin.realms().create(realmRepresentation);
        api = new Api(realm, admin.tokenManager().getAccessTokenString());

        var mapper = new ProtocolMapperRepresentation();
        mapper.setName("org assignments");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("fuhito-assignments-mapper");
        mapper.setConfig(Map.of("claim.name", "org", "access.token.claim", "true", "id.token.claim", "true",
                "userinfo.token.claim", "true"));
        var client = new ClientRepresentation();
        client.setClientId("app");
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setProtocolMappers(List.of(mapper));
        admin.realm(realm).clients().create(client).close();
    }

    private static String user(String username) {
        var credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setEmailVerified(true);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEnabled(true);
        user.setCredentials(List.of(credential));
        try (Response response = admin.realm(realm).users().create(user)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static JsonNode tokens(String username) throws Exception {
        String form = "grant_type=password&client_id=app&scope=openid&username=" + username + "&password=" + PASSWORD;
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/realms/" + realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private static JsonNode claims(String jwt) throws Exception {
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private static String post(String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    @Test
    void theTokenHasTheAssignmentsThatHoldToday() throws Exception {
        String physician = user("physician");
        String hospital = post("/org-units", "{\"name\": \"X病院\", \"code\": \"x-hospital\", \"validFrom\": \"2020-04-01\"}");
        String medicine = post("/org-units", "{\"name\": \"内科\", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}");
        String committee = post("/org-units", "{\"name\": \"医療安全委員会\", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}");
        String ended = post("/org-units", "{\"name\": \"旧部署\", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"OCCUPATION\", \"code\": \"physician\", \"name\": \"医師\"}").statusCode());
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"JOB_TITLE\", \"code\": \"chair\", \"name\": \"委員長\"}").statusCode());
        String employment = post("/employments", "{\"userId\": \"" + physician + "\", \"validFrom\": \"2020-04-01\", \"staffNumber\": \"D-1\"}");
        String start = "{\"employmentId\": \"" + employment + "\", \"validFrom\": \"2020-04-01\", \"orgUnitId\": \"";
        post("/assignments", start + committee + "\", \"jobTitle\": \"chair\", \"head\": true}");
        post("/assignments", start + medicine + "\", \"occupation\": \"physician\", \"primary\": true}");
        post("/assignments", start + ended + "\", \"validTo\": \"2021-04-01\"}");

        JsonNode tokens = tokens("physician");

        JsonNode org = claims(tokens.get("access_token").asText()).get("org");
        assertEquals(2, org.size());
        JsonNode primary = org.get(0);
        assertEquals(medicine, primary.get("orgUnitId").asText());
        assertEquals("内科", primary.get("orgUnitName").asText());
        assertEquals(List.of("X病院", "内科"), primary.get("orgUnitPath").findValuesAsText("name"));
        assertEquals(hospital, primary.get("orgUnitPath").get(0).get("id").asText());
        assertEquals("physician", primary.get("occupation").asText());
        assertTrue(primary.get("primary").asBoolean());
        assertFalse(primary.get("head").asBoolean());
        assertEquals("D-1", primary.get("staffNumber").asText());
        assertEquals("ACTIVE", primary.get("status").asText());
        assertFalse(primary.has("jobTitle"));
        JsonNode chair = org.get(1);
        assertEquals(committee, chair.get("orgUnitId").asText());
        assertEquals("chair", chair.get("jobTitle").asText());
        assertTrue(chair.get("head").asBoolean());
        assertEquals(org, claims(tokens.get("id_token").asText()).get("org"));
    }

    @Test
    void aUserWithNoAssignmentGetsAnEmptyList() throws Exception {
        user("newcomer");

        JsonNode org = claims(tokens("newcomer").get("access_token").asText()).get("org");

        assertTrue(org.isArray());
        assertEquals(0, org.size());
    }
}
