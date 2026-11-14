package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMaskIT {

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    // An employment from 2020 to 2024 with every identifying value, an assignment, and a qualification.
    private static List<String> hire(TestRealm realm, String userId, String unit, String staffNumber) throws Exception {
        Api api = realm.api;
        String employment = created(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2024-04-01\", \"staffNumber\": \"" + staffNumber
                + "\", \"employmentType\": \"full-time\", \"attributes\": {\"住所\": \"東京都\"}, "
                + "\"externalId\": \"HR-" + staffNumber + "\", \"source\": \"hr\"}")).get("id").asText();
        assertEquals(200, api.put("/employments/" + employment + "/terms", "{\"validFrom\": \"2022-04-01\", "
                + "\"employmentType\": \"full-time\", \"status\": \"ON_LEAVE\", \"attributes\": {\"理由\": \"育休\"}}").statusCode());
        String assignment = created(api.post("/assignments", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\", \"jobTitle\": \"chief\", "
                + "\"occupation\": \"nurse\", \"primary\": true, \"attributes\": {\"内線\": \"1234\"}, "
                + "\"externalId\": \"A-" + staffNumber + "\", \"source\": \"hr\"}")).get("id").asText();
        String qualification = created(api.post("/qualifications", "{\"userId\": \"" + userId
                + "\", \"kind\": \"license\", \"number\": \"第" + staffNumber + "号\", \"validFrom\": \"2010-05-01\", "
                + "\"attributes\": {\"発行\": \"東京都\"}}")).get("id").asText();
        return List.of(employment, assignment, qualification);
    }

    private static TestRealm realm() throws Exception {
        TestRealm realm = TestRealm.create("user-mask");
        realm.refItem("EMPLOYMENT_TYPE", "full-time", "常勤");
        realm.refItem("JOB_TITLE", "chief", "主任");
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("QUALIFICATION", "license", "免許");
        return realm;
    }

    @Test
    void maskingKeepsEveryRecordAndClearsWhatIdentifiesThePerson() throws Exception {
        TestRealm realm = realm();
        Api api = realm.api;
        String unit = created(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String left = realm.user("left");
        String other = realm.user("other");
        List<String> ids = hire(realm, left, unit, "N-001");
        List<String> otherIds = hire(realm, other, unit, "N-002");
        JsonNode otherBefore = Api.json(api.get("/users/" + other + "/history"));
        assertFalse(Api.json(api.get("/users/" + left + "/history")).has("maskedAt"));

        HttpResponse<String> response = api.post("/users/" + left + "/mask", "");

        assertEquals(200, response.statusCode(), response.body());
        String maskedAt = Api.json(response).get("maskedAt").asText();
        assertTrue(maskedAt.endsWith("Z"));
        assertEquals(left, Api.json(response).get("userId").asText());

        // Every record is still there with the same ID, and the structure is intact.
        JsonNode history = Api.json(api.get("/users/" + left + "/history"));
        assertEquals(maskedAt, history.get("maskedAt").asText());
        JsonNode employment = history.get("employments").get(0);
        assertEquals(ids.get(0), employment.get("id").asText());
        assertEquals(left, employment.get("userId").asText());
        assertEquals("2020-04-01", employment.get("validFrom").asText());
        assertEquals("2024-04-01", employment.get("validTo").asText());
        assertEquals(List.of("ACTIVE", "ON_LEAVE"), employment.get("terms").findValuesAsText("status"));
        assertEquals(List.of("full-time", "full-time"), employment.get("terms").findValuesAsText("employmentType"));
        JsonNode assignment = history.get("assignments").get(0);
        assertEquals(ids.get(1), assignment.get("id").asText());
        assertEquals(unit, assignment.get("orgUnitId").asText());
        assertEquals("chief", assignment.get("periods").get(0).get("jobTitle").asText());
        assertEquals("nurse", assignment.get("periods").get(0).get("occupation").asText());
        JsonNode qualification = Api.json(api.get("/qualifications/" + ids.get(2)));
        assertEquals("license", qualification.get("kind").asText());
        assertEquals("2010-05-01", qualification.get("validFrom").asText());
        assertEquals(List.of(left, other).stream().sorted().toList(),
                Api.json(api.get("/org-units/" + unit + "/members?asOf=2021-04-01")).findValuesAsText("userId").stream().sorted().toList());

        // What identifies the person is gone.
        assertFalse(employment.has("staffNumber"));
        assertFalse(employment.has("externalId"));
        assertEquals("hr", employment.get("source").asText());
        employment.get("terms").forEach(terms -> assertEquals(0, terms.get("attributes").size()));
        assertFalse(assignment.has("externalId"));
        assertEquals(0, assignment.get("periods").get(0).get("attributes").size());
        assertFalse(qualification.has("number"));
        assertEquals(0, qualification.get("attributes").size());

        // The copies in the change log are cleared too, and the entries themselves stay.
        String everything = "";
        for (String[] resource : new String[][] {{"FUHITO_EMPLOYMENT", ids.get(0)}, {"FUHITO_ASSIGNMENT", ids.get(1)},
                {"FUHITO_QUALIFICATION", ids.get(2)}}) {
            HttpResponse<String> log = api.get("/change-log?resourceType=" + resource[0] + "&resourceId=" + resource[1]);
            assertFalse(Api.json(log).isEmpty());
            everything += log.body();
        }
        assertEquals(2, Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + ids.get(0))).size());
        for (String secret : List.of("N-001", "HR-N-001", "A-N-001", "東京都", "育休", "1234", "第N-001号")) {
            assertFalse(everything.contains(secret), secret);
        }
        assertTrue(everything.contains("ON_LEAVE"));
        assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_change_log where realm_id = '" + realm.id()
                + "' and resource_id in ('" + String.join("','", ids) + "') and (representation like '%N-001%'"
                + " or representation like '%東京都%' or representation like '%育休%' or representation like '%1234%')"));

        // The other user is untouched.
        assertEquals(otherBefore, Api.json(api.get("/users/" + other + "/history")));
        assertEquals("第N-002号", Api.json(api.get("/qualifications/" + otherIds.get(2))).get("number").asText());
        assertTrue(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + otherIds.get(0)).body().contains("N-002"));

        // The log says who masked whom. Masking again keeps the first time.
        JsonNode maskLog = Api.json(api.get("/change-log?resourceType=FUHITO_USER_DATA&resourceId=" + left));
        assertEquals(List.of("UPDATE"), maskLog.findValuesAsText("operation"));
        assertEquals("fuhito/users/" + left + "/mask", maskLog.get(0).get("resourcePath").asText());
        assertEquals(maskedAt, Api.json(api.post("/users/" + left + "/mask", "")).get("maskedAt").asText());
    }

    @Test
    void aRemovedUserCanBeMaskedAndAnEmployedUserCannot() throws Exception {
        TestRealm realm = realm();
        Api api = realm.api;
        String unit = created(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String removed = realm.user("removed");
        String employed = realm.user("employed");
        List<String> removedIds = hire(realm, removed, unit, "N-010");
        hire(realm, employed, unit, "N-020");
        created(api.post("/employments", "{\"userId\": \"" + employed + "\", \"validFrom\": \"2025-04-01\", \"staffNumber\": \"N-020\"}"));
        realm.removeUser(removed);

        assertEquals(200, api.post("/users/" + removed + "/mask", "").statusCode());
        assertFalse(Api.json(api.get("/employments/" + removedIds.get(0))).has("staffNumber"));

        HttpResponse<String> rejected = api.post("/users/" + employed + "/mask", "");
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals("user.still_employed", Api.json(rejected).get("error").asText());
        assertEquals("N-020", Api.json(api.get("/users/" + employed + "/history")).get("employments").get(0).get("staffNumber").asText());
        assertEquals(404, api.post("/users/no-such-user/mask", "").statusCode());
        assertEquals(401, new Api(realm.name, null).post("/users/" + removed + "/mask", "").statusCode());

        // Erasing is still possible after masking, and removes the mark too.
        assertEquals(204, api.delete("/users/" + removed + "/data").statusCode());
        assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_user_mask where user_id = '" + removed + "'"));
    }

    @Test
    void aUserWithOnlyAQualificationCanBeMasked() throws Exception {
        TestRealm realm = realm();
        Api api = realm.api;
        String user = realm.user("licensed");
        String kept = created(api.post("/qualifications", "{\"userId\": \"" + user
                + "\", \"kind\": \"license\", \"number\": \"L-1\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String deleted = created(api.post("/qualifications", "{\"userId\": \"" + user
                + "\", \"kind\": \"license\", \"number\": \"L-2\", \"validFrom\": \"2021-04-01\"}")).get("id").asText();
        // The log entry of a delete has no state to mask.
        assertEquals(204, api.delete("/qualifications/" + deleted).statusCode());

        assertEquals(200, api.post("/users/" + user + "/mask", "").statusCode());

        assertFalse(Api.json(api.get("/qualifications/" + kept)).has("number"));
        assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_change_log where realm_id = '"
                + realm.id() + "' and representation like '%L-1%'"));
    }
}
