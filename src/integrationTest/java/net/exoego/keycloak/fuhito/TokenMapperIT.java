package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // A token gives the names of units in the language of the user, else of the realm.
        realmRepresentation.setInternationalizationEnabled(true);
        realmRepresentation.setSupportedLocales(Set.of("ja", "en"));
        realmRepresentation.setDefaultLocale("ja");
        TestRealm.withTimeZone(realmRepresentation);
        TestRealm.createRealm(admin, realmRepresentation);
        api = new Api(realm, admin.tokenManager().getAccessTokenString());
        TestRealm.legalEntity(api, "法人");

        var mapper = new ProtocolMapperRepresentation();
        mapper.setName("org assignments");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("fuhito-assignments-mapper");
        mapper.setConfig(Map.of("claim.name", "org", "access.token.claim", "true", "id.token.claim", "true",
                "userinfo.token.claim", "true", "fuhito.include.staffNumber", "true", "fuhito.include.status",
                "true"));
        // A mapper with no claim name uses the default name.
        var unnamed = new ProtocolMapperRepresentation();
        unnamed.setName("org assignments with the default name");
        unnamed.setProtocol("openid-connect");
        unnamed.setProtocolMapper("fuhito-assignments-mapper");
        unnamed.setConfig(Map.of("access.token.claim", "true"));
        // A claim name of only spaces counts as none.
        var blank = new ProtocolMapperRepresentation();
        blank.setName("org assignments with a blank name");
        blank.setProtocol("openid-connect");
        blank.setProtocolMapper("fuhito-assignments-mapper");
        blank.setConfig(Map.of("claim.name", "  ", "id.token.claim", "true"));
        var client = new ClientRepresentation();
        client.setClientId("app");
        client.setPublicClient(true);
        client.setDirectAccessGrantsEnabled(true);
        client.setProtocolMappers(List.of(mapper, unnamed, blank));
        admin.realm(realm).clients().create(client).close();
    }

    private static String user(String username) {
        return user(username, null);
    }

    private static String user(String username, String locale) {
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
        if (locale != null) {
            user.setAttributes(Map.of("locale", List.of(locale)));
        }
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
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.", -1)[1]), StandardCharsets.UTF_8));
    }

    private static String post(String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    @Test
    void theTokenHasTheAssignmentsThatHoldToday() throws Exception {
        String physician = user("physician");
        String hospital = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X病院\"}}, \"code\": \"x-hospital\", \"validFrom\": \"2020-04-01\"}");
        String medicine = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"内科\"}}, \"parentId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}");
        String committee = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"医療安全委員会\"}}, \"parentId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}");
        String ended = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"旧部署\"}}, \"parentId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"OCCUPATION\", \"code\": \"physician\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"医師\"}}}").statusCode());
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"JOB_TITLE\", \"code\": \"chair\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"委員長\"}}}").statusCode());
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
        assertFalse(primary.has("effectiveWorkLocation"));
        assertFalse(primary.has("effectiveCostCenter"));
        JsonNode chair = org.get(1);
        assertEquals(committee, chair.get("orgUnitId").asText());
        assertEquals("chair", chair.get("jobTitle").asText());
        assertTrue(chair.get("head").asBoolean());
        assertEquals(org, claims(tokens.get("id_token").asText()).get("org"));
        // A mapper that does not ask for them leaves the staff number and the status out.
        JsonNode plain = claims(tokens.get("access_token").asText()).get("assignments");
        assertEquals(org.findValuesAsText("assignmentId"), plain.findValuesAsText("assignmentId"));
        assertEquals(List.of(), plain.findValuesAsText("staffNumber"));
        assertEquals(List.of(), plain.findValuesAsText("status"));
        assertEquals("physician", plain.get(0).get("occupation").asText());
        assertEquals(plain, claims(tokens.get("id_token").asText()).get("assignments"));
    }

    @Test
    void theNamesOfTheUnitsAreInTheLanguageOfTheUser() throws Exception {
        String hospital = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"Y病院\"}, \"en\": {\"name\": \"Y Hospital\"}}, \"validFrom\": \"2020-04-01\"}");
        String ward = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東病棟\"}, "
                + "\"EN\": {\"name\": \"East Ward\"}}, \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}");
        for (String[] user : new String[][] {{"english-nurse", "en"}, {"japanese-nurse", null}}) {
            String employment = post("/employments", "{\"userId\": \"" + user(user[0], user[1])
                    + "\", \"validFrom\": \"2020-04-01\"}");
            post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + ward
                    + "\", \"primary\": true, \"validFrom\": \"2020-04-01\"}");
        }

        JsonNode english = claims(tokens("english-nurse").get("access_token").asText()).get("org").get(0);
        JsonNode japanese = claims(tokens("japanese-nurse").get("access_token").asText()).get("org").get(0);

        // The tag of the user matches a key in any case.
        assertEquals("East Ward", english.get("orgUnitName").asText());
        assertEquals(List.of("Y Hospital", "East Ward"), english.get("orgUnitPath").findValuesAsText("name"));
        // A user who picked no language gets the default language of the realm.
        assertEquals("東病棟", japanese.get("orgUnitName").asText());
        assertEquals(List.of("Y病院", "東病棟"), japanese.get("orgUnitPath").findValuesAsText("name"));
    }

    @Test
    void eachEntryHasTheWorkLocationAndTheCostCenterThatApply() throws Exception {
        String commuter = user("commuter");
        for (String[] item : new String[][] {{"WORK_LOCATION", "main-site"}, {"COST_CENTER", "cc-admin"},
                {"COST_CENTER", "cc-care"}}) {
            post("/ref-items", "{\"kind\": \"" + item[0] + "\", \"code\": \"" + item[1] + "\", "
                    + "\"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"" + item[1] + "\"}}}");
        }
        String clinic = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Zクリニック\"}}, \"workLocation\": \"main-site\", "
                + "\"costCenter\": \"cc-admin\", \"validFrom\": \"2020-04-01\"}");
        String care = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"外来\"}}, \"parentId\": \"" + clinic + "\", "
                + "\"costCenter\": \"cc-care\", \"validFrom\": \"2020-04-01\"}");
        String elsewhere = post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"別の組織\"}}, \"validFrom\": \"2020-04-01\"}");
        String employment = post("/employments", "{\"userId\": \"" + commuter + "\", \"validFrom\": \"2020-04-01\"}");
        String start = "{\"employmentId\": \"" + employment + "\", \"validFrom\": \"2020-04-01\", \"orgUnitId\": \"";
        String primary = post("/assignments", start + care + "\", \"primary\": true}");
        String other = post("/assignments", start + elsewhere + "\", \"costCenter\": \"cc-admin\"}");

        JsonNode entries = claims(tokens("commuter").get("access_token").asText()).get("assignments");

        // The work location comes from the clinic above, and the cost center from the unit itself.
        assertEquals(List.of(primary, other), entries.findValuesAsText("assignmentId"));
        assertEquals("main-site", entries.get(0).get("effectiveWorkLocation").asText());
        assertEquals("cc-care", entries.get(0).get("effectiveCostCenter").asText());
        // A value of the assignment applies, and a field with no value is left out.
        assertEquals("cc-admin", entries.get(1).get("effectiveCostCenter").asText());
        assertFalse(entries.get(1).has("effectiveWorkLocation"));
        assertFalse(entries.get(1).has("workLocation"));
    }

    @Test
    void aUserWithNoAssignmentGetsAnEmptyList() throws Exception {
        user("newcomer");

        JsonNode org = claims(tokens("newcomer").get("access_token").asText()).get("org");

        assertTrue(org.isArray());
        assertEquals(0, org.size());
    }

    // The admin console reads this to offer the mapper and to show its help.
    @Test
    void theServerInfoDescribesTheMapper() {
        var mapper = admin.serverInfo().getInfo().getProtocolMapperTypes().get("openid-connect").stream()
                .filter(type -> type.getId().equals("fuhito-assignments-mapper"))
                .findFirst()
                .orElseThrow();

        assertEquals("Org assignments", mapper.getName());
        assertEquals("Token mapper", mapper.getCategory());
        assertTrue(mapper.getHelpText().contains("org unit assignments"), mapper.getHelpText());
        assertTrue(mapper.getHelpText().contains("the work location and the cost center that apply"),
                mapper.getHelpText());
        assertFalse(mapper.getProperties().isEmpty());
    }
}
