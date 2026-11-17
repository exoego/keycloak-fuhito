package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        realm = TestRealm.withEmployer("reporting");
        otherRealm = TestRealm.withEmployer("reporting");
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
    void aManagerWhoLeadsTwoUnitsOneBelowTheOtherHasEachReportOnce() throws Exception {
        String department = unit("総務部", null);
        String section = unit("庶務課", department);
        String office = unit("文書係", section);
        String both = realm.user("leads-both");
        String clerk = realm.user("clerk");
        String filer = realm.user("filer");
        JsonNode head = Api.json(api.post("/staff", "{\"userId\": \"" + both + "\", \"orgUnitId\": \"" + department
                + "\", \"validFrom\": \"2026-04-01\", \"head\": true}"));
        id(api.post("/assignments", "{\"employmentId\": \"" + head.get("employment").get("id").asText()
                + "\", \"orgUnitId\": \"" + section + "\", \"validFrom\": \"2026-04-01\", \"head\": true}"));
        staff(clerk, section, false);
        staff(filer, office, false);

        assertEquals(List.of(clerk, filer).stream().sorted().toList(),
                who("/users/" + both + "/reports?asOf=2026-04-01"));
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
        assertEquals("2027-10-01", Api.json(api.putWithVersion("/reporting-lines/" + dotted + "/end", "{\"validTo\": \"2027-10-01\"}"))
                .get("validTo").asText());
        assertEquals(204, api.deleteWithVersion("/reporting-lines/" + dotted).statusCode());
        assertEquals("reporting_line.not_found", errorOf(404, api.get("/reporting-lines/" + dotted)));

        // Ending the assignment ends its line.
        assertEquals(200, api.putWithVersion("/assignments/" + nurseAAssignment + "/end", "{\"validTo\": \"2030-04-01\"}").statusCode());
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
        assertEquals("user.not_found", errorOf(404, otherRealm.api.get("/users/" + nurseB + "/reports/count")));
        // The other realm knows neither the manager nor the assignment. The manager is looked up first.
        assertEquals("person.user_unknown", errorOf(400, otherRealm.api.post("/reporting-lines", line + director + "\"}")));
        assertEquals(401, new Api(realm.name, null).post("/reporting-lines", "{}").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/users/" + nurseB + "/managers").statusCode());
    }

    @Test
    void removingAManagerOrAReportEndsTheirLinesAndKeepsThem() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("reporting-removal");
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
        Api other = TestRealm.withEmployer("reporting-other").api;

        assertEquals(404, other.get("/reporting-lines/" + line).statusCode());
        assertEquals(404, other.deleteWithVersion("/reporting-lines/" + line).statusCode());
        assertEquals(200, api.get("/reporting-lines/" + line).statusCode());
        assertEquals(204, api.deleteWithVersion("/reporting-lines/" + line).statusCode());
    }

    @Test
    void anActingHeadLeadsTheUnitWhileItHolds() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("reporting-acting");
        Api scratchApi = scratch.api;
        assertEquals(201, scratchApi.post("/ref-items", "{\"kind\": \"ASSIGNMENT_KIND\", \"code\": \"acting\", "
                + "\"name\": \"事務取扱\", \"actingHead\": true}").statusCode());
        String hospital = id(scratchApi.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2026-04-01\"}"));
        String nursing = id(scratchApi.post("/org-units", "{\"name\": \"看護部\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2026-04-01\"}"));
        Map<String, String> users = new LinkedHashMap<>();
        for (String[] person : new String[][] {{"director", hospital, "true"}, {"head-nurse", nursing, "true"},
                {"nurse", nursing, "false"}}) {
            users.put(person[0], scratch.user(person[0]));
            HttpResponse<String> response = scratchApi.post("/staff", "{\"userId\": \"" + users.get(person[0])
                    + "\", \"orgUnitId\": \"" + person[1] + "\", \"validFrom\": \"2026-04-01\", \"head\": "
                    + person[2] + "}");
            assertEquals(201, response.statusCode(), response.body());
        }
        users.put("acting", scratch.user("acting"));
        String acting = id(scratchApi.post("/employments", "{\"userId\": \"" + users.get("acting")
                + "\", \"validFrom\": \"2026-04-01\"}"));
        id(scratchApi.post("/assignments", "{\"employmentId\": \"" + acting + "\", \"orgUnitId\": \"" + nursing
                + "\", \"validFrom\": \"2026-06-01\", \"kind\": \"acting\", \"head\": true}"));
        Map<String, String> names = new LinkedHashMap<>();
        users.forEach((name, userId) -> names.put(userId, name));
        java.util.function.BiFunction<String, String, List<String>> read = (path, date) -> {
            try {
                return Api.json(scratchApi.get(path + "?asOf=" + date)).findValuesAsText("userId").stream()
                        .map(names::get).sorted().toList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        // Before the acting head starts, the head nurse leads the department.
        assertEquals(List.of("head-nurse"), read.apply("/users/" + users.get("nurse") + "/managers", "2026-05-01"));
        assertEquals(List.of("nurse"), read.apply("/users/" + users.get("head-nurse") + "/reports", "2026-05-01"));

        // The acting head leads the department, though the employment of the head nurse stays active. Both heads
        // report to the director.
        assertEquals(List.of("acting"), read.apply("/users/" + users.get("nurse") + "/managers", "2026-07-01"));
        assertEquals(List.of("nurse"), read.apply("/users/" + users.get("acting") + "/reports", "2026-07-01"));
        assertEquals(List.of(), read.apply("/users/" + users.get("head-nurse") + "/reports", "2026-07-01"));
        assertEquals(List.of("director"), read.apply("/users/" + users.get("head-nurse") + "/managers",
                "2026-07-01"));
        assertEquals(List.of("acting", "head-nurse"), read.apply("/users/" + users.get("director") + "/reports",
                "2026-07-01"));
    }

    @Test
    void theDatabaseFindsTheReportsDownToTheNextHeadAndPagesThem() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("reporting-down");
        Api scratchApi = scratch.api;
        Map<String, String> units = new LinkedHashMap<>();
        for (String[] unit : new String[][] {{"病院", null}, {"看護部", "病院"}, {"病棟", "看護部"}, {"事務部", "病院"},
                {"受付", "事務部"}}) {
            String parent = unit[1] == null ? "" : ", \"parentId\": \"" + units.get(unit[1]) + "\"";
            units.put(unit[0], id(scratchApi.post("/org-units",
                    "{\"name\": \"" + unit[0] + "\"" + parent + ", \"validFrom\": \"2026-04-01\"}")));
        }
        Map<String, String> users = new LinkedHashMap<>();
        Map<String, String> assignments = new LinkedHashMap<>();
        // The hospital and the nursing department have heads. The ward, the office, and the desk have none.
        for (String[] person : new String[][] {{"director", "病院", "true"}, {"secretary", "病院", "false"},
                {"head-nurse", "看護部", "true"}, {"co-head", "看護部", "true"}, {"aide", "看護部", "false"},
                {"nurse-a", "病棟", "false"}, {"nurse-b", "病棟", "false"}, {"clerk", "事務部", "false"},
                {"temp", "受付", "false"}}) {
            users.put(person[0], scratch.user(person[0]));
            HttpResponse<String> response = scratchApi.post("/staff", "{\"userId\": \"" + users.get(person[0])
                    + "\", \"orgUnitId\": \"" + units.get(person[1]) + "\", \"validFrom\": \"2026-04-01\", \"head\": "
                    + person[2] + "}");
            assertEquals(201, response.statusCode(), response.body());
            assignments.put(person[0], Api.json(response).get("assignment").get("id").asText());
        }
        Map<String, String> names = new LinkedHashMap<>();
        users.forEach((name, userId) -> names.put(userId, name));
        String on = "asOf=2026-04-01";

        // The director gets the own unit, the heads of the nursing department, and the units with no head.
        JsonNode ofDirector = Api.json(scratchApi.get("/users/" + users.get("director") + "/reports?" + on));
        assertEquals(List.of("clerk", "co-head", "head-nurse", "secretary", "temp"),
                ofDirector.findValuesAsText("userId").stream().map(names::get).sorted().toList());
        // The nearest come first.
        assertEquals("secretary", names.get(ofDirector.get(0).get("userId").asText()));
        assertEquals("temp", names.get(ofDirector.get(4).get("userId").asText()));
        assertEquals(List.of("true"), ofDirector.findValuesAsText("derived").stream().distinct().toList());

        // A head gets the members of the own unit and the ward below, and not the other head.
        String ofHeadNurse = "/users/" + users.get("head-nurse") + "/reports?" + on;
        assertEquals(List.of("aide", "nurse-a", "nurse-b"), Api.json(scratchApi.get(ofHeadNurse))
                .findValuesAsText("userId").stream().map(names::get).sorted().toList());
        assertEquals(List.of(), Api.json(scratchApi.get("/users/" + users.get("aide") + "/reports?" + on))
                .findValuesAsText("userId"));

        // A stored solid line takes nurse A away from the heads. The stored line comes first for the director.
        assertEquals(201, scratchApi.post("/reporting-lines", "{\"assignmentId\": \"" + assignments.get("nurse-a")
                + "\", \"managerUserId\": \"" + users.get("director") + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(List.of("aide", "nurse-b"), Api.json(scratchApi.get(ofHeadNurse))
                .findValuesAsText("userId").stream().map(names::get).sorted().toList());
        JsonNode withStored = Api.json(scratchApi.get("/users/" + users.get("director") + "/reports?" + on));
        assertEquals(6, withStored.size());
        assertEquals("nurse-a", names.get(withStored.get(0).get("userId").asText()));
        assertEquals("false", withStored.get(0).get("derived").asText());

        // The pages follow the same order.
        JsonNode page = Api.json(scratchApi.get("/users/" + users.get("director") + "/reports?first=1&max=2&" + on));
        assertEquals(List.of(withStored.get(1).get("assignmentId").asText(), withStored.get(2).get("assignmentId").asText()),
                page.findValuesAsText("assignmentId"));
        String person = Api.json(scratchApi.get("/people?userId=" + users.get("director"))).get(0).get("id").asText();
        assertEquals(1, Api.json(scratchApi.get("/people/" + person + "/reports?max=1&" + on)).size());
        // The count reads both the stored lines and the derived ones.
        assertEquals(6, scratchApi.count("/users/" + users.get("director") + "/reports/count?" + on));
        assertEquals(6, scratchApi.count("/people/" + person + "/reports/count?" + on));
        assertEquals(2, scratchApi.count("/users/" + users.get("head-nurse") + "/reports/count?" + on));
        assertEquals(0, scratchApi.count("/users/" + users.get("aide") + "/reports/count?" + on));
    }
}
