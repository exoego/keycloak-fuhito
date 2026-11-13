package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One hospital with a nursing department and a ward, and a second hospital.
 * A physician works in both hospitals. A nurse goes on leave in October 2026. A clerk leaves and comes back.
 */
class MembershipIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String xHospital;
    private static String nursing;
    private static String ward;
    private static String yHospital;
    private static String physician;
    private static String nurse;
    private static String clerk;
    private static String idle;

    @BeforeAll
    static void createTheOrganization() throws Exception {
        realm = TestRealm.create("membership");
        otherRealm = TestRealm.create("membership");
        api = realm.api;
        realm.refItem("OCCUPATION", "physician", "医師");
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("JOB_TITLE", "director", "部長");
        realm.refItem("EMPLOYMENT_TYPE", "part-time", "非常勤");
        xHospital = unit("X病院", null);
        nursing = unit("看護部", xHospital);
        ward = unit("1病棟", nursing);
        yHospital = unit("Y病院", null);
        physician = realm.user("physician");
        nurse = realm.user("nurse");
        clerk = realm.user("clerk");
        idle = realm.user("idle");

        String physicianEmployment = staff(physician, xHospital, ", \"staffNumber\": \"D-1\", \"occupation\": \"physician\", \"jobTitle\": \"director\", \"head\": true");
        post("/assignments", "{\"employmentId\": \"" + physicianEmployment + "\", \"orgUnitId\": \"" + yHospital
                + "\", \"validFrom\": \"2026-04-01\", \"occupation\": \"physician\", \"allocationPercent\": 20}");
        String nurseEmployment = staff(nurse, ward, ", \"occupation\": \"nurse\", \"employmentType\": \"part-time\", \"workPercent\": 50");
        assertEquals(200, api.put("/employments/" + nurseEmployment + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"employmentType\": \"part-time\", \"status\": \"ON_LEAVE\"}").statusCode());
        String clerkEmployment = staff(clerk, nursing, "");
        assertEquals(200, api.put("/employments/" + clerkEmployment + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        post("/staff", "{\"userId\": \"" + clerk + "\", \"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2028-04-01\"}");
    }

    private static JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String unit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return post("/org-units", "{\"name\": \"" + name + "\"" + parent + ", \"validFrom\": \"2026-04-01\"}").get("id").asText();
    }

    private static String staff(String userId, String orgUnitId, String more) throws Exception {
        return post("/staff", "{\"userId\": \"" + userId + "\", \"orgUnitId\": \"" + orgUnitId
                + "\", \"validFrom\": \"2026-04-01\"" + more + "}").get("employment").get("id").asText();
    }

    private static List<String> users(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("userId");
    }

    @Test
    void listsTheMembersOfAUnitOnTwoDates() throws Exception {
        assertEquals(List.of(clerk), users(api.get("/org-units/" + nursing + "/members?asOf=2026-04-01")));
        assertEquals(List.of(), users(api.get("/org-units/" + nursing + "/members?asOf=2027-04-01")));
        assertEquals(List.of(nurse), users(api.get("/org-units/" + ward + "/members?asOf=2027-04-01")));
        assertEquals(List.of(nurse, clerk), users(api.get("/org-units/" + ward + "/members?asOf=2028-04-01")));
    }

    @Test
    void listsDescendantsInTreeOrderWithEveryField() throws Exception {
        HttpResponse<String> response = api.get("/org-units/" + xHospital + "/members?includeDescendants=true&asOf=2026-04-01");

        assertEquals(List.of(physician, clerk, nurse), users(response));
        JsonNode head = Api.json(response).get(0);
        assertEquals(xHospital, head.get("orgUnitId").asText());
        assertEquals("X病院", head.get("orgUnitName").asText());
        assertEquals("D-1", head.get("staffNumber").asText());
        assertEquals("physician", head.get("occupation").asText());
        assertEquals("director", head.get("jobTitle").asText());
        assertTrue(head.get("head").asBoolean());
        assertTrue(head.get("primary").asBoolean());
        assertEquals("ACTIVE", head.get("status").asText());
        assertEquals("2026-04-01", head.get("validFrom").asText());
        assertFalse(head.has("validTo"));
        assertFalse(head.has("orgUnitPath"));
        JsonNode partTime = Api.json(response).get(2);
        assertEquals("part-time", partTime.get("employmentType").asText());
        assertEquals("50", partTime.get("workPercent").asText());
        assertEquals("1病棟", partTime.get("orgUnitName").asText());
    }

    @Test
    void filtersAndPagesTheMembers() throws Exception {
        String all = "/org-units/" + xHospital + "/members?includeDescendants=true";

        assertEquals(List.of(nurse), users(api.get(all + "&asOf=2026-04-01&occupation=nurse")));
        assertEquals(List.of(physician), users(api.get(all + "&asOf=2026-04-01&jobTitle=director")));
        assertEquals(List.of(physician, clerk), users(api.get(all + "&asOf=2026-10-01&status=ACTIVE")));
        assertEquals(List.of(nurse), users(api.get(all + "&asOf=2026-10-01&status=ON_LEAVE")));
        assertEquals(List.of(physician, clerk, nurse), users(api.get(all + "&asOf=2026-10-01")));
        assertEquals(List.of(clerk), users(api.get(all + "&asOf=2026-04-01&first=1&max=1")));
        assertEquals(400, api.get(all + "&occupation=director").statusCode());
        assertEquals(400, api.get(all + "&status=GONE").statusCode());
        assertEquals(404, api.get("/org-units/" + xHospital + "/members?asOf=2026-03-31").statusCode());
        assertEquals(404, api.get("/org-units/nope/members").statusCode());
        assertEquals(404, otherRealm.api.get("/org-units/" + xHospital + "/members?asOf=2026-04-01").statusCode());
    }

    @Test
    void listsTheAssignmentsOfAUserInTwoHospitals() throws Exception {
        HttpResponse<String> response = api.get("/users/" + physician + "/assignments?asOf=2026-04-01&includePath=true");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode assignments = Api.json(response);
        assertEquals(2, assignments.size());
        assertEquals(xHospital, assignments.get(0).get("orgUnitId").asText());
        assertTrue(assignments.get(0).get("primary").asBoolean());
        assertEquals(yHospital, assignments.get(1).get("orgUnitId").asText());
        assertEquals("20", assignments.get(1).get("allocationPercent").asText());
        assertEquals(List.of("Y病院"), assignments.get(1).get("orgUnitPath").findValuesAsText("name"));

        JsonNode ofNurse = Api.json(api.get("/users/" + nurse + "/assignments?asOf=2026-10-01&includePath=true"));
        assertEquals(List.of("X病院", "看護部", "1病棟"), ofNurse.get(0).get("orgUnitPath").findValuesAsText("name"));
        assertEquals("ON_LEAVE", ofNurse.get(0).get("status").asText());
        assertEquals(0, Api.json(api.get("/users/" + clerk + "/assignments?asOf=2027-04-01")).size());
        assertEquals(0, Api.json(api.get("/users/" + idle + "/assignments")).size());
    }

    @Test
    void readsTheHistoryOfAUserWhoLeftAndCameBack() throws Exception {
        HttpResponse<String> response = api.get("/users/" + clerk + "/history");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode history = Api.json(response);
        assertEquals(clerk, history.get("userId").asText());
        assertEquals(2, history.get("employments").size());
        assertEquals("2027-04-01", history.get("employments").get(0).get("validTo").asText());
        assertEquals("2028-04-01", history.get("employments").get(1).get("validFrom").asText());
        assertEquals(List.of(nursing, ward), history.get("assignments").findValuesAsText("orgUnitId"));
        assertEquals("2027-04-01", history.get("assignments").get(0).get("validTo").asText());
        assertEquals(0, Api.json(api.get("/users/" + idle + "/history")).get("employments").size());
    }

    @Test
    void rejectsAnUnknownUserAndAMissingToken() throws Exception {
        for (String sub : List.of("assignments", "history")) {
            HttpResponse<String> unknown = api.get("/users/no-such-user/" + sub);
            assertEquals(404, unknown.statusCode());
            assertEquals("user.not_found", Api.json(unknown).get("error").asText());
            assertEquals(404, otherRealm.api.get("/users/" + physician + "/" + sub).statusCode());
            assertEquals(401, new Api(realm.name, null).get("/users/" + physician + "/" + sub).statusCode());
        }
        assertEquals(400, api.get("/users/" + physician + "/assignments?asOf=nope").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/org-units/" + ward + "/members").statusCode());
    }
}
