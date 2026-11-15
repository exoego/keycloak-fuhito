package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmExportIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> LISTS = List.of("ref-kinds", "ref-items", "attribute-definitions",
            "hierarchies", "org-units", "people", "employments", "assignments", "reporting-lines", "unit-roles",
            "qualifications", "external-ids", "change-orders");
    private static final List<String> USERS = List.of("nurse", "doctor", "former");

    private static String id(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, response.body());
        return Api.json(response).get("id").asText();
    }

    private static void ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
    }

    // Small pages, so that the export follows its links.
    private static ObjectNode export(Api api) throws Exception {
        ObjectNode document = JSON.createObjectNode().put("format", "fuhito.export.v1");
        for (String list : LISTS) {
            ArrayNode rows = document.putArray(camel(list));
            String next = "/export/" + list + "?max=2";
            while (next != null) {
                HttpResponse<String> response = api.get(next);
                assertEquals(200, response.statusCode(), response.body());
                Api.json(response).forEach(rows::add);
                next = response.headers().firstValue("Link")
                        .map(link -> link.substring(link.indexOf("/fuhito") + "/fuhito".length(), link.indexOf('>')))
                        .orElse(null);
            }
        }
        return document;
    }

    private static String line;

    private static JsonNode defaultHierarchy(Api api) throws Exception {
        JsonNode hierarchies = Api.json(api.get("/hierarchies"));
        return hierarchies.get(hierarchies.findValuesAsText("code").indexOf("default"));
    }

    private static String camel(String list) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '-') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }

    @Test
    void anExportLoadsIntoAnEmptyRealmAndGivesTheSameExportAgain() throws Exception {
        TestRealm source = TestRealm.withEmployer("export");
        Api api = source.api;
        Map<String, String> ids = new HashMap<>();
        USERS.forEach(username -> ids.put(username, source.user(username)));
        ok(api.post("/ref-kinds", "{\"code\": \"site\", \"name\": \"拠点\"}"));
        ok(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"name\": \"東京\"}"));
        source.refItem("JOB_TITLE", "chief", "主任");
        source.refItem("LEAVE_REASON", "parental", "育児休業");
        source.refItem("UNIT_ROLE", "approver", "承認者");
        source.refItem("QUALIFICATION", "license", "免許");
        ok(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"site\", \"type\": \"REF_ITEM\", "
                + "\"refKind\": \"site\"}"));
        ok(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"プロジェクト\"}"));
        // The default hierarchy goes with its ID and its name.
        line = defaultHierarchy(api).get("id").asText();
        ok(api.put("/hierarchies/" + line, "{\"name\": \"組織図\"}"));
        String hospital = id(api.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2020-04-01\"}"));
        String ward = id(api.post("/org-units", "{\"name\": \"1病棟\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.put("/org-units/" + ward, "{\"code\": \"W1\", \"name\": \"第1病棟\", \"validFrom\": \"2024-04-01\"}"));
        ok(api.put("/org-units/" + ward + "/parent?hierarchy=project", "{\"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2021-04-01\"}"));
        String old = id(api.post("/org-units", "{\"name\": \"旧病棟\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.post("/reorganizations", "{\"effectiveDate\": \"2023-04-01\", \"steps\": [{\"op\": \"succeed\", "
                + "\"id\": \"" + old + "\", \"successorIds\": [\"" + ward + "\"]}]}"));
        JsonNode nurse = Api.json(api.post("/staff", "{\"userId\": \"" + ids.get("nurse") + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2020-04-01\", \"jobTitle\": \"chief\"}"));
        String nurseEmployment = nurse.get("employment").get("id").asText();
        ok(api.post("/employments/" + nurseEmployment + "/leave", "{\"validFrom\": \"2022-04-01\", "
                + "\"validTo\": \"2023-04-01\", \"reason\": \"parental\"}"));
        JsonNode doctor = Api.json(api.post("/staff", "{\"userId\": \"" + ids.get("doctor")
                + "\", \"orgUnitId\": \"" + hospital + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.post("/reporting-lines", "{\"assignmentId\": \"" + nurse.get("assignment").get("id").asText()
                + "\", \"managerUserId\": \"" + ids.get("doctor") + "\", \"validFrom\": \"2021-04-01\"}"));
        ok(api.post("/unit-roles", "{\"employmentId\": \"" + doctor.get("employment").get("id").asText()
                + "\", \"orgUnitId\": \"" + ward + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.post("/qualifications", "{\"userId\": \"" + ids.get("nurse") + "\", \"kind\": \"license\", "
                + "\"number\": \"1\", \"validFrom\": \"2010-04-01\"}"));
        ok(api.put("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}"));
        ok(api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2024-04-01\", \"approverUserId\": \""
                + ids.get("doctor") + "\"}"));
        ok(api.post("/employments", "{\"userId\": \"" + ids.get("former")
                + "\", \"validFrom\": \"2010-04-01\", \"validTo\": \"2015-04-01\"}"));
        ok(api.post("/users/" + ids.get("former") + "/mask", ""));
        ok(api.post("/people", "{\"name\": \"山田 花子\", \"attributes\": {\"site\": \"tokyo\"}}"));

        ObjectNode first = export(api);
        assertEquals(2, first.get("orgUnits").get(0).get("versions").size() + first.get("orgUnits").get(1)
                .get("versions").size() + first.get("orgUnits").get(2).get("versions").size()
                + first.get("orgUnits").get(3).get("versions").size() - 3);

        // A new realm with the same users, after the first one is gone.
        TestRealm target = TestRealm.create("export-target");
        USERS.forEach(username -> target.user(username));
        source.remove();
        // A run of a job before the restore leaves its record, and the realm still counts as empty.
        assertEquals(409, target.api.post("/projection/rebuild", "").statusCode());
        HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(first));

        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(4, Api.json(restored).get("org-units").asInt());
        assertEquals(first, export(target.api));
        assertEquals(line, defaultHierarchy(target.api).get("id").asText());
        assertEquals("組織図", defaultHierarchy(target.api).get("name").asText());
        assertEquals("restore.realm_not_empty", Api.json(target.api.post("/restore", JSON.writeValueAsString(first)))
                .get("error").asText());
        assertEquals(404, target.api.get("/export/nothing").statusCode());
    }

    @Test
    void aRestoreChecksTheExportBeforeItWrites() throws Exception {
        TestRealm source = TestRealm.withEmployer("export-copy");
        String person = Api.json(source.api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        ObjectNode copy = export(source.api);
        TestRealm target = TestRealm.create("export-copy-target");

        // The first realm is still there, so its IDs are taken in this database.
        assertEquals("restore.id_taken", error(target.api.post("/restore", JSON.writeValueAsString(copy))));

        // A row may name only rows of the same export, not those of another realm.
        ObjectNode foreign = JSON.createObjectNode().put("format", "fuhito.export.v1");
        foreign.putArray("qualifications").addObject().put("id", "q-1").put("personId", person)
                .put("kindId", "k-1").put("validFrom", "2020-04-01");
        HttpResponse<String> named = target.api.post("/restore", JSON.writeValueAsString(foreign));
        assertEquals(400, named.statusCode(), named.body());
        assertEquals("restore.reference_unknown", Api.json(named).get("error").asText());
        assertEquals("personId", Api.json(named).get("params").get("field").asText());

        // A date that cannot be read, and a unit with no list of versions.
        ObjectNode badDate = JSON.createObjectNode().put("format", "fuhito.export.v1");
        ObjectNode unit = badDate.putArray("orgUnits").addObject().put("id", "u-1");
        unit.putArray("versions").addObject().put("id", "v-1").put("validFrom", "someday").put("name", "X");
        unit.putArray("parents");
        unit.putArray("successors");
        assertEquals("restore.bad_value", error(target.api.post("/restore", JSON.writeValueAsString(badDate))));
        ObjectNode noVersions = JSON.createObjectNode().put("format", "fuhito.export.v1");
        noVersions.putArray("orgUnits").addObject().put("id", "u-2");
        assertEquals("restore.bad_value", error(target.api.post("/restore", JSON.writeValueAsString(noVersions))));
        // Nothing was written.
        assertEquals(0, Api.json(target.api.get("/export/org-units")).size());
    }

    private static String error(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() == 400 || response.statusCode() == 409, response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void aRestoreNeedsTheUsersAndTheFormat() throws Exception {
        TestRealm target = TestRealm.create("export-users");
        ObjectNode document = JSON.createObjectNode().put("format", "fuhito.export.v1");
        document.putArray("people").addObject().put("id", "p-1").put("username", "nobody").put("name", "X")
                .putObject("names");

        assertEquals("restore.username_unknown", Api.json(target.api.post("/restore",
                JSON.writeValueAsString(document))).get("error").asText());
        assertEquals("restore.format", Api.json(target.api.post("/restore", "{\"format\": \"other\"}"))
                .get("error").asText());
    }
}
