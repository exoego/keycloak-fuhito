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
        assertEquals("assignment.already_ended", errorOf(409, api.put("/assignments/" + id + "/end", "{\"validTo\": \"2029-04-01\"}")));
        assertEquals("assignment.later_changes_exist", errorOf(409, api.put("/assignments/" + id + "/end", "{\"validTo\": \"2027-01-01\"}")));
        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + directorId)));
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
