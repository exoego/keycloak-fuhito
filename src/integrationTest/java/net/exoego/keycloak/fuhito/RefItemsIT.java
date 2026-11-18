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
        TestRealm.createRealm(admin, realm);
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
        created(api.post("/attribute-definitions", "{\"target\": \"REF_ITEM\", \"name\": \"hasBeds\", "
                + "\"type\": \"BOOLEAN\"}"));
        created(api.post("/attribute-definitions", "{\"target\": \"REF_ITEM\", \"name\": \"note\", \"type\": \"STRING\"}"));
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
        assertEquals(codes.subList(1, 2), codes(api.get("/ref-items?kind=UNIT_TYPE&first=1&max=1")));
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
        assertEquals("invalid.required", errorOf(400, api.get("/ref-items/count")));
        assertEquals("invalid.kind", errorOf(400, api.get("/ref-items/count?kind=NOPE")));
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

        HttpResponse<String> response = api.putWithVersion("/ref-items/" + id,
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
        long active = api.count("/ref-items/count?kind=UNIT_TYPE");
        assertEquals(codes(api.get("/ref-items?kind=UNIT_TYPE")).size(), active);
        assertEquals(codes(api.get("/ref-items?kind=UNIT_TYPE&includeInactive=true")).size(),
                api.count("/ref-items/count?kind=UNIT_TYPE&includeInactive=true"));
        assertTrue(api.count("/ref-items/count?kind=UNIT_TYPE&includeInactive=true") > active);
    }

    @Test
    void aRenameFromADateKeepsTheOldNameForEarlierDates() throws Exception {
        String id = created(api.post("/ref-items", """
                {"kind": "JOB_TITLE", "code": "dated-chief", "name": "主任", "names": {"en": "Chief"}}
                """)).get("id").asText();

        HttpResponse<String> response = api.putWithVersion("/ref-items/" + id,
                "{\"name\": \"主任補佐\", \"names\": {\"en\": \"Senior Chief\"}, \"validFrom\": \"2027-04-01\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode renamed = Api.json(response);
        assertEquals("主任補佐", renamed.get("name").asText());
        assertEquals("2027-04-01", renamed.get("validFrom").asText());
        assertFalse(renamed.has("validTo"));
        assertEquals(2, renamed.get("version").asLong());
        JsonNode before = Api.json(api.get("/ref-items/" + id + "?asOf=2027-03-31"));
        assertEquals("主任", before.get("name").asText());
        assertEquals("Chief", before.get("names").get("en").asText());
        assertFalse(before.has("validFrom"));
        assertEquals("2027-04-01", before.get("validTo").asText());
        assertEquals(renamed, Api.json(api.get("/ref-items/" + id + "?asOf=2027-04-01")));
        assertEquals("主任", Api.json(api.get("/ref-items/" + id)).get("name").asText());
        assertEquals("主任", name(api.get("/ref-items?kind=JOB_TITLE&asOf=2027-03-31"), "dated-chief"));
        assertEquals("主任補佐", name(api.get("/ref-items?kind=JOB_TITLE&asOf=2027-04-01"), "dated-chief"));

        // A rename on the first date of the new names corrects them, and a write without a date renames today.
        assertEquals(200, api.putWithVersion("/ref-items/" + id,
                "{\"name\": \"係長\", \"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(200, api.putWithVersion("/ref-items/" + id,
                "{\"name\": \"主任A\", \"names\": {\"en\": \"Chief\"}}").statusCode());

        HttpResponse<String> history = api.get("/ref-items/" + id + "/history");
        assertEquals(200, history.statusCode(), history.body());
        assertEquals(Api.json(api.get("/ref-items/" + id)).get("version"), Api.json(history).get("version"));
        assertEquals("dated-chief", Api.json(history).get("code").asText());
        JsonNode versions = Api.json(history).get("versions");
        assertEquals(List.of("主任A", "係長"), versions.findValuesAsText("name"));
        assertFalse(versions.get(0).has("validFrom"));
        assertEquals("2027-04-01", versions.get(0).get("validTo").asText());
        assertEquals("Chief", versions.get(0).get("names").get("en").asText());
        assertEquals("2027-04-01", versions.get(1).get("validFrom").asText());
        assertFalse(versions.get(1).has("validTo"));
        assertEquals(4, Api.json(history).get("version").asLong());

        JsonNode log = Api.json(api.get("/change-log?resourceType=FUHITO_REF_ITEM&resourceId=" + id));
        assertEquals(List.of("CREATE", "UPDATE", "UPDATE", "UPDATE"), log.findValuesAsText("operation"));
        assertEquals("2027-04-01", log.get(1).get("representation").get("validFrom").asText());

        assertEquals("ref_item.not_found", errorOf(404, api.get("/ref-items/nope/history")));
        assertEquals("invalid.date", errorOf(400, api.get("/ref-items/" + id + "?asOf=soon")));
        assertEquals("invalid.date", errorOf(400, api.putWithVersion("/ref-items/" + id,
                "{\"name\": \"x\", \"validFrom\": \"soon\"}")));
    }

    @Test
    void createsAnItemWithNamesFromADate() throws Exception {
        JsonNode item = created(api.post("/ref-items",
                "{\"kind\": \"JOB_TITLE\", \"code\": \"dated-new\", \"name\": \"部長\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("2026-04-01", item.get("validFrom").asText());
        assertEquals(1, item.get("version").asLong());
        // The item has no dates of its own, so a date before its first names gets them.
        assertEquals("部長", Api.json(api.get("/ref-items/" + item.get("id").asText() + "?asOf=2000-01-01"))
                .get("name").asText());
        JsonNode versions = Api.json(api.get("/ref-items/" + item.get("id").asText() + "/history")).get("versions");
        assertEquals(1, versions.size());
        assertEquals("2026-04-01", versions.get(0).get("validFrom").asText());
    }

    private static String name(HttpResponse<String> list, String code) throws Exception {
        assertEquals(200, list.statusCode(), list.body());
        for (JsonNode item : Api.json(list)) {
            if (item.get("code").asText().equals(code)) {
                return item.get("name").asText();
            }
        }
        return null;
    }

    @Test
    void deletesAnItem() throws Exception {
        String id = created(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"to-delete\", \"name\": \"x\"}")).get("id").asText();

        assertEquals(204, api.deleteWithVersion("/ref-items/" + id).statusCode());

        assertEquals("ref_item.not_found", errorOf(404, api.get("/ref-items/" + id)));
        assertEquals("ref_item.not_found", errorOf(404, api.deleteWithVersion("/ref-items/" + id)));
        assertEquals("ref_item.not_found", errorOf(404, api.putWithVersion("/ref-items/" + id, "{\"name\": \"x\"}")));
    }

    @Test
    void oneRealmDoesNotSeeTheItemsOfAnother() throws Exception {
        String body = "{\"kind\": \"UNIT_TYPE\", \"code\": \"per-realm\", \"name\": \"x\"}";
        String id = created(api.post("/ref-items", body)).get("id").asText();

        assertFalse(codes(otherRealmApi.get("/ref-items?kind=UNIT_TYPE")).contains("per-realm"));
        assertEquals(404, otherRealmApi.get("/ref-items/" + id).statusCode());
        assertEquals(404, otherRealmApi.putWithVersion("/ref-items/" + id, "{\"name\": \"stolen\"}").statusCode());
        assertEquals(404, otherRealmApi.deleteWithVersion("/ref-items/" + id).statusCode());
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
