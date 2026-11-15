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
        realm = TestRealm.withEmployer("staff");
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
    void promotesAPersonInOneWrite() throws Exception {
        realm.refItem("GRADE", "g1", "1級");
        realm.refItem("GRADE", "g2", "2級");
        realm.refItem("JOB_TITLE", "chief", "主任");
        String user = realm.user("promoted-nurse");
        JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"part-time\", \"workPercent\": 50, "
                + "\"grade\": \"g1\", \"occupation\": \"nurse\"}"));
        String employmentId = staff.get("employment").get("id").asText();
        String assignmentId = staff.get("assignment").get("id").asText();

        HttpResponse<String> response = api.post("/employments/" + employmentId + "/promotion",
                "{\"validFrom\": \"2026-10-01\", \"grade\": \"g2\", \"jobTitle\": \"chief\", \"head\": true}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode terms = Api.json(response).get("employment").get("terms");
        assertEquals(2, terms.size());
        assertEquals("g2", terms.get(1).get("grade").asText());
        assertEquals("2026-10-01", terms.get(1).get("validFrom").asText());
        // What the request did not name stays.
        assertEquals("part-time", terms.get(1).get("employmentType").asText());
        assertEquals(50, terms.get(1).get("workPercent").asInt());
        JsonNode periods = Api.json(response).get("assignment").get("periods");
        assertEquals(assignmentId, Api.json(response).get("assignment").get("id").asText());
        assertEquals("chief", periods.get(1).get("jobTitle").asText());
        assertEquals("nurse", periods.get(1).get("occupation").asText());
        assertTrue(periods.get(1).get("head").asBoolean());
        assertTrue(periods.get(1).get("primary").asBoolean());
        assertFalse(periods.get(0).has("jobTitle"));
        assertEquals(Api.json(response).get("employment"), Api.json(api.get("/employments/" + employmentId)));
        assertEquals("fuhito/employments/" + employmentId + "/promotion", Api.json(api.get(
                "/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + employmentId)).get(1).get("resourcePath")
                .asText());

        // A bad job title undoes the good grade of the same request.
        HttpResponse<String> bad = api.post("/employments/" + employmentId + "/promotion",
                "{\"validFrom\": \"2027-04-01\", \"grade\": \"g1\", \"jobTitle\": \"nope\"}");
        assertEquals(400, bad.statusCode(), bad.body());
        assertEquals("ref_item.unknown", Api.json(bad).get("error").asText());
        assertEquals(2, Api.json(api.get("/employments/" + employmentId)).get("terms").size());
        HttpResponse<String> empty = api.post("/employments/" + employmentId + "/promotion",
                "{\"validFrom\": \"2027-04-01\"}");
        assertEquals(400, empty.statusCode(), empty.body());
        assertEquals("employment.promotion_empty", Api.json(empty).get("error").asText());
    }

    @Test
    void aLeaveAndAReturnChangeOnlyTheStatus() throws Exception {
        String user = realm.user("nurse-on-leave");
        String employmentId = Api.json(api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"part-time\", \"workPercent\": 50}"))
                .get("employment").get("id").asText();

        HttpResponse<String> leave = api.post("/employments/" + employmentId + "/leave",
                "{\"validFrom\": \"2026-10-01\", \"validTo\": \"2027-04-01\"}");

        assertEquals(200, leave.statusCode(), leave.body());
        JsonNode terms = Api.json(leave).get("terms");
        assertEquals(java.util.List.of("ACTIVE", "ON_LEAVE", "ACTIVE"), terms.findValuesAsText("status"));
        assertEquals(java.util.List.of("2026-04-01", "2026-10-01", "2027-04-01"), terms.findValuesAsText("validFrom"));
        assertEquals(java.util.List.of("part-time", "part-time", "part-time"), terms.findValuesAsText("employmentType"));
        assertEquals(50, terms.get(1).get("workPercent").asInt());

        // The person comes back early.
        HttpResponse<String> back = api.post("/employments/" + employmentId + "/return",
                "{\"validFrom\": \"2027-01-01\"}");
        assertEquals(200, back.statusCode(), back.body());
        assertEquals(java.util.List.of("ACTIVE", "ON_LEAVE", "ACTIVE", "ACTIVE"),
                Api.json(back).get("terms").findValuesAsText("status"));
        assertEquals(Api.json(back), Api.json(api.get("/employments/" + employmentId)));
        assertEquals(java.util.List.of("fuhito/employments/" + employmentId, "fuhito/employments/" + employmentId
                + "/leave", "fuhito/employments/" + employmentId + "/return"), Api.json(api.get(
                "/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + employmentId))
                .findValuesAsText("resourcePath"));

        HttpResponse<String> notOnLeave = api.post("/employments/" + employmentId + "/return",
                "{\"validFrom\": \"2027-02-01\"}");
        assertEquals(409, notOnLeave.statusCode(), notOnLeave.body());
        assertEquals("employment.not_on_leave", Api.json(notOnLeave).get("error").asText());
        HttpResponse<String> stored = api.post("/employments/" + employmentId + "/leave",
                "{\"validFrom\": \"2026-05-01\"}");
        assertEquals(409, stored.statusCode(), stored.body());
        assertEquals("employment.terms_change_stored", Api.json(stored).get("error").asText());
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
