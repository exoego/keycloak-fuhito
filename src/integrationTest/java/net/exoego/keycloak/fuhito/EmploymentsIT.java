package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmploymentsIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String nurse;
    private static String doctor;
    private static String fullTimeId;

    @BeforeAll
    static void createRealms() throws Exception {
        realm = TestRealm.create("employments");
        otherRealm = TestRealm.create("employments");
        api = realm.api;
        nurse = realm.user("nurse");
        doctor = realm.user("doctor");
        fullTimeId = realm.refItem("EMPLOYMENT_TYPE", "full-time", "常勤");
        realm.refItem("EMPLOYMENT_TYPE", "temporary", "臨時職員");
        realm.refItem("GRADE", "g3", "3等級");
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
    void startsAnEmploymentWithOnlyAUserAndADate() throws Exception {
        JsonNode employment = created(api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals(nurse, employment.get("userId").asText());
        assertEquals("2026-04-01", employment.get("validFrom").asText());
        assertFalse(employment.has("validTo"));
        assertFalse(employment.has("staffNumber"));
        assertEquals(1, employment.get("terms").size());
        JsonNode terms = employment.get("terms").get(0);
        assertEquals("ACTIVE", terms.get("status").asText());
        assertEquals("2026-04-01", terms.get("validFrom").asText());
        assertFalse(terms.has("employmentType"));
        assertEquals(employment, Api.json(api.get("/employments/" + employment.get("id").asText())));
    }

    @Test
    void startsATemporaryPartTimeEmployment() throws Exception {
        JsonNode employment = created(api.post("/employments", "{\"userId\": \"" + doctor + "\", "
                + "\"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\", \"staffNumber\": \"000777\", "
                + "\"employmentType\": \"temporary\", \"grade\": \"g3\", \"workPercent\": 60.5, "
                + "\"attributes\": {\"note\": \"週3日\"}, \"externalId\": \"E-777\", \"source\": \"hr\"}"));

        assertEquals("2027-04-01", employment.get("validTo").asText());
        assertEquals("000777", employment.get("staffNumber").asText());
        assertEquals("E-777", employment.get("externalId").asText());
        JsonNode terms = employment.get("terms").get(0);
        assertEquals("temporary", terms.get("employmentType").asText());
        assertEquals("g3", terms.get("grade").asText());
        assertEquals(60.5, terms.get("workPercent").asDouble());
        assertEquals("週3日", terms.get("attributes").get("note").asText());
        assertEquals("2027-04-01", terms.get("validTo").asText());

        // The same person may be rehired with the same staff number. Another person may not take it.
        created(api.post("/employments", "{\"userId\": \"" + doctor
                + "\", \"validFrom\": \"2028-04-01\", \"staffNumber\": \"000777\"}"));
        assertEquals("employment.staff_number_taken", errorOf(409, api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\", \"staffNumber\": \"000777\"}")));
    }

    @Test
    void changesTermsForALeaveAReturnAndANewEmploymentType() throws Exception {
        String id = created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"temporary\", \"workPercent\": 50}"))
                .get("id").asText();

        // Parental leave from October, back in April as full-time staff.
        HttpResponse<String> leave = api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"employmentType\": \"temporary\", \"status\": \"ON_LEAVE\", \"workPercent\": 50}");
        assertEquals(200, leave.statusCode(), leave.body());
        JsonNode employment = Api.json(api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2027-04-01\", \"employmentType\": \"full-time\", \"grade\": \"g3\", \"workPercent\": 100}"));

        JsonNode terms = employment.get("terms");
        assertEquals(3, terms.size());
        assertEquals("ACTIVE", terms.get(0).get("status").asText());
        assertEquals("2026-10-01", terms.get(0).get("validTo").asText());
        assertEquals("ON_LEAVE", terms.get(1).get("status").asText());
        assertEquals("temporary", terms.get(1).get("employmentType").asText());
        assertEquals("2027-04-01", terms.get(1).get("validTo").asText());
        assertEquals("ACTIVE", terms.get(2).get("status").asText());
        assertEquals("full-time", terms.get(2).get("employmentType").asText());
        assertEquals("g3", terms.get(2).get("grade").asText());
        assertFalse(terms.get(2).has("validTo"));
        assertFalse(employment.has("validTo"));
        assertEquals(employment, Api.json(api.get("/employments/" + id)));

        // A change on the first date of stored terms corrects them.
        JsonNode corrected = Api.json(api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"status\": \"SUSPENDED\"}"));
        assertEquals(3, corrected.get("terms").size());
        assertEquals("SUSPENDED", corrected.get("terms").get(1).get("status").asText());
        assertFalse(corrected.get("terms").get(1).has("employmentType"));
    }

    @Test
    void rejectsABadChangeOfTerms() throws Exception {
        String id = created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\"}")).get("id").asText();

        assertEquals("employment.not_valid_on", errorOf(404, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2027-04-01\"}")));
        assertEquals("employment.not_valid_on", errorOf(404, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-03-31\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/employments/" + id + "/terms", "{}")));
        assertEquals("invalid.choice", errorOf(400, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-10-01\", \"status\": \"x\"}")));
        assertEquals("employment.not_found", errorOf(404, api.put("/employments/nope/terms", "{\"validFrom\": \"2026-10-01\"}")));
        assertEquals("employment.not_found", errorOf(404, otherRealm.api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-10-01\"}")));
        assertEquals(1, Api.json(api.get("/employments/" + id)).get("terms").size());
    }

    @Test
    void leavingEndsTheTermsAndTheAssignments() throws Exception {
        realm.refItem("END_REASON", "resignation-" + System.nanoTime(), "退職");
        String reason = "retirement-" + System.nanoTime();
        realm.refItem("END_REASON", reason, "定年");
        String user = realm.user("leaver-" + System.nanoTime());
        String id = created(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String unit = created(api.post("/org-units", "{\"name\": \"総務課\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String assignment = "{\"employmentId\": \"" + id + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"";
        String open = created(api.post("/assignments", assignment + "2026-04-01\"}")).get("id").asText();
        String future = created(api.post("/assignments", assignment + "2028-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2028-04-01\", \"status\": \"ON_LEAVE\"}").statusCode());

        HttpResponse<String> response = api.put("/employments/" + id + "/end",
                "{\"validTo\": \"2027-04-01\", \"endReason\": \"" + reason + "\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode ended = Api.json(response);
        assertEquals("2027-04-01", ended.get("validTo").asText());
        assertEquals(reason, ended.get("endReason").asText());
        assertEquals(1, ended.get("terms").size());
        assertEquals("2027-04-01", ended.get("terms").get(0).get("validTo").asText());
        assertEquals("2027-04-01", Api.json(api.get("/assignments/" + open)).get("validTo").asText());
        assertEquals("assignment.not_found", errorOf(404, api.get("/assignments/" + future)));

        // A rehire is a new employment of the same user. The old one stays readable.
        String rehire = created(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2029-04-01\"}"))
                .get("id").asText();
        assertEquals(ended, Api.json(api.get("/employments/" + id)));
        assertFalse(Api.json(api.get("/employments/" + rehire)).has("validTo"));

        // The resignation is taken back.
        JsonNode reopened = Api.json(api.delete("/employments/" + id + "/end"));
        assertFalse(reopened.has("validTo"));
        assertFalse(reopened.has("endReason"));
        assertFalse(reopened.get("terms").get(0).has("validTo"));
    }

    @Test
    void extendsAFixedTermEmploymentAndRejectsABadEnd() throws Exception {
        String user = realm.user("contract-" + System.nanoTime());
        String id = created(api.post("/employments", "{\"userId\": \"" + user
                + "\", \"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\", \"employmentType\": \"temporary\"}")).get("id").asText();

        JsonNode extended = Api.json(api.put("/employments/" + id + "/end", "{\"validTo\": \"2028-04-01\"}"));

        assertEquals("2028-04-01", extended.get("validTo").asText());
        assertEquals("2028-04-01", extended.get("terms").get(0).get("validTo").asText());
        assertEquals("temporary", extended.get("terms").get(0).get("employmentType").asText());
        assertEquals("employment.not_valid_on", errorOf(404, api.put("/employments/" + id + "/end", "{\"validTo\": \"2026-04-01\"}")));
        assertEquals("ref_item.unknown", errorOf(400, api.put("/employments/" + id + "/end", "{\"validTo\": \"2027-04-01\", \"endReason\": \"nope\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/employments/" + id + "/end", "{}")));
        assertEquals("employment.not_found", errorOf(404, otherRealm.api.put("/employments/" + id + "/end", "{\"validTo\": \"2027-04-01\"}")));
        assertEquals("employment.not_found", errorOf(404, otherRealm.api.delete("/employments/" + id + "/end")));
        assertEquals(extended, Api.json(api.get("/employments/" + id)));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String user = "\"userId\": \"" + nurse + "\"";
        assertEquals("person.user_unknown", errorOf(400, api.post("/employments",
                "{\"userId\": \"no-such-user\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.post("/employments", "{" + user + "}")));
        assertEquals("invalid.period", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"validTo\": \"2026-03-01\"}")));
        assertEquals("ref_item.unknown", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"employmentType\": \"g3\"}")));
        assertEquals("invalid.choice", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"status\": \"GONE\"}")));
        assertEquals("invalid.percent", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"workPercent\": 0}")));
        assertEquals("employment.not_found", errorOf(404, api.get("/employments/no-such-id")));
        assertEquals(401, new Api(realm.name, null).post("/employments", "{}").statusCode());
    }

    @Test
    void oneRealmDoesNotSeeTheUsersOrEmploymentsOfAnother() throws Exception {
        String id = created(api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        assertEquals("employment.not_found", errorOf(404, otherRealm.api.get("/employments/" + id)));
        assertEquals("person.user_unknown", errorOf(400, otherRealm.api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void anEmploymentTypeInUseCannotBeDeleted() throws Exception {
        created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"full-time\"}"));

        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + fullTimeId)));
    }
}
