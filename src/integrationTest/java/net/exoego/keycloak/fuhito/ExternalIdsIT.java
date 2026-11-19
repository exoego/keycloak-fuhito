package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalIdsIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void aUnitHasTheIdsOfSeveralSystems() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids");
        Api api = realm.api;
        HttpResponse<String> unit = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\", "
                + "\"externalId\": \"W-1\", \"source\": \"hr\"}");
        assertEquals(201, unit.statusCode(), unit.body());
        String ward = Api.json(unit).get("id").asText();
        // The ID of the system that made the unit is a row of the table like any other.
        assertEquals(List.of("hr=W-1"), Api.externalIds(Api.json(unit)));
        JsonNode own = ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=W-1"));
        assertEquals(ward, own.get("resourceId").asText());
        assertTrue(own.get("own").asBoolean());

        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"P-9\"}"));
        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"budget\", \"externalId\": \"B-3\"}"));
        JsonNode ids = ok(api.get("/external-ids?resourceType=ORG_UNIT&resourceId=" + ward));
        assertEquals(List.of("hr", "budget", "payroll"), ids.findValuesAsText("source"));
        assertEquals(List.of("true", "false", "false"), ids.findValuesAsText("own"));
        assertEquals(List.of("hr=W-1", "budget=B-3", "payroll=P-9"),
                Api.externalIds(ok(api.get("/org-units/" + ward + "?asOf=2020-04-01"))));
        assertEquals(ward, ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=payroll&externalId=P-9"))
                .get("resourceId").asText());
        assertFalse(Api.json(api.get("/change-log?resourceType=FUHITO_EXTERNAL_ID")).isEmpty());
        // A change of the details can give the unit the ID of one more system.
        assertEquals(List.of("hr=W-1", "budget=B-3", "ledger=L-1", "payroll=P-9"), Api.externalIds(ok(api.putWithVersion(
                "/org-units/" + ward, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\", \"source\": \"ledger\", "
                        + "\"externalId\": \"L-1\"}"))));
        // A new unit or person cannot take an ID that names another one, whichever system made that one.
        for (String taken : List.of("\"externalId\": \"W-1\", \"source\": \"hr\"",
                "\"externalId\": \"P-9\", \"source\": \"payroll\"")) {
            assertEquals("external_id.taken", errorOf(409, api.post("/org-units",
                    "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"2病棟\"}}, \"validFrom\": \"2020-04-01\", " + taken + "}")));
        }
        String other = Api.json(api.post("/people", "{\"name\": \"Other\"}")).get("id").asText();
        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + other
                + "\", \"source\": \"payroll\", \"externalId\": \"E-1\"}"));
        assertEquals("external_id.taken", errorOf(409, api.post("/people",
                "{\"name\": \"New\", \"externalId\": \"E-1\", \"source\": \"payroll\"}")));
        String third = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"3病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        assertEquals("external_id.taken", errorOf(409, api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", "
                + "\"resourceId\": \"" + third + "\", \"source\": \"hr\", \"externalId\": \"W-1\"}")));

        // The ID of the system that made the unit can change and go. The unit keeps the name of that system.
        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"hr\", \"externalId\": \"W-2\"}"));
        assertEquals(ward, ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=W-2"))
                .get("resourceId").asText());
        assertEquals(204, api.deleteWithVersion("/external-ids?resourceType=ORG_UNIT&resourceId=" + ward + "&source=hr")
                .statusCode());
        JsonNode kept = ok(api.get("/org-units/" + ward + "?asOf=2020-04-01"));
        assertEquals("hr", kept.get("source").asText());
        assertEquals(List.of("budget=B-3", "ledger=L-1", "payroll=P-9"), Api.externalIds(kept));
        assertEquals("external_id.unknown", errorOf(404, api.get(
                "/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=W-2")));
        assertEquals("hr", ok(api.get("/org-units/" + ward + "/history")).get("source").asText());
    }

    @Test
    void aMaskRemovesTheIdsOfAPersonAndEveryKindHasTheIdsOfEverySystem() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids-kinds");
        Api api = realm.api;
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        // A person who left is masked, and the IDs of the person in other systems go away.
        String user = realm.user("former");
        HttpResponse<String> employment = api.post("/employments", "{\"userId\": \"" + user
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2021-04-01\"}");
        assertEquals(201, employment.statusCode(), employment.body());
        String person = Api.json(employment).get("personId").asText();
        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + person
                + "\", \"source\": \"payroll\", \"externalId\": \"E-77\"}"));
        assertEquals(200, api.postWithVersion("/people/" + person + "/mask", "").statusCode());
        assertEquals(List.of(), ok(api.get("/external-ids?resourceType=PERSON&resourceId=" + person))
                .findValuesAsText("source"));
        assertEquals("external_id.unknown", errorOf(404, api.get(
                "/external-ids/lookup?resourceType=PERSON&source=payroll&externalId=E-77")));
        assertFalse(api.get("/change-log?resourceType=FUHITO_EXTERNAL_ID").body().contains("E-77"));

        // Each kind of resource keeps the IDs of every system.
        realm.refItem("QUALIFICATION", "license", "免許");
        String nurse = realm.user("nurse");
        HttpResponse<String> staff = api.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, staff.statusCode(), staff.body());
        String employmentId = Api.json(staff).get("employment").get("id").asText();
        String assignmentId = Api.json(staff).get("assignment").get("id").asText();
        HttpResponse<String> license = api.post("/qualifications", "{\"userId\": \"" + nurse
                + "\", \"kind\": \"license\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, license.statusCode(), license.body());
        String qualificationId = Api.json(license).get("id").asText();
        for (String[] resource : new String[][] {{"EMPLOYMENT", employmentId}, {"ASSIGNMENT", assignmentId},
                {"QUALIFICATION", qualificationId}}) {
            for (String value : List.of("X-1", "X-2")) {
                ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"" + resource[0] + "\", \"resourceId\": \""
                        + resource[1] + "\", \"source\": \"payroll\", \"externalId\": \"" + value + "\"}"));
            }
            assertEquals(List.of("X-2"), ok(api.get("/external-ids?resourceType=" + resource[0] + "&resourceId="
                    + resource[1])).findValuesAsText("externalId"));
            assertEquals(resource[1], ok(api.get("/external-ids/lookup?resourceType=" + resource[0]
                    + "&source=payroll&externalId=X-2")).get("resourceId").asText());
            assertEquals("external_id.unknown", errorOf(404, api.get("/external-ids/lookup?resourceType="
                    + resource[0] + "&source=hr&externalId=none")));
        }
    }

    @Test
    void aRecordThatGoesAwayTakesItsIdsWithIt() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids-removal");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String lookup = "/external-ids/lookup?resourceType=";

        String person = Api.json(api.post("/people", "{\"name\": \"Temp\", \"externalId\": \"P-1\", "
                + "\"source\": \"hr\"}")).get("id").asText();
        assertEquals(204, api.deleteWithVersion("/people/" + person).statusCode());
        assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "PERSON&source=hr&externalId=P-1")));
        assertEquals(201, api.post("/people", "{\"name\": \"Next\", \"externalId\": \"P-1\", \"source\": \"hr\"}")
                .statusCode());

        String holder = Api.json(api.post("/people", "{\"name\": \"Holder\"}")).get("id").asText();
        String license = Api.json(api.post("/qualifications", "{\"personId\": \"" + holder + "\", \"kind\": "
                + "\"license\", \"validFrom\": \"2020-04-01\", \"externalId\": \"Q-1\", \"source\": \"hr\"}"))
                .get("id").asText();
        assertEquals(204, api.deleteWithVersion("/qualifications/" + license).statusCode());
        assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "QUALIFICATION&source=hr&externalId=Q-1")));

        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String employment = Api.json(api.post("/employments", "{\"personId\": \"" + holder + "\", \"validFrom\": "
                + "\"2020-04-01\", \"externalId\": \"E-1\", \"source\": \"hr\"}")).get("id").asText();
        String planned = Api.json(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", "
                + "\"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2027-04-01\", \"externalId\": \"A-1\", "
                + "\"source\": \"hr\"}")).get("id").asText();
        // The employment ends before the planned assignment starts, so the assignment goes away.
        assertEquals(200, api.putWithVersion("/employments/" + employment + "/end", "{\"validTo\": \"2026-04-01\"}")
                .statusCode());
        assertEquals(404, api.get("/assignments/" + planned).statusCode());
        assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "ASSIGNMENT&source=hr&externalId=A-1")));
        assertEquals(204, api.deleteWithVersion("/employments/" + employment).statusCode());
        assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "EMPLOYMENT&source=hr&externalId=E-1")));
    }
}
