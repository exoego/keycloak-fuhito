package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A small clinic with one org unit.
 */
class StaffIT {

    private static TestRealm realm;
    private static Api api;
    private static String clinic;

    @BeforeAll
    static void createTheClinic() throws Exception {
        realm = TestRealm.create("staff");
        api = realm.api;
        clinic = Api.json(api.post("/org-units", "{\"name\": \"ホゲホゲ診療所\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("EMPLOYMENT_TYPE", "part-time", "非常勤");
    }

    @Test
    void addsAPersonWithOnlyAUserAUnitAndADate() throws Exception {
        String user = realm.user("receptionist");

        HttpResponse<String> response = api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2026-04-01\"}");

        assertEquals(201, response.statusCode(), response.body());
        JsonNode employment = Api.json(response).get("employment");
        JsonNode assignment = Api.json(response).get("assignment");
        assertEquals(user, employment.get("userId").asText());
        assertEquals("ACTIVE", employment.get("terms").get(0).get("status").asText());
        assertEquals(employment.get("id").asText(), assignment.get("employmentId").asText());
        assertEquals(clinic, assignment.get("orgUnitId").asText());
        assertTrue(assignment.get("periods").get(0).get("primary").asBoolean());
        assertFalse(assignment.has("validTo"));
        assertFalse(Api.json(response).has("userId"));
        assertEquals(employment, Api.json(api.get("/employments/" + employment.get("id").asText())));
        assertEquals(assignment, Api.json(api.get("/assignments/" + assignment.get("id").asText())));
    }

    @Test
    void addsAPartTimeNurseForOneYear() throws Exception {
        String user = realm.user("part-time-nurse");

        JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\", \"staffNumber\": \"N-001\", "
                + "\"employmentType\": \"part-time\", \"workPercent\": 50, \"occupation\": \"nurse\", "
                + "\"head\": true, \"primary\": false}"));

        assertEquals("2027-04-01", staff.get("employment").get("validTo").asText());
        assertEquals("N-001", staff.get("employment").get("staffNumber").asText());
        assertEquals("part-time", staff.get("employment").get("terms").get(0).get("employmentType").asText());
        assertEquals("50", staff.get("employment").get("terms").get(0).get("workPercent").asText());
        assertEquals("2027-04-01", staff.get("assignment").get("validTo").asText());
        JsonNode details = staff.get("assignment").get("periods").get(0);
        assertEquals("nurse", details.get("occupation").asText());
        assertTrue(details.get("head").asBoolean());
        assertFalse(details.get("primary").asBoolean());
    }

    @Test
    void aFailedAssignmentWritesNoEmployment() throws Exception {
        String user = realm.user("nobody");
        String count = "select count(*) from fuhito_employment e join fuhito_person p on p.id = e.person_id"
                + " where p.user_id = '" + user + "'";
        String start = "{\"userId\": \"" + user + "\", \"validFrom\": \"2026-04-01\"";

        assertEquals(400, api.post("/staff", start + ", \"orgUnitId\": \"no-such-unit\"}").statusCode());
        assertEquals(400, api.post("/staff", start + ", \"orgUnitId\": \"" + clinic + "\", \"occupation\": \"nope\"}").statusCode());
        assertEquals(400, api.post("/staff", start + "}").statusCode());
        // The clinic starts on 2026-04-01, so an earlier assignment is rejected after the employment was started.
        assertEquals(409, api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2026-03-01\"}").statusCode());

        assertEquals("0", KeycloakEnvironment.sql(count));
        assertEquals(401, new Api(realm.name, null).post("/staff", "{}").statusCode());
    }

    @Test
    void theFirstAssignmentNeedNotBeThePrimaryOne() throws Exception {
        String user = realm.user("helper");

        HttpResponse<String> response = api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2026-04-01\", \"primary\": false}");

        assertEquals(201, response.statusCode(), response.body());
        assertFalse(Api.json(response).get("assignment").get("periods").get(0).get("primary").asBoolean());
        HttpResponse<String> said = api.post("/staff", "{\"userId\": \"" + realm.user("main-helper")
                + "\", \"orgUnitId\": \"" + clinic + "\", \"validFrom\": \"2026-04-01\", \"primary\": true}");
        assertTrue(Api.json(said).get("assignment").get("periods").get(0).get("primary").asBoolean());
    }
}
