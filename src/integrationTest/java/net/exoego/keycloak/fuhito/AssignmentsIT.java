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
 * A physician who works in two hospitals of one corporation and sits on a committee.
 */
class AssignmentsIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String physician;
    private static String employment;
    private static String xMedicine;
    private static String ySurgery;
    private static String committee;
    private static String directorId;

    @BeforeAll
    static void createTheOrganization() throws Exception {
        realm = TestRealm.create("assignments");
        otherRealm = TestRealm.create("assignments");
        api = realm.api;
        physician = realm.user("physician");
        employment = id(api.post("/employments", "{\"userId\": \"" + physician + "\", \"validFrom\": \"2026-04-01\"}"));
        String xHospital = unit("X病院", null);
        String yHospital = unit("Y病院", null);
        xMedicine = unit("内科", xHospital);
        ySurgery = unit("外科", yHospital);
        committee = unit("医療安全委員会", xHospital);
        realm.refItem("JOB_TITLE", "staff", "医員");
        directorId = realm.refItem("JOB_TITLE", "director", "部長");
        realm.refItem("JOB_TITLE", "chair", "委員長");
        realm.refItem("OCCUPATION", "physician", "医師");
        realm.refItem("ASSIGNMENT_KIND", "regular", "通常");
    }

    private static String unit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return id(api.post("/org-units", "{\"name\": \"" + name + "\"" + parent + ", \"validFrom\": \"2026-04-01\"}"));
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String assign(String orgUnitId, String more) {
        return "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + orgUnitId
                + "\", \"validFrom\": \"2026-04-01\"" + more + "}";
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void aPhysicianHoldsThreeAssignmentsAtOnceInTwoHospitals() throws Exception {
        HttpResponse<String> main = api.post("/assignments", assign(xMedicine, ", \"jobTitle\": \"staff\", "
                + "\"occupation\": \"physician\", \"kind\": \"regular\", \"primary\": true, \"allocationPercent\": 60, "
                + "\"displayOrder\": 2, \"attributes\": {\"外来\": \"月水金\"}, \"externalId\": \"A-1\", \"source\": \"hr\""));
        assertEquals(201, main.statusCode(), main.body());
        JsonNode first = Api.json(main);
        String second = id(api.post("/assignments", assign(ySurgery, ", \"occupation\": \"physician\", \"allocationPercent\": 30")));
        String third = id(api.post("/assignments", assign(committee, ", \"jobTitle\": \"chair\", \"head\": true, \"allocationPercent\": 10")));

        assertEquals(employment, first.get("employmentId").asText());
        assertEquals(physician, first.get("userId").asText());
        assertEquals(xMedicine, first.get("orgUnitId").asText());
        assertEquals("2026-04-01", first.get("validFrom").asText());
        assertFalse(first.has("validTo"));
        assertEquals("A-1", first.get("externalId").asText());
        JsonNode details = first.get("periods").get(0);
        assertEquals("staff", details.get("jobTitle").asText());
        assertEquals("physician", details.get("occupation").asText());
        assertEquals("regular", details.get("kind").asText());
        assertTrue(details.get("primary").asBoolean());
        assertFalse(details.get("head").asBoolean());
        assertEquals("60", details.get("allocationPercent").asText());
        assertEquals(2, details.get("displayOrder").asInt());
        assertEquals("月水金", details.get("attributes").get("外来").asText());
        assertEquals(first, Api.json(api.get("/assignments/" + first.get("id").asText())));

        JsonNode chair = Api.json(api.get("/assignments/" + third)).get("periods").get(0);
        assertTrue(chair.get("head").asBoolean());
        assertFalse(chair.get("primary").asBoolean());
        assertEquals(ySurgery, Api.json(api.get("/assignments/" + second)).get("orgUnitId").asText());
    }

    @Test
    void changesAJobTitleOnADateAndEndsTheAssignment() throws Exception {
        String id = id(api.post("/assignments", assign(xMedicine, ", \"jobTitle\": \"staff\"")));

        HttpResponse<String> response = api.put("/assignments/" + id + "/details",
                "{\"validFrom\": \"2027-04-01\", \"jobTitle\": \"director\", \"head\": true}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode changed = Api.json(response);
        assertEquals(id, changed.get("id").asText());
        assertEquals(List.of("staff", "director"), changed.get("periods").findValuesAsText("jobTitle"));
        assertEquals("2027-04-01", changed.get("periods").get(0).get("validTo").asText());
        assertTrue(changed.get("periods").get(1).get("head").asBoolean());

        JsonNode ended = Api.json(api.put("/assignments/" + id + "/end", "{\"validTo\": \"2028-04-01\"}"));
        assertEquals("2028-04-01", ended.get("validTo").asText());
        assertEquals("2028-04-01", ended.get("periods").get(1).get("validTo").asText());
        assertEquals(ended, Api.json(api.get("/assignments/" + id)));
        // The end can move later, and it can be removed.
        assertEquals("2029-04-01", Api.json(api.put("/assignments/" + id + "/end", "{\"validTo\": \"2029-04-01\"}")).get("validTo").asText());
        HttpResponse<String> reopened = api.delete("/assignments/" + id + "/end");
        assertEquals(200, reopened.statusCode(), reopened.body());
        assertFalse(Api.json(reopened).has("validTo"));
        assertEquals("assignment.later_changes_exist", errorOf(409, api.put("/assignments/" + id + "/end", "{\"validTo\": \"2027-01-01\"}")));
        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + directorId)));
    }

    @Test
    void anEmploymentHasOnePrimaryAssignmentAtATime() throws Exception {
        String nurse = realm.user("nurse-" + System.nanoTime());
        String nurseEmployment = id(api.post("/employments", "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}"));
        String start = "{\"employmentId\": \"" + nurseEmployment + "\", \"primary\": true, \"orgUnitId\": \"";
        String old = id(api.post("/assignments", start + xMedicine + "\", \"validFrom\": \"2026-04-01\"}"));

        HttpResponse<String> rejected = api.post("/assignments", start + ySurgery + "\", \"validFrom\": \"2027-04-01\"}");
        assertEquals("assignment.primary_exists", errorOf(409, rejected));
        assertEquals(old, Api.json(rejected).get("params").get("assignmentId").asText());

        String now = id(api.post("/assignments", start + ySurgery + "\", \"validFrom\": \"2027-04-01\", \"switchPrimary\": true}"));

        JsonNode oldPeriods = Api.json(api.get("/assignments/" + old)).get("periods");
        assertEquals(List.of("true", "false"), oldPeriods.findValuesAsText("primary"));
        assertEquals("2027-04-01", oldPeriods.get(1).get("validFrom").asText());
        assertFalse(oldPeriods.get(0).has("switchPrimary"));
        assertEquals(List.of("true"), Api.json(api.get("/assignments/" + now)).get("periods").findValuesAsText("primary"));

        // The old assignment takes the flag back from 2028 with a change of its details.
        assertEquals("assignment.primary_exists", errorOf(409, api.put("/assignments/" + old + "/details",
                "{\"validFrom\": \"2028-04-01\", \"primary\": true}")));
        assertEquals(200, api.put("/assignments/" + old + "/details",
                "{\"validFrom\": \"2028-04-01\", \"primary\": true, \"switchPrimary\": true}").statusCode());
        assertEquals(List.of("true", "false", "true"),
                Api.json(api.get("/assignments/" + old)).get("periods").findValuesAsText("primary"));
        assertEquals(List.of("true", "false"),
                Api.json(api.get("/assignments/" + now)).get("periods").findValuesAsText("primary"));
    }

    // This row goes around the application, as a bug in it would.
    @Test
    void theDatabaseRejectsTwoPrimaryAssignmentsOnTheSameDates() throws Exception {
        String nurse = realm.user("nurse-" + System.nanoTime());
        String nurseEmployment = id(api.post("/employments", "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}"));
        id(api.post("/assignments", "{\"employmentId\": \"" + nurseEmployment + "\", \"primary\": true, \"orgUnitId\": \""
                + xMedicine + "\", \"validFrom\": \"2026-04-01\"}"));
        String insert = "insert into fuhito_assignment (id, realm_id, assignment_id, employment_id, org_unit_id,"
                + " valid_from, is_primary, attributes) values ('%s', '" + realm.id() + "', '%s', '" + nurseEmployment
                + "', '" + ySurgery + "', '2027-04-01', %s, '{}')";

        assertTrue(KeycloakEnvironment.sqlError(String.format(insert, "sql-row-1", "sql-assignment-1", "true"))
                .contains("ex_fuhito_assignment_primary"));
        KeycloakEnvironment.sql(String.format(insert, "sql-row-2", "sql-assignment-2", "false"));
    }

    @Test
    void anOrgUnitWithAssignmentsCannotEndBeforeThem() throws Exception {
        String unit = unit("統合予定の課", null);
        String first = id(api.post("/assignments", assign(unit, "")));
        id(api.post("/assignments", assign(unit, ", \"validTo\": \"2027-04-01\"")));

        HttpResponse<String> rejected = api.put("/org-units/" + unit + "/end", "{\"validTo\": \"2027-01-01\"}");
        assertEquals("org_unit.has_assignments", errorOf(409, rejected));
        assertEquals("2", Api.json(rejected).get("params").get("count").asText());
        assertEquals("1", Api.json(api.put("/org-units/" + unit + "/end", "{\"validTo\": \"2027-04-01\"}"))
                .get("params").get("count").asText());

        // Once the last assignment ends on the date, the unit can end on it.
        assertEquals(200, api.put("/assignments/" + first + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        assertEquals(200, api.put("/org-units/" + unit + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String id = id(api.post("/assignments", assign(xMedicine, "")));
        String closing = unit("閉鎖予定", null);
        assertEquals(200, api.put("/org-units/" + closing + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());

        assertEquals("assignment.employment_unknown", errorOf(400, api.post("/assignments",
                "{\"employmentId\": \"nope\", \"orgUnitId\": \"" + xMedicine + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("assignment.org_unit_unknown", errorOf(400, api.post("/assignments", assign("nope", ""))));
        assertEquals("assignment.org_unit_not_valid", errorOf(409, api.post("/assignments", assign(closing, ""))));
        assertEquals(201, api.post("/assignments", assign(closing, ", \"validTo\": \"2027-04-01\"")).statusCode());
        assertEquals("assignment.outside_employment", errorOf(409, api.post("/assignments",
                "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + xMedicine + "\", \"validFrom\": \"2026-03-31\"}")));
        assertEquals("ref_item.unknown", errorOf(400, api.post("/assignments", assign(xMedicine, ", \"jobTitle\": \"physician\""))));
        assertEquals("invalid.percent", errorOf(400, api.post("/assignments", assign(xMedicine, ", \"allocationPercent\": 0"))));
        assertEquals("invalid.required", errorOf(400, api.post("/assignments", "{\"employmentId\": \"" + employment + "\"}")));
        assertEquals("assignment.not_valid_on", errorOf(404, api.put("/assignments/" + id + "/details", "{\"validFrom\": \"2026-03-31\"}")));
        assertEquals("assignment.not_found", errorOf(404, api.get("/assignments/nope")));
        assertEquals("assignment.not_found", errorOf(404, otherRealm.api.get("/assignments/" + id)));
        assertEquals("assignment.not_found", errorOf(404, otherRealm.api.put("/assignments/" + id + "/end", "{\"validTo\": \"2027-04-01\"}")));
        assertEquals("assignment.employment_unknown", errorOf(400, otherRealm.api.post("/assignments", assign(xMedicine, ""))));
        assertEquals(401, new Api(realm.name, null).post("/assignments", "{}").statusCode());
    }
}
