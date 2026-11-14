package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QualificationsIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String physician;
    private static String licenseKindId;

    @BeforeAll
    static void createRealms() throws Exception {
        realm = TestRealm.create("qualifications");
        otherRealm = TestRealm.create("qualifications");
        api = realm.api;
        physician = realm.user("physician");
        licenseKindId = realm.refItem("QUALIFICATION", "physician-license", "医師免許");
        realm.refItem("QUALIFICATION", "narcotics-license", "麻薬施用者免許");
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void addsReadsReplacesAndDeletesAQualification() throws Exception {
        String user = realm.user("holder");
        JsonNode license = created(api.post("/qualifications", "{\"userId\": \"" + user
                + "\", \"kind\": \"narcotics-license\", \"number\": \"第12345号\", \"validFrom\": \"2024-01-01\", "
                + "\"validTo\": \"2026-01-01\", \"attributes\": {\"発行\": \"東京都\"}}"));
        String id = license.get("id").asText();

        assertEquals(user, license.get("userId").asText());
        assertEquals("narcotics-license", license.get("kind").asText());
        assertEquals("第12345号", license.get("number").asText());
        assertEquals("2026-01-01", license.get("validTo").asText());
        assertEquals("東京都", license.get("attributes").get("発行").asText());
        assertEquals(license, Api.json(api.get("/qualifications/" + id)));

        // The license is renewed for two more years.
        HttpResponse<String> response = api.put("/qualifications/" + id,
                "{\"kind\": \"narcotics-license\", \"number\": \"第12345号\", \"validFrom\": \"2024-01-01\", \"validTo\": \"2028-01-01\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("2028-01-01", Api.json(response).get("validTo").asText());
        assertEquals(0, Api.json(response).get("attributes").size());

        assertEquals(204, api.delete("/qualifications/" + id).statusCode());
        assertEquals("qualification.not_found", errorOf(404, api.get("/qualifications/" + id)));
        assertEquals(List.of("CREATE", "UPDATE", "DELETE"),
                Api.json(api.get("/change-log?resourceType=FUHITO_QUALIFICATION&resourceId=" + id)).findValuesAsText("operation"));
    }

    @Test
    void listsTheQualificationsOfAUserOnADate() throws Exception {
        String start = "{\"userId\": \"" + physician + "\", \"kind\": \"";
        created(api.post("/qualifications", start + "physician-license\", \"number\": \"123456\", \"validFrom\": \"2010-05-01\"}"));
        created(api.post("/qualifications", start + "narcotics-license\", \"validFrom\": \"2020-01-01\", \"validTo\": \"2022-01-01\"}"));
        created(api.post("/qualifications", start + "narcotics-license\", \"validFrom\": \"2022-01-01\", \"validTo\": \"2024-01-01\"}"));

        assertEquals(List.of("physician-license", "narcotics-license"),
                Api.json(api.get("/users/" + physician + "/qualifications?asOf=2021-06-01")).findValuesAsText("kind"));
        assertEquals(List.of("physician-license"),
                Api.json(api.get("/users/" + physician + "/qualifications?asOf=2024-01-01")).findValuesAsText("kind"));
        assertEquals(List.of("physician-license"),
                Api.json(api.get("/users/" + physician + "/qualifications")).findValuesAsText("kind"));
        assertEquals(3, Api.json(api.get("/users/" + physician + "/qualifications?all=true")).size());
        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + licenseKindId)));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String id = created(api.post("/qualifications", "{\"userId\": \"" + physician
                + "\", \"kind\": \"physician-license\", \"validFrom\": \"2010-05-01\"}")).get("id").asText();

        assertEquals("person.user_unknown", errorOf(400, api.post("/qualifications",
                "{\"userId\": \"nobody\", \"kind\": \"physician-license\", \"validFrom\": \"2010-05-01\"}")));
        assertEquals("ref_item.unknown", errorOf(400, api.post("/qualifications",
                "{\"userId\": \"" + physician + "\", \"kind\": \"nope\", \"validFrom\": \"2010-05-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.post("/qualifications",
                "{\"userId\": \"" + physician + "\", \"kind\": \"physician-license\"}")));
        assertEquals("invalid.period", errorOf(400, api.put("/qualifications/" + id,
                "{\"kind\": \"physician-license\", \"validFrom\": \"2010-05-01\", \"validTo\": \"2010-01-01\"}")));
        assertEquals("user.not_found", errorOf(404, api.get("/users/nobody/qualifications")));
        assertEquals("qualification.not_found", errorOf(404, otherRealm.api.get("/qualifications/" + id)));
        assertEquals("qualification.not_found", errorOf(404, otherRealm.api.delete("/qualifications/" + id)));
        assertEquals("user.not_found", errorOf(404, otherRealm.api.get("/users/" + physician + "/qualifications")));
        assertEquals(401, new Api(realm.name, null).post("/qualifications", "{}").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/users/" + physician + "/qualifications").statusCode());
    }

    @Test
    void removingAUserKeepsTheirQualificationsAndErasingRemovesThem() throws Exception {
        TestRealm scratch = TestRealm.create("qualifications-removal");
        scratch.refItem("QUALIFICATION", "license", "免許");
        String removed = scratch.user("removed");
        String kept = scratch.user("kept");
        String removedId = created(scratch.api.post("/qualifications", "{\"userId\": \"" + removed
                + "\", \"kind\": \"license\", \"number\": \"第1号\", \"validFrom\": \"2010-05-01\"}")).get("id").asText();
        String keptId = created(scratch.api.post("/qualifications", "{\"userId\": \"" + kept
                + "\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}")).get("id").asText();
        String log = "/change-log?resourceType=FUHITO_QUALIFICATION&resourceId=";

        scratch.removeUser(removed);

        assertEquals("第1号", Api.json(scratch.api.get("/qualifications/" + removedId)).get("number").asText());
        assertEquals(1, Api.json(scratch.api.get("/users/" + removed + "/qualifications?all=true")).size());
        assertEquals(1, Api.json(scratch.api.get(log + removedId)).size());

        assertEquals(204, scratch.api.delete("/users/" + removed + "/data").statusCode());

        assertEquals(404, scratch.api.get("/qualifications/" + removedId).statusCode());
        assertEquals(0, Api.json(scratch.api.get(log + removedId)).size());
        assertEquals(200, scratch.api.get("/qualifications/" + keptId).statusCode());
        assertFalse(Api.json(scratch.api.get(log + keptId)).isEmpty());
    }
}
