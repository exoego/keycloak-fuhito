package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;

    @BeforeAll
    static void createRealms() {
        realm = TestRealm.create("people");
        otherRealm = TestRealm.create("people");
        api = realm.api;
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void createsReadsReplacesAndDeletesAPersonWithNoUser() throws Exception {
        JsonNode person = created(api.post("/people", "{\"name\": \"山田 太郎\", \"names\": {\"en\": \"Taro Yamada\"}, "
                + "\"phoneticName\": \"やまだ たろう\", \"attributes\": {\"派遣元\": \"A社\"}, "
                + "\"externalId\": \"P-1\", \"source\": \"hr\"}"));
        String id = person.get("id").asText();

        assertFalse(person.has("userId"));
        assertEquals("山田 太郎", person.get("name").asText());
        assertEquals("RECORD", person.get("nameSource").asText());
        assertEquals("Taro Yamada", person.get("names").get("en").asText());
        assertEquals("やまだ たろう", person.get("phoneticName").asText());
        assertEquals("A社", person.get("attributes").get("派遣元").asText());
        assertEquals("P-1", person.get("externalId").asText());
        assertEquals("hr", person.get("source").asText());
        assertEquals(person, ok(api.get("/people/" + id)));

        JsonNode changed = ok(api.put("/people/" + id, "{\"name\": \"山田 花子\"}"));
        assertEquals("山田 花子", changed.get("name").asText());
        assertEquals(0, changed.get("names").size());
        assertFalse(changed.has("phoneticName"));
        assertEquals("P-1", changed.get("externalId").asText());

        assertEquals(204, api.delete("/people/" + id).statusCode());
        assertEquals("person.not_found", errorOf(404, api.get("/people/" + id)));
        assertEquals(List.of("CREATE", "UPDATE", "DELETE"),
                Api.json(api.get("/change-log?resourceType=FUHITO_PERSON&resourceId=" + id))
                        .findValuesAsText("operation"));
    }

    @Test
    void aPersonWithNoNameOfItsOwnShowsTheNameOfKeycloak() throws Exception {
        String user = realm.user("taro", "Taro", "Yamada");
        String nameless = realm.user("nameless");

        JsonNode person = created(api.post("/people", "{\"userId\": \"" + user + "\"}"));
        assertEquals(user, person.get("userId").asText());
        assertEquals("Taro Yamada", person.get("name").asText());
        assertEquals("KEYCLOAK", person.get("nameSource").asText());
        // The change log has the name too, so that a mask or an erase clears it there.
        assertTrue(api.get("/change-log?resourceType=FUHITO_PERSON&resourceId=" + person.get("id").asText())
                .body().contains("Taro Yamada"));

        JsonNode noName = created(api.post("/people", "{\"userId\": \"" + nameless + "\"}"));
        assertFalse(noName.has("name"));
        assertFalse(noName.has("nameSource"));

        // A name of the record wins from then on.
        JsonNode named = ok(api.put("/people/" + person.get("id").asText(),
                "{\"userId\": \"" + user + "\", \"name\": \"山田 太郎\"}"));
        assertEquals("山田 太郎", named.get("name").asText());
        assertEquals("RECORD", named.get("nameSource").asText());

        JsonNode found = ok(api.get("/people?userId=" + user));
        assertEquals(List.of(person.get("id").asText()), found.findValuesAsText("id"));
        assertEquals(0, ok(api.get("/people?userId=nobody")).size());
    }

    @Test
    void aRealmCanPutTheFamilyNameFirst() throws Exception {
        TestRealm japanese = TestRealm.create("people-order", Map.of("fuhito.nameOrder", "family-first"));
        String user = japanese.user("taro", "太郎", "山田");

        JsonNode person = created(japanese.api.post("/people", "{\"userId\": \"" + user + "\"}"));

        assertEquals("山田 太郎", person.get("name").asText());
        assertEquals("KEYCLOAK", person.get("nameSource").asText());
    }

    @Test
    void listsThePeopleInPages() throws Exception {
        TestRealm scratch = TestRealm.create("people-list");
        for (String name : List.of("A", "B", "C")) {
            created(scratch.api.post("/people", "{\"name\": \"" + name + "\"}"));
        }

        List<String> all = ok(scratch.api.get("/people")).findValuesAsText("id");
        assertEquals(3, all.size());
        assertEquals(all.stream().sorted().toList(), all);
        assertEquals(all.subList(1, 3), ok(scratch.api.get("/people?first=1&max=5")).findValuesAsText("id"));
        assertEquals(all.subList(0, 1), ok(scratch.api.get("/people?max=1")).findValuesAsText("id"));
        assertEquals(0, ok(otherRealm.api.get("/people")).size());
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String user = realm.user("linked");
        String id = created(api.post("/people", "{\"userId\": \"" + user
                + "\", \"externalId\": \"P-9\", \"source\": \"hr\"}")).get("id").asText();
        String other = created(api.post("/people", "{\"name\": \"Other\"}")).get("id").asText();

        assertEquals("person.name_required", errorOf(400, api.post("/people", "{}")));
        assertEquals("person.user_unknown", errorOf(400, api.post("/people", "{\"userId\": \"nobody\"}")));
        assertEquals("person.user_taken", errorOf(409, api.post("/people", "{\"userId\": \"" + user + "\"}")));
        assertEquals("person.user_taken", errorOf(409, api.put("/people/" + other,
                "{\"name\": \"Other\", \"userId\": \"" + user + "\"}")));
        assertEquals("person.external_id_taken", errorOf(409, api.post("/people",
                "{\"name\": \"X\", \"externalId\": \"P-9\", \"source\": \"hr\"}")));
        assertEquals("person.not_found", errorOf(404, otherRealm.api.get("/people/" + id)));
        assertEquals("person.not_found", errorOf(404, otherRealm.api.put("/people/" + id, "{\"name\": \"X\"}")));
        assertEquals("person.not_found", errorOf(404, otherRealm.api.delete("/people/" + id)));
        assertEquals(401, new Api(realm.name, null).get("/people").statusCode());
        assertEquals(401, new Api(realm.name, null).post("/people", "{}").statusCode());
    }

    @Test
    void maskingAUserClearsThePersonAndErasingRemovesIt() throws Exception {
        TestRealm scratch = TestRealm.create("people-personal-data");
        Api scratchApi = scratch.api;
        String user = scratch.user("left");
        String kept = created(scratchApi.post("/people", "{\"name\": \"Kept\"}")).get("id").asText();
        String id = created(scratchApi.post("/people", "{\"userId\": \"" + user + "\", \"name\": \"山田 太郎\", "
                + "\"names\": {\"en\": \"Taro Yamada\"}, \"phoneticName\": \"やまだ\", "
                + "\"attributes\": {\"住所\": \"東京都\"}, \"externalId\": \"P-1\", \"source\": \"hr\"}"))
                .get("id").asText();
        String log = "/change-log?resourceType=FUHITO_PERSON&resourceId=";

        assertEquals(200, scratchApi.post("/users/" + user + "/mask", "").statusCode());

        JsonNode masked = ok(scratchApi.get("/people/" + id));
        assertEquals(user, masked.get("userId").asText());
        assertEquals("hr", masked.get("source").asText());
        for (String field : List.of("name", "phoneticName", "externalId")) {
            assertFalse(masked.has(field), field);
        }
        assertEquals(0, masked.get("names").size());
        assertEquals(0, masked.get("attributes").size());
        HttpResponse<String> maskedLog = scratchApi.get(log + id);
        assertEquals(1, Api.json(maskedLog).size());
        for (String secret : List.of("山田", "Taro", "やまだ", "東京都", "P-1")) {
            assertFalse(maskedLog.body().contains(secret), secret);
        }

        assertEquals(204, scratchApi.delete("/users/" + user + "/data").statusCode());

        assertEquals("person.not_found", errorOf(404, scratchApi.get("/people/" + id)));
        assertEquals(0, Api.json(scratchApi.get(log + id)).size());
        assertEquals("Kept", ok(scratchApi.get("/people/" + kept)).get("name").asText());
        assertEquals(1, Api.json(scratchApi.get(log + kept)).size());
    }

    @Test
    void aPersonWithNoUserCanBeEmployedAndAssigned() throws Exception {
        TestRealm scratch = TestRealm.create("people-employed");
        Api scratchApi = scratch.api;
        String unit = created(scratchApi.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String cleaner = created(scratchApi.post("/people", "{\"name\": \"清掃 花子\"}")).get("id").asText();
        String nurse = scratch.user("nurse");

        JsonNode employment = created(scratchApi.post("/employments",
                "{\"personId\": \"" + cleaner + "\", \"validFrom\": \"2020-04-01\"}"));
        assertEquals(cleaner, employment.get("personId").asText());
        assertFalse(employment.has("userId"));
        JsonNode assignment = created(scratchApi.post("/assignments", "{\"employmentId\": \""
                + employment.get("id").asText() + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\", \"head\": true}"));
        assertFalse(assignment.has("userId"));
        // The staff shortcut takes a person too.
        String visitor = created(scratchApi.post("/people", "{\"name\": \"応援 医師\"}")).get("id").asText();
        JsonNode staff = created(scratchApi.post("/staff", "{\"personId\": \"" + visitor + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        assertEquals(visitor, staff.get("employment").get("personId").asText());
        created(scratchApi.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\"}"));

        JsonNode members = ok(scratchApi.get("/org-units/" + unit + "/members"));
        assertEquals(3, members.size());
        assertEquals(List.of(nurse), members.findValuesAsText("userId"));
        assertTrue(members.findValuesAsText("personId").containsAll(List.of(cleaner, visitor)));
        // The head has no user, so the derived manager of the nurse has no user ID.
        JsonNode managers = ok(scratchApi.get("/users/" + nurse + "/managers"));
        assertEquals(1, managers.size());
        assertFalse(managers.get(0).has("userId"));
        assertTrue(managers.get(0).get("derived").asBoolean());

        // The person gets an account later. The employment then shows the user.
        String account = scratch.user("cleaner");
        ok(scratchApi.put("/people/" + cleaner, "{\"name\": \"清掃 花子\", \"userId\": \"" + account + "\"}"));
        assertEquals(account, ok(scratchApi.get("/employments/" + employment.get("id").asText())).get("userId").asText());
        assertEquals(List.of(unit), ok(scratchApi.get("/users/" + account + "/assignments")).findValuesAsText("orgUnitId"));
        assertEquals(List.of(account), ok(scratchApi.get("/users/" + nurse + "/managers")).findValuesAsText("userId"));

        assertEquals("person.in_use", errorOf(409, scratchApi.delete("/people/" + cleaner)));
    }

    @Test
    void aPersonWithNoUserCanHoldAQualificationAndBeAManager() throws Exception {
        TestRealm scratch = TestRealm.create("people-lines");
        Api scratchApi = scratch.api;
        scratch.refItem("QUALIFICATION", "license", "免許");
        String unit = created(scratchApi.post("/org-units", "{\"name\": \"外科\", \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String visiting = created(scratchApi.post("/people", "{\"name\": \"応援 医師\"}")).get("id").asText();
        String advisor = created(scratchApi.post("/people", "{\"name\": \"顧問\"}")).get("id").asText();
        String resident = scratch.user("resident");

        JsonNode license = created(scratchApi.post("/qualifications", "{\"personId\": \"" + visiting
                + "\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}"));
        assertEquals(visiting, license.get("personId").asText());
        assertFalse(license.has("userId"));
        assertEquals("person.in_use", errorOf(409, scratchApi.delete("/people/" + visiting)));

        String assignment = created(scratchApi.post("/staff", "{\"userId\": \"" + resident + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}")).get("assignment").get("id").asText();
        JsonNode line = created(scratchApi.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerPersonId\": \"" + advisor + "\", \"validFrom\": \"2020-04-01\"}"));
        assertEquals(advisor, line.get("managerPersonId").asText());
        assertFalse(line.has("managerUserId"));
        assertEquals("person.in_use", errorOf(409, scratchApi.delete("/people/" + advisor)));

        JsonNode managers = ok(scratchApi.get("/users/" + resident + "/managers"));
        assertEquals(List.of(advisor), managers.findValuesAsText("personId"));
        assertFalse(managers.get(0).has("userId"));
        assertFalse(managers.get(0).get("derived").asBoolean());

        assertEquals("reporting_line.manager_unknown", errorOf(400, scratchApi.post("/reporting-lines",
                "{\"assignmentId\": \"" + assignment + "\", \"managerPersonId\": \"nobody\", "
                        + "\"validFrom\": \"2020-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, scratchApi.post("/reporting-lines",
                "{\"assignmentId\": \"" + assignment + "\", \"validFrom\": \"2020-04-01\"}")));
        assertEquals("qualification.person_unknown", errorOf(400, scratchApi.post("/qualifications",
                "{\"personId\": \"nobody\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}")));
    }

    @Test
    void anEmploymentByUserIdUsesThePersonOfTheUserOrCreatesIt() throws Exception {
        TestRealm scratch = TestRealm.create("people-by-user");
        Api scratchApi = scratch.api;
        String user = scratch.user("taro", "Taro", "Yamada");
        String start = "{\"userId\": \"" + user + "\", \"validFrom\": \"";

        JsonNode first = created(scratchApi.post("/employments", start + "2020-04-01\", \"validTo\": \"2021-04-01\"}"));
        JsonNode second = created(scratchApi.post("/employments", start + "2022-04-01\"}"));

        String personId = first.get("personId").asText();
        assertEquals(user, first.get("userId").asText());
        assertEquals(personId, second.get("personId").asText());
        JsonNode people = ok(scratchApi.get("/people"));
        assertEquals(List.of(personId), people.findValuesAsText("id"));
        assertEquals("Taro Yamada", people.get(0).get("name").asText());
        assertEquals(List.of("CREATE"), Api.json(scratchApi.get("/change-log?resourceType=FUHITO_PERSON&resourceId="
                + personId)).findValuesAsText("operation"));

        assertEquals("employment.person_unknown", errorOf(400, scratchApi.post("/employments",
                "{\"personId\": \"nobody\", \"validFrom\": \"2020-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, scratchApi.post("/employments",
                "{\"validFrom\": \"2020-04-01\"}")));
        assertEquals("employment.person_unknown", errorOf(400, otherRealm.api.post("/employments",
                "{\"personId\": \"" + personId + "\", \"validFrom\": \"2020-04-01\"}")));
    }
}
