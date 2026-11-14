package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hospital with a director, a nursing department with a head nurse, and a ward with two nurses and no head.
 */
class ReportingLinesIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String director;
    private static String headNurse;
    private static String nurseA;
    private static String nurseB;
    private static String projectLead;
    private static String nurseAAssignment;
    private static String nurseBAssignment;

    @BeforeAll
    static void createTheHospital() throws Exception {
        realm = TestRealm.create("reporting");
        otherRealm = TestRealm.create("reporting");
        api = realm.api;
        String hospital = unit("X病院", null);
        String nursing = unit("看護部", hospital);
        String ward = unit("1病棟", nursing);
        director = realm.user("director");
        headNurse = realm.user("head-nurse");
        nurseA = realm.user("nurse-a");
        nurseB = realm.user("nurse-b");
        projectLead = realm.user("project-lead");
        staff(director, hospital, true);
        staff(headNurse, nursing, true);
        nurseAAssignment = staff(nurseA, ward, false);
        nurseBAssignment = staff(nurseB, ward, false);
    }

    private static String unit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return id(api.post("/org-units", "{\"name\": \"" + name + "\"" + parent + ", \"validFrom\": \"2026-04-01\"}"));
    }

    private static String staff(String userId, String orgUnitId, boolean head) throws Exception {
        HttpResponse<String> response = api.post("/staff", "{\"userId\": \"" + userId + "\", \"orgUnitId\": \""
                + orgUnitId + "\", \"validFrom\": \"2026-04-01\", \"head\": " + head + "}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("assignment").get("id").asText();
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static List<String> who(String path) throws Exception {
        HttpResponse<String> response = api.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("userId").stream().sorted().toList();
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void derivesManagersAndReportsFromTheHeadsOfTheTree() throws Exception {
        assertEquals(List.of(headNurse), who("/users/" + nurseB + "/managers?asOf=2026-04-01"));
        assertEquals(List.of(director), who("/users/" + headNurse + "/managers?asOf=2026-04-01"));
        assertEquals(List.of(), who("/users/" + director + "/managers?asOf=2026-04-01"));
        assertEquals(List.of(headNurse), who("/users/" + director + "/reports?asOf=2026-04-01"));
        assertTrue(who("/users/" + headNurse + "/reports?asOf=2026-04-01").contains(nurseB));

        JsonNode manager = Api.json(api.get("/users/" + nurseB + "/managers?asOf=2026-04-01")).get(0);
        assertEquals("SOLID", manager.get("kind").asText());
        assertTrue(manager.get("derived").asBoolean());
        assertFalse(manager.has("lineId"));
        assertEquals(nurseBAssignment, manager.get("assignmentId").asText());
    }

    @Test
    void storedLinesChangeTheAnswerFromTheirDate() throws Exception {
        HttpResponse<String> created = api.post("/reporting-lines", "{\"assignmentId\": \"" + nurseAAssignment
                + "\", \"managerUserId\": \"" + director + "\", \"validFrom\": \"2027-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode solid = Api.json(created);
        assertEquals("SOLID", solid.get("kind").asText());
        assertFalse(solid.has("validTo"));
        assertEquals(solid, Api.json(api.get("/reporting-lines/" + solid.get("id").asText())));
        String dotted = id(api.post("/reporting-lines", "{\"assignmentId\": \"" + nurseAAssignment
                + "\", \"managerUserId\": \"" + projectLead + "\", \"kind\": \"DOTTED\", \"validFrom\": \"2027-04-01\", "
                + "\"validTo\": \"2028-04-01\"}"));

        assertEquals(List.of(headNurse), who("/users/" + nurseA + "/managers?asOf=2027-03-31"));
        assertEquals(List.of(director, projectLead).stream().sorted().toList(),
                who("/users/" + nurseA + "/managers?asOf=2027-04-01"));
        assertEquals(List.of(director), who("/users/" + nurseA + "/managers?asOf=2028-04-01"));
        assertEquals(List.of(nurseA), who("/users/" + projectLead + "/reports?asOf=2027-04-01"));
        assertFalse(who("/users/" + headNurse + "/reports?asOf=2027-04-01").contains(nurseA));
        assertTrue(who("/users/" + director + "/reports?asOf=2027-04-01").contains(nurseA));

        assertEquals("reporting_line.solid_exists", errorOf(409, api.post("/reporting-lines", "{\"assignmentId\": \""
                + nurseAAssignment + "\", \"managerUserId\": \"" + projectLead + "\", \"validFrom\": \"2029-04-01\"}")));
        assertEquals("2027-10-01", Api.json(api.put("/reporting-lines/" + dotted + "/end", "{\"validTo\": \"2027-10-01\"}"))
                .get("validTo").asText());
        assertEquals(204, api.delete("/reporting-lines/" + dotted).statusCode());
        assertEquals("reporting_line.not_found", errorOf(404, api.get("/reporting-lines/" + dotted)));

        // Ending the assignment ends its line.
        assertEquals(200, api.put("/assignments/" + nurseAAssignment + "/end", "{\"validTo\": \"2030-04-01\"}").statusCode());
        assertEquals("2030-04-01", Api.json(api.get("/reporting-lines/" + solid.get("id").asText())).get("validTo").asText());
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String line = "{\"assignmentId\": \"" + nurseBAssignment + "\", \"validFrom\": \"2026-04-01\", \"managerUserId\": \"";
        assertEquals("person.user_unknown", errorOf(400, api.post("/reporting-lines", line + "nobody\"}")));
        assertEquals("reporting_line.self", errorOf(400, api.post("/reporting-lines", line + nurseB + "\"}")));
        assertEquals("reporting_line.assignment_unknown", errorOf(400, api.post("/reporting-lines",
                "{\"assignmentId\": \"nope\", \"managerUserId\": \"" + director + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("reporting_line.outside_assignment", errorOf(409, api.post("/reporting-lines",
                "{\"assignmentId\": \"" + nurseBAssignment + "\", \"managerUserId\": \"" + director + "\", \"validFrom\": \"2026-03-01\"}")));
        assertEquals("invalid.choice", errorOf(400, api.post("/reporting-lines", line + director + "\", \"kind\": \"THICK\"}")));
        assertEquals("user.not_found", errorOf(404, api.get("/users/nobody/managers")));
        assertEquals("user.not_found", errorOf(404, otherRealm.api.get("/users/" + nurseB + "/reports")));
        // The other realm knows neither the manager nor the assignment. The manager is looked up first.
        assertEquals("person.user_unknown", errorOf(400, otherRealm.api.post("/reporting-lines", line + director + "\"}")));
        assertEquals(401, new Api(realm.name, null).post("/reporting-lines", "{}").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/users/" + nurseB + "/managers").statusCode());
    }

    @Test
    void removingAManagerOrAReportEndsTheirLinesAndKeepsThem() throws Exception {
        TestRealm scratch = TestRealm.create("reporting-removal");
        Api scratchApi = scratch.api;
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String unit = Api.json(scratchApi.post("/org-units", "{\"name\": \"課\", \"validFrom\": \"2025-04-01\"}")).get("id").asText();
        String manager = scratch.user("manager");
        String keptManager = scratch.user("kept-manager");
        String report = scratch.user("report");
        String kept = scratch.user("kept");
        String start = "{\"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2025-04-01\", \"userId\": \"";
        String reportAssignment = Api.json(scratchApi.post("/staff", start + report + "\"}")).get("assignment").get("id").asText();
        String keptAssignment = Api.json(scratchApi.post("/staff", start + kept + "\"}")).get("assignment").get("id").asText();
        String line = "{\"validFrom\": \"2025-04-01\", \"kind\": \"DOTTED\", \"assignmentId\": \"";
        String ofReport = id(scratchApi.post("/reporting-lines", line + reportAssignment + "\", \"managerUserId\": \"" + keptManager + "\"}"));
        String toManager = id(scratchApi.post("/reporting-lines", line + keptAssignment + "\", \"managerUserId\": \"" + manager + "\"}"));
        String untouched = id(scratchApi.post("/reporting-lines", line + keptAssignment + "\", \"managerUserId\": \"" + keptManager + "\"}"));
        String count = "select count(*) from fuhito_reporting_line where realm_id = '" + scratch.id() + "'";

        scratch.removeUser(manager);
        scratch.removeUser(report);

        // Every line is still stored. The two that involved a removed user end today.
        assertEquals("3", KeycloakEnvironment.sql(count));
        assertEquals(today, Api.json(scratchApi.get("/reporting-lines/" + toManager)).get("validTo").asText());
        assertEquals(today, Api.json(scratchApi.get("/reporting-lines/" + ofReport)).get("validTo").asText());
        assertFalse(Api.json(scratchApi.get("/reporting-lines/" + untouched)).has("validTo"));
        String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        assertEquals(List.of(kept), Api.json(scratchApi.get("/users/" + keptManager + "/reports?asOf=" + tomorrow)).findValuesAsText("userId"));
        assertEquals(List.of(keptManager), Api.json(scratchApi.get("/users/" + kept + "/managers?asOf=" + tomorrow)).findValuesAsText("userId"));
    }

    @Test
    void aLineOfAnotherRealmIsNotFound() throws Exception {
        String line = id(api.post("/reporting-lines", "{\"assignmentId\": \"" + nurseBAssignment
                + "\", \"managerUserId\": \"" + projectLead + "\", \"kind\": \"DOTTED\", \"validFrom\": \"2029-04-01\"}"));
        Api other = TestRealm.create("reporting-other").api;

        assertEquals(404, other.get("/reporting-lines/" + line).statusCode());
        assertEquals(404, other.delete("/reporting-lines/" + line).statusCode());
        assertEquals(200, api.get("/reporting-lines/" + line).statusCode());
        assertEquals(204, api.delete("/reporting-lines/" + line).statusCode());
    }
}
