package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A value of another JSON type than its field fails with {@code invalid.type}, which names the field with its path
 * and the type of the field. Jackson would turn such a value into the type of the field, so that {@code 3.7} for a
 * whole number became {@code 3} and {@code "false"} became false.
 */
class ValueTypeIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"甲\"}}";

    private static final String SETUP = """
            {"effectiveDate": "2026-04-01",
             "changeOrders": [{"externalId": "O1", "title": "4月の異動"}],
             "refItems": [{"externalId": "I1", "kind": "JOB_TITLE", "code": "chief", "primaryLanguage": "ja",
                           "names": {"ja": {"name": "主任"}}}],
             "hierarchies": [{"externalId": "R1", "code": "project", "name": "プロジェクト"}],
             "orgUnits": [
               {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validFrom": "2020-04-01"},
               {"externalId": "W", "code": "w", "primaryLanguage": "ja", "names": {"ja": {"name": "病棟"}},
                "parentExternalId": "H", "validFrom": "2020-04-01"}],
             "people": [{"externalId": "P1", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}}],
             "employments": [{"externalId": "E1", "personExternalId": "P1", "validFrom": "2020-04-01",
                              "workPercent": 80.0}],
             "assignments": [
               {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "W", "validFrom": "2020-04-01",
                "allocationPercent": 50}]}
            """;

    // The status, the error, the field, and the type of a response, or the status and the body of another response.
    private static String failure(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 300) {
            return String.valueOf(response.statusCode());
        }
        JsonNode params = Api.json(response).path("params");
        return response.statusCode() + " " + (params.has("type") ? Api.json(response).get("error").asText() + " "
                + params.get("field").asText() + " " + params.get("type").asText() : response.body());
    }

    private static String lookup(Api api, String resourceType, String externalId) throws Exception {
        return Api.json(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    @Test
    void aRestWriteFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("type-rest");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        String hospital = lookup(api, "ORG_UNIT", "H");
        String ward = lookup(api, "ORG_UNIT", "W");
        String person = lookup(api, "PERSON", "P1");
        String employment = lookup(api, "EMPLOYMENT", "E1");
        String hierarchy = null;
        for (JsonNode read : Api.json(api.get("/hierarchies"))) {
            hierarchy = "project".equals(read.get("code").asText()) ? read.get("id").asText() : hierarchy;
        }
        ObjectNode before = RealmExportIT.export(api);
        String refItem = "{\"kind\": \"JOB_TITLE\", \"code\": \"%s\", " + NAMES + "%s}";

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.post("/ref-items", refItem.formatted("a", ", \"displayOrder\": 3.7"))));
        failures.add(failure(api.post("/ref-items", refItem.formatted("b", ", \"displayOrder\": \"3\""))));
        failures.add(failure(api.post("/ref-items", refItem.formatted("c", ", \"active\": \"false\""))));
        failures.add(failure(api.post("/ref-items", refItem.formatted("d", ", \"legalEntity\": 0"))));
        failures.add(failure(api.post("/hierarchies", "{\"code\": 123, \"name\": \"n\"}")));
        failures.add(failure(api.post("/hierarchies", "{\"code\": \"e\", \"name\": true}")));
        failures.add(failure(api.put("/hierarchies/" + hierarchy,
                "{\"code\": \"project\", \"name\": \"計画\", \"version\": 1.0}")));
        failures.add(failure(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": 1}}, "
                + "\"parentId\": \"" + hospital + "\", \"validFrom\": \"2026-05-01\"}")));
        failures.add(failure(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": [], "
                + "\"validFrom\": \"2026-05-01\"}")));
        failures.add(failure(api.post("/employments", "{\"personId\": \"" + person + "\", \"validFrom\": "
                + "\"2026-05-01\", \"terms\": [{\"validFrom\": \"2026-05-01\", \"workPercent\": \"50\"}]}")));
        failures.add(failure(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2026-05-01\", \"primary\": \"true\"}")));
        failures.add(failure(api.post("/change-orders", "{\"title\": [\"5月\"], \"effectiveDate\": \"2026-05-01\"}")));
        failures.add(failure(api.post("/hierarchies", "[]")));
        HttpResponse<String> japanese = api.withAcceptLanguage("ja").post("/ref-items",
                refItem.formatted("f", ", \"displayOrder\": 3.7"));

        assertEquals(List.of("400 invalid.type displayOrder integer",
                "400 invalid.type displayOrder integer",
                "400 invalid.type active boolean",
                "400 invalid.type legalEntity boolean",
                "400 invalid.type code string",
                "400 invalid.type name string",
                "400 invalid.type version integer",
                "400 invalid.type names.ja.name string",
                "400 invalid.type names object",
                "400 invalid.type terms[0].workPercent number",
                "400 invalid.type primary boolean",
                "400 invalid.type title string",
                "400 invalid.type body object"), failures);
        assertEquals("displayOrder は integer 型の値にしてください。別の型の値は変換しません。本文の \"3\" は数値ではなく、3.0 は整数ではありません。"
                + "クエリの数値は JSON と同じ形で、0 から 9 の数字だけで書いてください。クエリの真偽値は小文字の true か false にしてください。",
                Api.json(japanese).get("message").asText(), japanese.body());
        assertEquals(before, RealmExportIT.export(api));

        // A value of the type of its field writes.
        JsonNode item = Api.json(api.post("/ref-items", refItem.formatted("g",
                ", \"displayOrder\": 3, \"active\": false")));
        assertEquals(List.of("3", "false"), List.of(item.get("displayOrder").asText(),
                String.valueOf(item.path("active").asBoolean(false))));
        assertEquals(201, api.post("/employments", "{\"personId\": \"" + person + "\", \"validFrom\": "
                + "\"2026-05-01\", \"terms\": [{\"validFrom\": \"2026-05-01\", \"workPercent\": 80.0}]}").statusCode());
    }

    private static String rows(String list, String rows) {
        return "{\"effectiveDate\": \"2026-05-01\", \"" + list + "\": [" + rows + "]}";
    }

    @Test
    void anImportFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("type-import");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.post("/imports", rows("refItems", "{\"externalId\": \"I1\", \"displayOrder\": 3.7}"))));
        failures.add(failure(api.post("/imports", rows("refItems", "{\"externalId\": \"I1\", \"active\": \"false\"}"))));
        failures.add(failure(api.post("/imports", rows("orgUnits", "{\"externalId\": \"H\"}, "
                + "{\"externalId\": \"W\", \"code\": 123}"))));
        failures.add(failure(api.post("/imports", rows("orgUnits",
                "{\"externalId\": \"W\", \"names\": {\"ja\": {\"name\": 1}}}"))));
        failures.add(failure(api.post("/imports", rows("employments", "{\"externalId\": \"E1\", \"workPercent\": \"80\"}"))));
        failures.add(failure(api.post("/imports", rows("assignments",
                "{\"externalId\": \"A1\", \"allocationPercent\": \"50\"}"))));
        failures.add(failure(api.post("/imports", rows("assignments", "{\"externalId\": \"A1\", \"head\": 1}"))));
        failures.add(failure(api.post("/imports", rows("orgUnits", "\"W\""))));
        failures.add(failure(api.post("/imports", "{\"effectiveDate\": \"2026-05-01\", \"orgUnits\": {}}")));
        failures.add(failure(api.post("/imports", "{\"effectiveDate\": \"2026-05-01\", \"dryRun\": \"true\", "
                + "\"orgUnits\": [{\"externalId\": \"W\", \"code\": \"w2\"}]}")));
        failures.add(failure(api.post("/imports", "{\"effectiveDate\": \"2026-05-01\", \"dryRun\": null, "
                + "\"orgUnits\": [{\"externalId\": \"W\", \"code\": \"w2\"}]}")));

        assertEquals(List.of("400 invalid.type refItems[0].displayOrder integer",
                "400 invalid.type refItems[0].active boolean",
                "400 invalid.type orgUnits[1].code string",
                "400 invalid.type orgUnits[0].names.ja.name string",
                "400 invalid.type employments[0].workPercent number",
                "400 invalid.type assignments[0].allocationPercent number",
                "400 invalid.type assignments[0].head boolean",
                "400 invalid.type orgUnits[0] object",
                "400 invalid.type orgUnits array",
                "400 invalid.type dryRun boolean",
                "400 invalid.type dryRun boolean"), failures);
        assertEquals(before, RealmExportIT.export(api));
    }

    // A JSON pointer such as /orgUnits/0/versions/0 as a path such as orgUnits[0].versions[0].
    private static String path(String pointer) {
        return pointer.substring(1).replaceAll("/(\\d+)", "[$1]").replace('/', '.');
    }

    /**
     * A value of a restore document.
     *
     * @param pointer the JSON pointer of the value
     * @param value the value that the document gets
     * @param type the type of the field
     */
    record Change(String pointer, Object value, String type) {
    }

    // The document is not read yet, so the error has the path in place of a list and an ID of a row.
    @Test
    void aRestoreFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("type-restore");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);
        List<Change> changes = List.of(new Change("/refItems/0/displayOrder", 3.7, "integer"),
                new Change("/refItems/0/displayOrder", "3", "integer"),
                new Change("/hierarchies/0/version", 2.5, "integer"),
                new Change("/refItems/0/active", "true", "boolean"),
                new Change("/assignments/0/periods/0/head", 1, "boolean"),
                new Change("/refItems/0/active", null, "boolean"),
                new Change("/changeOrders/0/note", true, "string"),
                new Change("/employments/0/terms/0/workPercent", "50", "number"),
                new Change("/orgUnits/0/versions/0/names/ja/name", 1, "string"));

        List<String> failures = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Change change : changes) {
            ObjectNode document = before.deepCopy();
            int last = change.pointer().lastIndexOf('/');
            ((ObjectNode) document.at(change.pointer().substring(0, last)))
                    .set(change.pointer().substring(last + 1), JSON.valueToTree(change.value()));
            failures.add(failure(api.post("/restore?replace=true", JSON.writeValueAsString(document))));
            expected.add("400 invalid.type " + path(change.pointer()) + " " + change.type());
        }

        assertEquals(expected, failures);
        assertEquals(before, RealmExportIT.export(api));
        assertEquals(200, api.post("/restore?replace=true", JSON.writeValueAsString(before)).statusCode());
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aReorganizationStepFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("type-reorganization");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        String hospital = lookup(api, "ORG_UNIT", "H");
        String ward = lookup(api, "ORG_UNIT", "W");
        ObjectNode before = RealmExportIT.export(api);
        String reorganization = "{\"effectiveDate\": %s, \"steps\": [{\"op\": \"create\", \"ref\": \"a\", " + NAMES
                + ", \"parentId\": \"" + hospital + "\"%s}, {\"op\": \"change\", \"id\": \"" + ward + "\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"乙\"}}%s}]}";
        String date = "\"2027-04-01\"";

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(date,
                ", \"displayOrder\": 3.7", ""))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(date, "",
                ", \"code\": 1"))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(date,
                ", \"successorIds\": \"x\"", ""))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted("20270401", "", ""))));

        assertEquals(List.of("400 invalid.type steps[0].displayOrder integer",
                "400 invalid.type steps[1].code string",
                "400 invalid.type steps[0].successorIds array",
                "400 invalid.type effectiveDate string"), failures);
        assertEquals(before, RealmExportIT.export(api));
        assertEquals(200, api.postWithVersion("/reorganizations", reorganization.formatted(date,
                ", \"displayOrder\": 3", "")).statusCode());
    }

    @Test
    void aKeycloakEndpointTurnsAValueIntoTheTypeOfItsFieldAsBefore() throws Exception {
        TestRealm realm = TestRealm.create("type-keycloak");
        String token = KEYCLOAK.getKeycloakAdminClient().tokenManager().getAccessTokenString();

        HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm.name + "/groups"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\": 123}", StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(201, created.statusCode(), created.body());
        assertEquals(List.of("123"), KEYCLOAK.getKeycloakAdminClient().realm(realm.name).groups().groups().stream()
                .map(group -> group.getName()).toList());
    }
}
