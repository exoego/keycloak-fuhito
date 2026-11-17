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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmExportIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> LISTS = List.of("ref-kinds", "ref-items", "attribute-definitions",
            "hierarchies", "org-units", "people", "employments", "assignments", "reporting-lines", "unit-roles",
            "qualifications", "external-ids", "change-orders", "erasures");
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
        source.refItem("WORK_LOCATION", "tokyo", "東京");
        source.refItem("COST_CENTER", "cc-ward", "病棟");
        ok(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"site\", \"type\": \"REF_ITEM\", "
                + "\"refKind\": \"site\", \"multiple\": true}"));
        ok(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"プロジェクト\"}"));
        // The default hierarchy goes with its ID and its name.
        line = defaultHierarchy(api).get("id").asText();
        ok(api.put("/hierarchies/" + line, "{\"name\": \"組織図\"}"));
        String hospital = id(api.post("/org-units", "{\"name\": \"病院\", \"workLocation\": \"tokyo\", "
                + "\"validFrom\": \"2020-04-01\", \"externalId\": \"H-1\", \"source\": \"hr\"}"));
        ok(api.put("/org-units/" + hospital + "/organization", "{\"organizationId\": \""
                + source.organization("x-hospital") + "\"}"));
        String ward = id(api.post("/org-units", "{\"name\": \"1病棟\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.put("/org-units/" + ward, "{\"code\": \"W1\", \"name\": \"第1病棟\", \"validFrom\": \"2024-04-01\"}"));
        ok(api.put("/org-units/" + ward + "/parent?hierarchy=project", "{\"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2021-04-01\"}"));
        String old = id(api.post("/org-units", "{\"name\": \"旧病棟\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.post("/reorganizations", "{\"effectiveDate\": \"2023-04-01\", \"steps\": [{\"op\": \"succeed\", "
                + "\"id\": \"" + old + "\", \"successorIds\": [\"" + ward + "\"]}]}"));
        JsonNode nurse = Api.json(api.post("/staff", "{\"userId\": \"" + ids.get("nurse") + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2020-04-01\", \"jobTitle\": \"chief\", \"costCenter\": \"cc-ward\", "
                + "\"contractEndDate\": \"2030-03-31\"}"));
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
        ok(api.post("/people", "{\"name\": \"山田 花子\", \"attributes\": {\"site\": [\"tokyo\"]}}"));

        ObjectNode first = export(api);
        assertTrue(first.get("attributeDefinitions").get(0).get("multiple").asBoolean(), first.toString());
        // The leave keeps the end of the contract, so all three terms of the nurse have it.
        assertEquals(3, first.get("employments").findValuesAsText("contractEndDate").stream()
                .filter("2030-03-31"::equals).count(), first.toString());
        assertEquals(2, first.get("orgUnits").get(0).get("versions").size() + first.get("orgUnits").get(1)
                .get("versions").size() + first.get("orgUnits").get(2).get("versions").size()
                + first.get("orgUnits").get(3).get("versions").size() - 3);

        // A new realm with the same users, after the first one is gone.
        TestRealm target = TestRealm.create("export-target");
        USERS.forEach(username -> target.user(username));
        // The organization of the new realm has the same alias and another ID.
        String organization = target.organization("x-hospital");
        source.remove();
        // A run of a job before the restore leaves its record, and the realm still counts as empty.
        assertEquals(409, target.api.post("/projection/rebuild", "").statusCode());
        HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(first));

        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(4, Api.json(restored).get("org-units").asInt());
        assertEquals(first, export(target.api));
        assertEquals(organization, Api.json(target.api.get("/org-units/" + hospital + "?asOf=2024-04-01"))
                .get("keycloakOrganizationId").asText());
        JsonNode nursePeriod = Api.json(target.api.get("/assignments/" + nurse.get("assignment").get("id").asText()))
                .get("periods").get(0);
        assertEquals("tokyo", nursePeriod.get("effectiveWorkLocation").asText());
        assertEquals("cc-ward", nursePeriod.get("costCenter").asText());
        assertEquals(line, defaultHierarchy(target.api).get("id").asText());
        assertEquals("組織図", defaultHierarchy(target.api).get("name").asText());
        assertEquals("restore.realm_not_empty", Api.json(target.api.post("/restore", JSON.writeValueAsString(first)))
                .get("error").asText());
        assertEquals(404, target.api.get("/export/nothing").statusCode());
    }

    // A unit names only the system that made it. Every ID is in the list of IDs.
    private static void assertIdsAreApart(JsonNode export, String unit) {
        JsonNode exported = null;
        for (JsonNode row : export.get("orgUnits")) {
            exported = row.get("id").asText().equals(unit) ? row : exported;
        }
        assertEquals("hr", exported.get("source").asText());
        assertFalse(exported.has("externalId"));
        assertEquals(List.of("H-1", "P-1"), export.get("externalIds").findValuesAsText("externalId").stream()
                .sorted().toList());
    }

    @Test
    void aRestoreWithReplaceBringsBackTheDataOfTheExport() throws Exception {
        TestRealm realm = TestRealm.withEmployer("export-replace");
        Api api = realm.api;
        String nurse = realm.user("nurse");
        String hospital = id(api.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2020-04-01\", "
                + "\"externalId\": \"H-1\", \"source\": \"hr\"}"));
        ok(api.put("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + hospital
                + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}"));
        String organization = realm.organization("x-hospital");
        ok(api.put("/org-units/" + hospital + "/organization", "{\"organizationId\": \"" + organization + "\"}"));
        ok(api.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("QUALIFICATION", "license", "免許");
        String license = id(api.post("/qualifications", "{\"userId\": \"" + nurse + "\", \"kind\": \"license\", "
                + "\"number\": \"1\", \"validFrom\": \"2010-04-01\"}"));
        ObjectNode before = export(api);
        assertIdsAreApart(before, hospital);

        // Changes by mistake after the export.
        ok(api.post("/org-units", "{\"name\": \"新病棟\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.put("/org-units/" + hospital, "{\"name\": \"誤った名前\", \"validFrom\": \"2025-04-01\"}"));
        ok(api.delete("/qualifications/" + license));
        ok(api.delete("/org-units/" + hospital + "/organization"));
        ok(api.delete("/external-ids?resourceType=ORG_UNIT&resourceId=" + hospital + "&source=hr"));

        assertEquals("restore.realm_not_empty", error(api.post("/restore", JSON.writeValueAsString(before))));
        HttpResponse<String> restored = api.post("/restore?replace=true", JSON.writeValueAsString(before));

        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(before, export(api));
        assertEquals(organization, Api.json(api.get("/org-units/" + hospital + "?asOf=2024-04-01"))
                .get("keycloakOrganizationId").asText());
        // The change log keeps what happened, the mistakes too.
        assertTrue(Api.json(api.get("/change-log?resourceType=FUHITO_QUALIFICATION&resourceId=" + license))
                .findValuesAsText("operation").contains("DELETE"));
    }

    @Test
    void aRestoreDoesNotBringBackErasedDataAndKeepsTheMasksOfTheExport() throws Exception {
        TestRealm realm = TestRealm.withEmployer("export-erased");
        String hospital = id(realm.api.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2020-04-01\"}"));
        List<String> users = List.of("erased", "masked");
        Map<String, String> people = new HashMap<>();
        for (String username : users) {
            people.put(username, id(realm.api.post("/people", "{\"userId\": \"" + realm.user(username)
                    + "\", \"name\": \"" + username + "\"}")));
        }
        // A mask needs a person with no open employment.
        ok(realm.api.post("/staff", "{\"personId\": \"" + people.get("erased") + "\", \"orgUnitId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}"));
        ObjectNode before = export(realm.api);

        assertEquals(204, realm.api.delete("/people/" + people.get("erased") + "/data").statusCode());
        ok(realm.api.post("/people/" + people.get("masked") + "/mask", ""));
        ok(realm.api.post("/restore?replace=true", JSON.writeValueAsString(before)));

        assertEquals(404, realm.api.get("/people/" + people.get("erased")).statusCode());
        // The mask came after the export, so the person is back as the export has it.
        JsonNode masked = Api.json(realm.api.get("/people/" + people.get("masked")));
        assertEquals("masked", masked.get("name").asText(), masked.toString());

        // An export after the erase has its record. A restore erases the person of the record in a new realm too.
        ObjectNode after = export(realm.api);
        assertEquals(people.get("erased"), after.get("erasures").get(0).get("personId").asText());
        before.set("erasures", after.get("erasures"));
        TestRealm target = TestRealm.create("export-erased-target");
        users.forEach(target::user);
        realm.remove();
        ok(target.api.post("/restore", JSON.writeValueAsString(before)));
        assertEquals(404, target.api.get("/people/" + people.get("erased")).statusCode());
        assertEquals(after.get("erasures"), export(target.api).get("erasures"));
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

        ObjectNode located = JSON.createObjectNode().put("format", "fuhito.export.v1");
        ObjectNode placed = located.putArray("orgUnits").addObject().put("id", "u-0");
        placed.putArray("versions").addObject().put("id", "v-0").put("validFrom", "2020-04-01").put("name", "X")
                .put("workLocationId", "l-1");
        placed.putArray("parents");
        placed.putArray("successors");
        HttpResponse<String> location = target.api.post("/restore", JSON.writeValueAsString(located));
        assertEquals("restore.reference_unknown", error(location));
        assertEquals("workLocationId", Api.json(location).get("params").get("field").asText());

        // The list of IDs keeps the rules of the table. One ID names one unit, and a unit has one ID of a system.
        for (String[] twice : new String[][] {{"u-3", "W-1", "u-4", "W-1", "restore.external_id_taken"},
                {"u-3", "W-1", "u-3", "W-2", "restore.external_id_twice"}}) {
            ObjectNode ids = JSON.createObjectNode().put("format", "fuhito.export.v1");
            ArrayNode units = ids.putArray("orgUnits");
            for (String unitId : List.of("u-3", "u-4")) {
                ObjectNode row = units.addObject().put("id", unitId).put("source", "hr");
                row.putArray("versions").addObject().put("id", "v-" + unitId).put("validFrom", "2020-04-01")
                        .put("name", "X");
                row.putArray("parents");
                row.putArray("successors");
            }
            ArrayNode rows = ids.putArray("externalIds");
            rows.addObject().put("id", "x-1").put("resourceType", "ORG_UNIT").put("resourceId", twice[0])
                    .put("source", "hr").put("externalId", twice[1]);
            rows.addObject().put("id", "x-2").put("resourceType", "ORG_UNIT").put("resourceId", twice[2])
                    .put("source", "hr").put("externalId", twice[3]);
            assertEquals(twice[4], error(target.api.post("/restore", JSON.writeValueAsString(ids))));
        }

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
    void anAdminOfAnotherRealmIsNotNamedInTheExport() throws Exception {
        // The admin of the tests belongs to the master realm.
        TestRealm source = TestRealm.create("export-announcer");
        String order = id(source.api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}"));
        ok(source.api.post("/change-orders/" + order + "/announce", ""));
        ObjectNode document = export(source.api);

        JsonNode announcedBy = document.get("changeOrders").get(0).get("announcedBy");
        assertTrue(announcedBy == null || announcedBy.isNull(), String.valueOf(announcedBy));
        TestRealm target = TestRealm.create("export-announcer");
        source.remove();
        ok(target.api.post("/restore", JSON.writeValueAsString(document)));
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

        ObjectNode linked = JSON.createObjectNode().put("format", "fuhito.export.v1");
        ObjectNode unit = linked.putArray("orgUnits").addObject().put("id", "u-1").put("organizationAlias", "nope");
        unit.putArray("versions").addObject().put("id", "v-1").put("validFrom", "2020-04-01").put("name", "X");
        unit.putArray("parents");
        unit.putArray("successors");
        HttpResponse<String> unknown = target.api.post("/restore", JSON.writeValueAsString(linked));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("restore.organization_unknown", Api.json(unknown).get("error").asText());
    }
}
