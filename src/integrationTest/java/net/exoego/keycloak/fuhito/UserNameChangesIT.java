package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * People whose read gives the name of their Keycloak user. An administrator, the user, or an identity provider over
 * SCIM changes the user in Keycloak, or an administrator removes it. The realm stores no events and lists no event
 * listener of the extension.
 */
class UserNameChangesIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "user-name-changes-password";
    private static final String SCIM_SECRET = "user-name-changes-scim-secret";

    private static Keycloak admin;
    private static TestRealm realm;
    private static Api api;
    private static String realmUrl;

    @BeforeAll
    static void createARealmWithAClientForTheUsersAndOneForScim() throws Exception {
        admin = KEYCLOAK.getKeycloakAdminClient();
        realm = TestRealm.create("user-names");
        api = realm.api;
        realmUrl = KEYCLOAK.getAuthServerUrl() + "/realms/" + realm.name;
        var app = new ClientRepresentation();
        app.setClientId("app");
        app.setPublicClient(true);
        app.setDirectAccessGrantsEnabled(true);
        admin.realm(realm.name).clients().create(app).close();

        // The admin client of the tests is older than the SCIM server, so it cannot turn it on.
        HttpResponse<String> scimOn = send(HttpRequest.newBuilder(URI.create(KEYCLOAK.getAuthServerUrl()
                        + "/admin/realms/" + realm.name))
                .header("Authorization", "Bearer " + admin.tokenManager().getAccessTokenString())
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"scimApiEnabled\": true}")));
        assertEquals(204, scimOn.statusCode(), scimOn.body());
        var audience = new ProtocolMapperRepresentation();
        audience.setName("scim audience");
        audience.setProtocol("openid-connect");
        audience.setProtocolMapper("oidc-audience-mapper");
        audience.setConfig(Map.of("included.custom.audience", realmUrl + "/scim/v2", "access.token.claim", "true"));
        var scim = new ClientRepresentation();
        scim.setClientId("scim");
        scim.setSecret(SCIM_SECRET);
        scim.setServiceAccountsEnabled(true);
        scim.setFullScopeAllowed(false);
        scim.setProtocolMappers(List.of(audience));
        String scimId;
        try (Response created = admin.realm(realm.name).clients().create(scim)) {
            scimId = CreatedResponseUtil.getCreatedId(created);
        }
        String management = admin.realm(realm.name).clients().findByClientId("realm-management").getFirst().getId();
        RoleRepresentation manageUsers = admin.realm(realm.name).clients().get(management).roles()
                .get("manage-users").toRepresentation();
        String serviceAccount = admin.realm(realm.name).clients().get(scimId).getServiceAccountUser().getId();
        admin.realm(realm.name).users().get(serviceAccount).roles().clientLevel(management).add(List.of(manageUsers));
        admin.realm(realm.name).clients().get(scimId).getScopeMappings().clientLevel(management)
                .add(List.of(manageUsers));
    }

    private static String person(String body) throws Exception {
        HttpResponse<String> response = api.post("/people", body);
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static void update(String userId, Consumer<UserRepresentation> change) {
        UserResource user = admin.realm(realm.name).users().get(userId);
        UserRepresentation representation = user.toRepresentation();
        change.accept(representation);
        user.update(representation);
    }

    private static List<String> kindsAndCauses(String personId) throws Exception {
        return ok(api.get("/change-log?resourceId=" + personId)).valueStream()
                .map(entry -> entry.get("kind").asText() + " " + entry.path("cause").asText())
                .toList();
    }

    @Test
    void anAdminUpdateOfTheFirstNameAddsAPropagatedChangeOfThePerson() throws Exception {
        String user = realm.user("taro", "Taro", "Yamada");
        String person = person("{\"userId\": \"" + user + "\"}");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        update(user, representation -> representation.setFirstName("Jiro"));

        reads.assertEveryChangeIsLogged();
        JsonNode read = ok(api.get("/people/" + person));
        assertEquals(List.of("Jiro Yamada", "KEYCLOAK", Long.toString(version + 1)), List.of(
                read.get("name").asText(), read.get("nameSource").asText(), read.get("version").asText()));
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_USER_NAME_CHANGED"), kindsAndCauses(person));
        JsonNode propagated = ok(api.get("/change-log?kind=PROPAGATED&resourceId=" + person)).get(0);
        assertEquals(List.of("FUHITO_PERSON", "UPDATE", "fuhito/people/" + person), List.of(
                propagated.get("resourceType").asText(), propagated.get("operation").asText(),
                propagated.get("resourcePath").asText()));
        for (String absent : List.of("actorUserId", "changeOrderId", "causeSequence")) {
            assertFalse(propagated.has(absent), absent);
        }
        // The change log has no name.
        assertFalse(propagated.toString().contains("Jiro"), propagated.toString());
    }

    @Test
    void aPersonWithNamesInTheRecordGetsNoPropagatedChange() throws Exception {
        String user = realm.user("hanako", "Hanako", "Suzuki");
        String person = person("{\"userId\": \"" + user + "\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"鈴木 花子\"}}}");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        update(user, representation -> representation.setFirstName("Hana"));

        reads.assertEveryChangeIsLogged();
        assertEquals(version, api.version("/people/" + person));
        assertEquals(List.of("WRITE "), kindsAndCauses(person));
    }

    @Test
    void anUpdateThatLeavesTheNameOfTheReadAsItIsGivesNoPropagatedChange() throws Exception {
        String user = realm.user("jiro", "Jiro", "Sato");
        String person = person("{\"userId\": \"" + user + "\"}");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        update(user, representation -> representation.setEmail("jiro@example.com"));
        // The read joins the parts with no spaces around them.
        update(user, representation -> representation.setLastName(" Sato "));

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of("Jiro", " Sato "), realm.nameOfUser(user));
        assertEquals("Jiro Sato", ok(api.get("/people/" + person)).get("name").asText());
        assertEquals(version, api.version("/people/" + person));
        assertEquals(List.of("WRITE "), kindsAndCauses(person));
    }

    @Test
    void theRemovalOfTheUserAddsAPropagatedChangeOfAPersonWithNoNames() throws Exception {
        String named = realm.user("goro", "Goro", "Kato");
        String recorded = realm.user("rokuro", "Rokuro", "Kato");
        String nameless = realm.user("nanashi");
        String person = person("{\"userId\": \"" + named + "\"}");
        String withNames = person("{\"userId\": \"" + recorded + "\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"加藤 六郎\"}}}");
        String withNoName = person("{\"userId\": \"" + nameless + "\"}");
        List<Long> versions = List.of(api.version("/people/" + person), api.version("/people/" + withNames),
                api.version("/people/" + withNoName));
        DerivedReads reads = DerivedReads.before(api);

        realm.removeUser(named);
        realm.removeUser(recorded);
        realm.removeUser(nameless);

        reads.assertEveryChangeIsLogged();
        JsonNode read = ok(api.get("/people/" + person));
        assertEquals(List.of(named, Long.toString(versions.get(0) + 1), "false", "false"), List.of(
                read.get("userId").asText(), read.get("version").asText(), Boolean.toString(read.has("name")),
                Boolean.toString(read.has("nameSource"))));
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_USER_REMOVED"), kindsAndCauses(person));
        // The read of the others does not change.
        assertEquals(List.of(versions.get(1), versions.get(2)),
                List.of(api.version("/people/" + withNames), api.version("/people/" + withNoName)));
        assertEquals(List.of("WRITE "), kindsAndCauses(withNames));
        assertEquals(List.of("WRITE "), kindsAndCauses(withNoName));
    }

    private static String userWithPassword(String username, String firstName, String lastName) {
        var credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setEmailVerified(true);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEnabled(true);
        user.setCredentials(List.of(credential));
        try (Response response = admin.realm(realm.name).users().create(user)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String form) throws Exception {
        HttpResponse<String> tokens = send(HttpRequest.newBuilder(URI.create(realmUrl + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
        assertEquals(200, tokens.statusCode(), tokens.body());
        return JSON.readTree(tokens.body()).get("access_token").asText();
    }

    // The account console sends the same request with the token of the user.
    private static void updateOwnProfile(String username, String firstName, String lastName) throws Exception {
        var profile = JSON.createObjectNode()
                .put("username", username)
                .put("email", username + "@example.com")
                .put("firstName", firstName)
                .put("lastName", lastName);
        HttpResponse<String> updated = send(HttpRequest.newBuilder(URI.create(realmUrl + "/account"))
                .header("Authorization", "Bearer " + token("grant_type=password&client_id=app&username=" + username
                        + "&password=" + PASSWORD))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(profile.toString())));
        assertEquals(204, updated.statusCode(), updated.body());
    }

    @Test
    void anUpdateOfTheProfileInTheAccountConsoleAddsAPropagatedChangeOfThePerson() throws Exception {
        String user = userWithPassword("saburo", "Saburo", "Tanaka");
        String person = person("{\"userId\": \"" + user + "\"}");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        updateOwnProfile("saburo", "Saburo", "Takahashi");

        reads.assertEveryChangeIsLogged();
        JsonNode read = ok(api.get("/people/" + person));
        assertEquals(List.of("Saburo Takahashi", Long.toString(version + 1)),
                List.of(read.get("name").asText(), read.get("version").asText()));
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_USER_NAME_CHANGED"), kindsAndCauses(person));

        // A profile with the same name changes nothing that the read gives.
        updateOwnProfile("saburo", "Saburo", "Takahashi");

        assertEquals(version + 1, api.version("/people/" + person));
        assertEquals(2, api.count("/change-log/count?resourceId=" + person));
    }

    // An identity provider sends the users to the SCIM server of Keycloak.
    @Test
    void aChangeOfTheNameOverScimAddsAPropagatedChangeOfThePerson() throws Exception {
        String user = realm.user("shiro", "Shiro", "Ito");
        String person = person("{\"userId\": \"" + user + "\"}");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        HttpResponse<String> patched = send(HttpRequest.newBuilder(URI.create(realmUrl + "/scim/v2/Users/" + user))
                .header("Authorization", "Bearer " + token("grant_type=client_credentials&client_id=scim"
                        + "&client_secret=" + SCIM_SECRET))
                .header("Content-Type", "application/scim+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("""
                        {"schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                        "Operations": [{"op": "replace", "path": "name.givenName", "value": "Goro"}]}""")));
        assertEquals(200, patched.statusCode(), patched.body());

        reads.assertEveryChangeIsLogged();
        JsonNode read = ok(api.get("/people/" + person));
        assertEquals(List.of("Goro Ito", Long.toString(version + 1)),
                List.of(read.get("name").asText(), read.get("version").asText()));
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_USER_NAME_CHANGED"), kindsAndCauses(person));
    }
}
