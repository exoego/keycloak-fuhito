package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefItemsIT {

    private static Api api;
    private static Api otherRealmApi;

    @BeforeAll
    static void createRealms() {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String token = admin.tokenManager().getAccessTokenString();
        api = new Api(createRealm(admin), token);
        otherRealmApi = new Api(createRealm(admin), token);
    }

    private static String createRealm(Keycloak admin) {
        var realm = new RealmRepresentation();
        realm.setRealm("ref-items-" + UUID.randomUUID());
        realm.setEnabled(true);
        TestRealm.withTimeZone(realm);
        admin.realms().create(realm);
        return realm.getRealm();
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static List<String> codes(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("code");
    }

    @Test
    void createsAndReadsAnItemWithJapaneseText() throws Exception {
        JsonNode item = created(api.post("/ref-items", """
                {"kind": "UNIT_TYPE", "code": "ward", "name": "病棟", "names": {"en": "Ward", "JA": "病棟"},
                 "displayOrder": 2, "attributes": {"hasBeds": true, "note": "夜勤あり"}}
                """));

        assertEquals(36, item.get("id").asText().length());
        assertEquals("UNIT_TYPE", item.get("kind").asText());
        assertEquals("病棟", item.get("name").asText());
        assertEquals("Ward", item.get("names").get("en").asText());
        assertEquals("病棟", item.get("names").get("ja").asText());
        assertTrue(item.get("active").asBoolean());

        JsonNode read = Api.json(api.get("/ref-items/" + item.get("id").asText()));
        assertEquals(item, read);
        assertEquals("夜勤あり", read.get("attributes").get("note").asText());
        assertTrue(read.get("attributes").get("hasBeds").asBoolean());
    }

    @Test
    void listsByDisplayOrderThenCode() throws Exception {
        created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"list-b\", \"name\": \"B\", \"displayOrder\": 91}"));
        created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"list-a\", \"name\": \"A\", \"displayOrder\": 91}"));
        created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"list-z\", \"name\": \"Z\", \"displayOrder\": 90}"));

        List<String> codes = codes(api.get("/ref-items?kind=UNIT_TYPE"));

        assertEquals(List.of("list-z", "list-a", "list-b"), codes.stream().filter(c -> c.startsWith("list-")).toList());
    }

    @Test
    void everyKindHasItsOwnCodes() throws Exception {
        for (String kind : List.of("UNIT_TYPE", "JOB_TITLE", "OCCUPATION", "EMPLOYMENT_TYPE", "GRADE", "END_REASON",
                "ASSIGNMENT_KIND", "QUALIFICATION")) {
            JsonNode item = created(api.post("/ref-items",
                    "{\"kind\": \"" + kind + "\", \"code\": \"same-code\", \"name\": \"" + kind + "\"}"));
            assertEquals(kind, item.get("kind").asText());
            assertEquals(List.of(kind), Api.json(api.get("/ref-items?kind=" + kind)).findValuesAsText("name").stream()
                    .filter(kind::equals).toList());
        }
        assertEquals(List.of("OCCUPATION"),
                Api.json(api.get("/ref-items?kind=OCCUPATION")).findValuesAsText("kind").stream().distinct().toList());
    }

    @Test
    void rejectsACodeThatIsTaken() throws Exception {
        String body = "{\"kind\": \"UNIT_TYPE\", \"code\": \"taken\", \"name\": \"Taken\"}";
        created(api.post("/ref-items", body));

        HttpResponse<String> response = api.post("/ref-items", body);

        assertEquals(409, response.statusCode());
        JsonNode error = Api.json(response);
        assertEquals("ref_item.code_taken", error.get("error").asText());
        assertEquals("taken", error.get("params").get("code").asText());
        assertEquals("The code \"taken\" is already used for the kind UNIT_TYPE.", error.get("message").asText());
    }

    @Test
    void rejectsBadValues() throws Exception {
        assertEquals("invalid.code", errorOf(400, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"bad code\", \"name\": \"x\"}")));
        assertEquals("invalid.name", errorOf(400, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"blank-name\", \"name\": \" \"}")));
        assertEquals("invalid.kind", errorOf(400, api.post("/ref-items",
                "{\"kind\": \"NOPE\", \"code\": \"x\", \"name\": \"x\"}")));
        assertEquals("invalid.language_tag", errorOf(400, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"bad-tag\", \"name\": \"x\", \"names\": {\"ja_JP\": \"x\"}}")));
        assertEquals("invalid.required", errorOf(400, api.get("/ref-items")));
        assertEquals("invalid.kind", errorOf(400, api.get("/ref-items?kind=NOPE")));
    }

    @Test
    void theMessageFollowsTheLanguageOfTheRequest() throws Exception {
        String path = "/ref-items/no-such-id";
        String english = "The reference item \"no-such-id\" does not exist.";
        String japanese = "参照データ「no-such-id」は存在しません。";

        assertEquals(english, messageOf(api.get(path)));
        assertEquals(japanese, messageOf(api.withAcceptLanguage("ja").get(path)));
        assertEquals(japanese, messageOf(api.withAcceptLanguage("ja-JP,ja;q=0.9,en;q=0.8").get(path)));
        assertEquals(english, messageOf(api.withAcceptLanguage("en-US,ja;q=0.5").get(path)));
        assertEquals(english, messageOf(api.withAcceptLanguage("fr").get(path)));
        assertEquals(english, messageOf(api.withAcceptLanguage("*").get(path)));
    }

    private static String messageOf(HttpResponse<String> response) throws Exception {
        JsonNode error = Api.json(response);
        assertEquals("ref_item.not_found", error.get("error").asText());
        return error.get("message").asText();
    }

    @Test
    void aFailedRequestWritesNothing() throws Exception {
        assertEquals(400, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"rolled-back\", \"name\": \"x\", \"names\": {\"??\": \"x\"}}").statusCode());

        assertFalse(codes(api.get("/ref-items?kind=UNIT_TYPE&includeInactive=true")).contains("rolled-back"));
    }

    @Test
    void updatesAndDeactivatesAnItem() throws Exception {
        String id = created(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"to-update\", \"name\": \"Old\", \"names\": {\"ja\": \"旧\"}}")).get("id").asText();

        HttpResponse<String> response = api.put("/ref-items/" + id,
                "{\"kind\": \"IGNORED\", \"code\": \"ignored\", \"name\": \"New\", \"displayOrder\": 5, \"active\": false}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode changed = Api.json(response);
        assertEquals("to-update", changed.get("code").asText());
        assertEquals("UNIT_TYPE", changed.get("kind").asText());
        assertEquals("New", changed.get("name").asText());
        assertEquals(0, changed.get("names").size());
        assertEquals(5, changed.get("displayOrder").asInt());
        assertFalse(changed.get("active").asBoolean());
        assertEquals(changed, Api.json(api.get("/ref-items/" + id)));

        assertFalse(codes(api.get("/ref-items?kind=UNIT_TYPE")).contains("to-update"));
        assertTrue(codes(api.get("/ref-items?kind=UNIT_TYPE&includeInactive=true")).contains("to-update"));
    }

    @Test
    void deletesAnItem() throws Exception {
        String id = created(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"to-delete\", \"name\": \"x\"}")).get("id").asText();

        assertEquals(204, api.delete("/ref-items/" + id).statusCode());

        assertEquals("ref_item.not_found", errorOf(404, api.get("/ref-items/" + id)));
        assertEquals("ref_item.not_found", errorOf(404, api.delete("/ref-items/" + id)));
        assertEquals("ref_item.not_found", errorOf(404, api.put("/ref-items/" + id, "{\"name\": \"x\"}")));
    }

    @Test
    void oneRealmDoesNotSeeTheItemsOfAnother() throws Exception {
        String body = "{\"kind\": \"UNIT_TYPE\", \"code\": \"per-realm\", \"name\": \"x\"}";
        String id = created(api.post("/ref-items", body)).get("id").asText();

        assertFalse(codes(otherRealmApi.get("/ref-items?kind=UNIT_TYPE")).contains("per-realm"));
        assertEquals(404, otherRealmApi.get("/ref-items/" + id).statusCode());
        assertEquals(404, otherRealmApi.put("/ref-items/" + id, "{\"name\": \"stolen\"}").statusCode());
        assertEquals(404, otherRealmApi.delete("/ref-items/" + id).statusCode());
        created(otherRealmApi.post("/ref-items", body));
        assertEquals("x", Api.json(api.get("/ref-items/" + id)).get("name").asText());
    }

    @Test
    void rejectsARequestWithoutAToken() throws Exception {
        var anonymous = new Api("master", null);
        assertEquals(401, anonymous.get("/ref-items?kind=UNIT_TYPE").statusCode());
        assertEquals(401, anonymous.post("/ref-items", "{}").statusCode());
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }
}
