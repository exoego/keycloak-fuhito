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
    void rejectsBadRequests() throws Exception {
        String user = "\"userId\": \"" + nurse + "\"";
        assertEquals("employment.user_unknown", errorOf(400, api.post("/employments",
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
        assertEquals("employment.user_unknown", errorOf(400, otherRealm.api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void anEmploymentTypeInUseCannotBeDeleted() throws Exception {
        created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"full-time\"}"));

        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + fullTimeId)));
    }
}
