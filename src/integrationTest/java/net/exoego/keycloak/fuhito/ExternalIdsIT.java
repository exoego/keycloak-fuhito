package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        String other = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Other\"}}}")).get("id").asText();
        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + other
                + "\", \"source\": \"payroll\", \"externalId\": \"E-1\"}"));
        assertEquals("external_id.taken", errorOf(409, api.post("/people",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"New\"}}, \"externalId\": \"E-1\", "
                        + "\"source\": \"payroll\"}")));
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
    void anIdFitsItsColumnOf255Characters() throws Exception {
        Api api = TestRealm.create("external-id-length").api;
        // An emoji is one character in the column, though Java holds it in two chars.
        String fits = "😀".repeat(255);
        String tooLong = "😀".repeat(256);
        String unit = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}, "
                + "\"validFrom\": \"2020-04-01\", \"source\": \"hr\", \"externalId\": \"%s\"}";
        HttpResponse<String> created = api.post("/org-units", unit.formatted("1病棟", fits));
        assertEquals(201, created.statusCode(), created.body());
        String ward = Api.json(created).get("id").asText();
        assertEquals(List.of("hr=" + fits), Api.externalIds(Api.json(created)));
        assertEquals("external_id.bad_value", errorOf(400, api.post("/org-units", unit.formatted("2病棟", tooLong))));

        String put = "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"}";
        assertEquals(fits, ok(api.putWithVersion("/external-ids", put.formatted(fits))).get("externalId").asText());
        assertEquals("external_id.bad_value", errorOf(400, api.putWithVersion("/external-ids", put.formatted(tooLong))));
        assertEquals(List.of(fits, fits), ok(api.get("/external-ids?resourceType=ORG_UNIT&resourceId=" + ward))
                .findValuesAsText("externalId"));
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

        String person = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Temp\"}}, \"externalId\": \"P-1\", "
                + "\"source\": \"hr\"}")).get("id").asText();
        assertEquals(204, api.deleteWithVersion("/people/" + person).statusCode());
        assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "PERSON&source=hr&externalId=P-1")));
        assertEquals(201, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Next\"}}, \"externalId\": \"P-1\", \"source\": \"hr\"}")
                .statusCode());

        String holder = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Holder\"}}}")).get("id").asText();
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

    // Creates a resource with the ID of the system hr, and returns its ID.
    private static String created(Api api, String path, String body, String externalId) throws Exception {
        HttpResponse<String> response = api.post(path, body.substring(0, body.length() - 1)
                + ", \"source\": \"hr\", \"externalId\": \"" + externalId + "\"}");
        assertEquals(201, response.statusCode(), response.body());
        JsonNode resource = Api.json(response);
        assertEquals(List.of("hr=" + externalId), Api.externalIds(resource));
        // The create and its ID are one write.
        assertEquals(1, resource.get("version").asLong());
        // The same request again fails, before it writes anything.
        assertEquals("external_id.taken", errorOf(409, api.post(path, body.substring(0, body.length() - 1)
                + ", \"source\": \"hr\", \"externalId\": \"" + externalId + "\"}")));
        return resource.get("id").asText();
    }

    @Test
    void everyResourceWithAnIdHasTheIdsOfEverySystem() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids-every-kind");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String kind = created(api, "/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}}}", "K-1");
        String item = created(api, "/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}", "I-1");
        String definition = created(api, "/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"beds\", \"type\": \"NUMBER\"}", "D-1");
        String hierarchy = created(api, "/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}", "H-1");
        String order = created(api, "/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}", "O-1");
        String role = created(api, "/unit-roles", "{\"employmentId\": \"" + staff.get("employment").get("id").asText()
                + "\", \"orgUnitId\": \"" + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}", "R-1");
        String line = created(api, "/reporting-lines", "{\"assignmentId\": \"" + staff.get("assignment").get("id").asText()
                + "\", \"managerUserId\": \"" + realm.user("boss") + "\", \"validFrom\": \"2021-04-01\"}", "L-1");

        String[][] resources = {{"REF_KIND", kind, "/ref-kinds/site"}, {"REF_ITEM", item, "/ref-items/" + item},
                {"ATTRIBUTE_DEFINITION", definition, "/attribute-definitions/" + definition},
                {"HIERARCHY", hierarchy, "/hierarchies/" + hierarchy}, {"CHANGE_ORDER", order, "/change-orders/" + order},
                {"UNIT_ROLE", role, "/unit-roles/" + role}, {"REPORTING_LINE", line, "/reporting-lines/" + line}};
        for (int i = 0; i < resources.length; i++) {
            String type = resources[i][0];
            String id = resources[i][1];
            String path = resources[i][2];
            JsonNode found = ok(api.get("/external-ids/lookup?resourceType=" + type + "&source=hr&externalId="
                    + Api.externalIds(read(api, path)).getFirst().substring("hr=".length())));
            assertEquals(id, found.get("resourceId").asText(), type);
            assertFalse(found.get("own").asBoolean(), type);

            // A change of an ID is a write of its resource.
            String payroll = "{\"resourceType\": \"" + type + "\", \"resourceId\": \"" + id
                    + "\", \"source\": \"payroll\", \"externalId\": \"P-" + i + "\"}";
            ok(api.putWithVersion("/external-ids", payroll));
            JsonNode changed = read(api, path);
            assertEquals(2, changed.get("version").asLong(), type);
            assertEquals("payroll=P-" + i, Api.externalIds(changed).getLast(), type);
            assertEquals(List.of("hr", "payroll"), ok(api.get("/external-ids?resourceType=" + type + "&resourceId="
                    + id)).findValuesAsText("source"), type);
            assertEquals(204, api.deleteWithVersion("/external-ids?resourceType=" + type + "&resourceId=" + id
                    + "&source=payroll").statusCode(), type);
            assertEquals(3, read(api, path).get("version").asLong(), type);
            assertEquals("external_id.resource_unknown", errorOf(404, api.putWithVersion("/external-ids",
                    payroll.replace(id, "nope"))), type);
        }

        // A list reads the IDs of every entry at once.
        String person = staff.get("employment").get("personId").asText();
        Map<String, String> lists = Map.of("/ref-kinds", "K-1", "/ref-items?kind=site", "I-1",
                "/attribute-definitions", "D-1", "/hierarchies", "H-1", "/change-orders", "O-1",
                "/people/" + person + "/unit-roles", "R-1", "/org-units/" + unit + "/role-holders", "R-1");
        for (Map.Entry<String, String> list : lists.entrySet()) {
            List<String> ids = new ArrayList<>();
            ok(api.get(list.getKey())).forEach(entry -> entry.path("externalIds")
                    .forEach(id -> ids.add(id.get("externalId").asText())));
            assertEquals(List.of(list.getValue()), ids, list.getKey());
        }

        // An import that changes a hierarchy and gives it an ID writes it once.
        HttpResponse<String> imported = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet", "hierarchies": [{"id": "%s", "name": "Q"}],
                 "externalIds": [{"resourceType": "HIERARCHY", "resourceId": "%s", "externalId": "S-1"}]}"""
                .formatted(hierarchy, hierarchy));
        assertEquals(200, imported.statusCode(), imported.body());
        JsonNode renamed = ok(api.get("/hierarchies/" + hierarchy));
        assertEquals("Q", renamed.get("name").asText());
        assertEquals(4, renamed.get("version").asLong());
        assertEquals(List.of("hr=H-1", "sheet=S-1"), Api.externalIds(renamed));

        // A resource that goes away takes its IDs with it.
        for (int i = resources.length - 1; i >= 0; i--) {
            String path = resources[i][2];
            String externalId = Api.externalIds(read(api, path)).getFirst().substring("hr=".length());
            assertEquals(204, api.deleteWithVersion(path).statusCode(), path);
            assertEquals("external_id.unknown", errorOf(404, api.get("/external-ids/lookup?resourceType="
                    + resources[i][0] + "&source=hr&externalId=" + externalId)));
        }
        assertEquals(201, api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\", \"source\": \"hr\", "
                + "\"externalId\": \"H-1\"}").statusCode());
    }

    private static String with(String body, String fields) {
        return body.substring(0, body.length() - 1) + fields + "}";
    }

    @Test
    void aCreateOfAResourceThatKeepsNoSystemTakesSourceOnlyWithAnId() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids-create-source");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"area\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"地域\"}}}").statusCode());
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}}}");
        bodies.put("/ref-items", "{\"kind\": \"area\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}");
        bodies.put("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"beds\", \"type\": \"NUMBER\"}");
        bodies.put("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}");
        bodies.put("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}");
        bodies.put("/unit-roles", "{\"employmentId\": \"" + staff.get("employment").get("id").asText()
                + "\", \"orgUnitId\": \"" + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}");
        // The manager has no person yet, so the create of the line would make one.
        bodies.put("/reporting-lines", "{\"assignmentId\": \"" + staff.get("assignment").get("id").asText()
                + "\", \"managerUserId\": \"" + realm.user("boss") + "\", \"validFrom\": \"2021-04-01\"}");
        JsonNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (Map.Entry<String, String> each : bodies.entrySet()) {
            String path = each.getKey();
            expected.addAll(List.of(path + " 400 invalid.value_fixed source", path + " 400 invalid.required source"));
            for (String fields : List.of(", \"source\": \"hr\"", ", \"externalId\": \"X\"")) {
                HttpResponse<String> response = api.post(path, with(each.getValue(), fields));
                got.add(path + " " + response.statusCode() + " " + Api.json(response).path("error").asText() + " "
                        + Api.json(response).path("params").path("field").asText());
            }
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        // With an ID, the source names the system of that ID, and the resource keeps no system.
        for (Map.Entry<String, String> each : bodies.entrySet()) {
            String path = each.getKey();
            String externalId = "X-" + path.substring(1);
            HttpResponse<String> response = api.post(path, with(each.getValue(), ", \"source\": \"hr\", "
                    + "\"externalId\": \"" + externalId + "\""));
            assertEquals(201, response.statusCode(), response.body());
            JsonNode created = Api.json(response);
            assertEquals(List.of("hr=" + externalId), Api.externalIds(created), path);
            assertTrue(created.path("source").isMissingNode(), path);
        }
    }

    @Test
    void aTransferGivesTheIdsOfWhatTheEndRemovesToTheCopies() throws Exception {
        TestRealm realm = TestRealm.create("external-ids-transfer");
        Api api = realm.api;
        String corporation = realm.legalEntity("医療法人X会");
        String company = realm.legalEntity("株式会社Yサービス");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        realm.refItem("UNIT_ROLE", "hr-contact", "人事担当");
        String kitchen = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"栄養科\"}}, \"parentId\": \""
                + corporation + "\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"parentId\": \""
                + corporation + "\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("cook") + "\", \"employerId\": \""
                + company + "\", \"orgUnitId\": \"" + kitchen + "\", \"validFrom\": \"2020-04-01\"}"));
        String employment = staff.get("employment").get("id").asText();
        String assignment = staff.get("assignment").get("id").asText();
        String boss = realm.user("boss");
        String heldLine = created(api, "/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + boss + "\", \"validFrom\": \"2020-04-01\"}", "L-1");
        String plannedLine = created(api, "/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + boss + "\", \"kind\": \"DOTTED\", \"validFrom\": \"2027-07-01\"}", "L-2");
        String heldRole = created(api, "/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + kitchen + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}", "R-1");
        String plannedRole = created(api, "/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + kitchen + "\", \"role\": \"hr-contact\", \"validFrom\": \"2027-04-01\"}", "R-2");
        HttpResponse<String> planned = api.post("/assignments", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2027-07-01\", \"externalId\": \"A-1\", "
                + "\"source\": \"hr\"}");
        assertEquals(201, planned.statusCode(), planned.body());
        String plannedAssignment = Api.json(planned).get("id").asText();

        HttpResponse<String> response = api.postWithVersion("/employments/" + employment + "/transfer",
                "{\"employerId\": \"" + corporation + "\", \"validFrom\": \"2027-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        JsonNode transfer = Api.json(response);

        // A line and a role that hold before the date are only ended. They keep their IDs, and their copies get none.
        String lookup = "/external-ids/lookup?resourceType=";
        assertEquals(heldLine, ok(api.get(lookup + "REPORTING_LINE&source=hr&externalId=L-1")).get("resourceId").asText());
        assertEquals(heldRole, ok(api.get(lookup + "UNIT_ROLE&source=hr&externalId=R-1")).get("resourceId").asText());
        // The end removes the ones that start on the date or later, and their copies take their IDs.
        // Each kind names the field and the value that tell the copy of the removed one.
        String[][] kinds = {
                {"reportingLines", "/reporting-lines/", "REPORTING_LINE", "L-2", plannedLine, "validFrom", "2027-07-01"},
                {"unitRoles", "/unit-roles/", "UNIT_ROLE", "R-2", plannedRole, "role", "hr-contact"},
                {"assignments", "/assignments/", "ASSIGNMENT", "A-1", plannedAssignment, "validFrom", "2027-07-01"}};
        for (String[] kind : kinds) {
            assertEquals(2, transfer.get(kind[0]).size(), kind[0]);
            String copy = null;
            for (JsonNode each : transfer.get(kind[0])) {
                boolean later = each.get(kind[5]).asText().equals(kind[6]);
                assertEquals(later ? List.of("hr=" + kind[3]) : List.of(), Api.externalIds(each), kind[0]);
                assertEquals(1, each.get("version").asLong(), kind[0]);
                if (later) {
                    copy = each.get("id").asText();
                }
            }
            assertEquals(List.of("hr=" + kind[3]), Api.externalIds(ok(api.get(kind[1] + copy))), kind[0]);
            assertEquals(1, ok(api.get(kind[1] + copy)).get("version").asLong(), kind[0]);
            assertEquals(copy, ok(api.get(lookup + kind[2] + "&source=hr&externalId=" + kind[3]))
                    .get("resourceId").asText(), kind[0]);
            // The ID is the same row, written once more.
            assertEquals(List.of("2"), ok(api.get("/external-ids?resourceType=" + kind[2] + "&resourceId=" + copy))
                    .findValuesAsText("version"), kind[0]);
            assertEquals(404, api.get(kind[1] + kind[4]).statusCode(), kind[0]);
        }
    }

    @Test
    void aMaskAndAnEraseRemoveTheIdsOfTheLinesAndTheRolesOfThePerson() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids-person-data");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String boss = realm.user("boss");
        String lookup = "/external-ids/lookup?resourceType=";
        for (String name : List.of("masked", "erased")) {
            JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user(name) + "\", \"orgUnitId\": \""
                    + unit + "\", \"validFrom\": \"2020-04-01\"}"));
            String employment = staff.get("employment").get("id").asText();
            String person = staff.get("employment").get("personId").asText();
            String role = created(api, "/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                    + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}", "R-" + name);
            String line = created(api, "/reporting-lines", "{\"assignmentId\": \""
                    + staff.get("assignment").get("id").asText() + "\", \"managerUserId\": \"" + boss
                    + "\", \"validFrom\": \"2020-04-01\"}", "L-" + name);
            assertEquals(200, api.putWithVersion("/employments/" + employment + "/end",
                    "{\"validTo\": \"2021-04-01\"}").statusCode());
            long roleVersion = ok(api.get("/unit-roles/" + role)).get("version").asLong();
            long lineVersion = ok(api.get("/reporting-lines/" + line)).get("version").asLong();

            if (name.equals("masked")) {
                assertEquals(200, api.postWithVersion("/people/" + person + "/mask", "").statusCode());
                // The line and the role stay, and lose their IDs.
                assertEquals(List.of(), Api.externalIds(ok(api.get("/unit-roles/" + role))));
                assertEquals(roleVersion + 1, ok(api.get("/unit-roles/" + role)).get("version").asLong());
                assertEquals(lineVersion + 1, ok(api.get("/reporting-lines/" + line)).get("version").asLong());
            } else {
                assertEquals(204, api.deleteWithVersion("/people/" + person + "/data").statusCode());
            }
            assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "UNIT_ROLE&source=hr&externalId=R-"
                    + name)));
            assertEquals("external_id.unknown", errorOf(404, api.get(lookup + "REPORTING_LINE&source=hr&externalId=L-"
                    + name)));
        }
    }

    @Test
    void aRestoreKeepsTheIdsOfEveryKind() throws Exception {
        TestRealm source = TestRealm.create("external-ids-export");
        String hierarchy = created(source.api, "/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}", "H-1");
        String order = created(source.api, "/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}", "O-1");
        String kind = created(source.api, "/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}}}", "K-1");
        var export = RealmExportIT.export(source.api);

        TestRealm target = TestRealm.create("external-ids-restore");
        source.remove();
        HttpResponse<String> restored = target.api.post("/restore", export.toString());
        assertEquals(200, restored.statusCode(), restored.body());

        String lookup = "/external-ids/lookup?resourceType=";
        assertEquals(hierarchy, ok(target.api.get(lookup + "HIERARCHY&source=hr&externalId=H-1")).get("resourceId")
                .asText());
        assertEquals(order, ok(target.api.get(lookup + "CHANGE_ORDER&source=hr&externalId=O-1")).get("resourceId")
                .asText());
        assertEquals(kind, ok(target.api.get(lookup + "REF_KIND&source=hr&externalId=K-1")).get("resourceId")
                .asText());
        // A restore writes no resource again.
        assertEquals(1, ok(target.api.get("/change-orders/" + order)).get("version").asLong());
        assertEquals(1, ok(target.api.get("/hierarchies/" + hierarchy)).get("version").asLong());
        assertEquals(export, RealmExportIT.export(target.api));
    }

    // A kind of reference data is read from the list of kinds.
    static JsonNode read(Api api, String path) throws Exception {
        if (path.startsWith("/ref-kinds/")) {
            for (JsonNode kind : ok(api.get("/ref-kinds"))) {
                if (kind.get("code").asText().equals(path.substring("/ref-kinds/".length()))) {
                    return kind;
                }
            }
            throw new AssertionError(path);
        }
        return ok(api.get(path));
    }
}
