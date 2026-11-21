package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A JSON body that gives one key twice in one object fails with {@code invalid.duplicate_key}, which names the key
 * with its path. Keycloak would keep the last value and drop the others.
 */
class DuplicateKeyIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String unit(String names, String more) {
        return "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": " + names + "}, \"validFrom\": \"2020-04-01\""
                + more + "}";
    }

    private static void assertDuplicate(HttpResponse<String> response, String field) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("invalid.duplicate_key", error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(Map.of("field", field)), error.get("params"), response.body());
    }

    @Test
    void aRestWriteFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("dup-rest");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "note", "STRING");
        ObjectNode before = RealmExportIT.export(api);

        assertDuplicate(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"甲\"}, "
                + "\"ja\": {\"name\": \"乙\"}}, \"validFrom\": \"2020-04-01\"}"), "names.ja");
        assertDuplicate(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"primaryLanguage\": \"en\", \"names\": "
                + "{\"ja\": {\"name\": \"甲\"}, \"en\": {\"name\": \"A\"}}, \"validFrom\": \"2020-04-01\"}"),
                "primaryLanguage");
        assertDuplicate(api.post("/org-units", unit("{\"name\": \"甲\", \"name\": \"乙\"}", "")), "names.ja.name");
        assertDuplicate(api.post("/org-units", unit("{\"name\": \"甲\"}",
                ", \"attributes\": {\"note\": \"a\", \"note\": \"b\"}")), "attributes.note");
        assertDuplicate(api.post("/change-orders", "{\"title\": \"t\", \"effectiveDate\": \"2026-04-01\", "
                + "\"title\": \"u\"}"), "title");
        HttpResponse<String> japanese = api.withAcceptLanguage("ja").post("/change-orders",
                "{\"title\": \"t\", \"title\": \"u\", \"effectiveDate\": \"2026-04-01\"}");

        assertEquals("title のキーが一つのオブジェクトに二度あります。キーは一度だけにしてください。",
                Api.json(japanese).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));
        assertEquals(201, api.post("/org-units", unit("{\"name\": \"甲\"}", ", \"attributes\": {\"note\": \"a\"}"))
                .statusCode());
    }

    // Keycloak gave these errors before the extension read its own bodies.
    @Test
    void otherBadJsonFailsAsBefore() throws Exception {
        Api api = TestRealm.create("dup-other").api;

        assertError(api.post("/hierarchies", "{\"code\": \"c\", \"name\": "),
                "{\"error\":\"invalid_request\",\"error_description\":\"Cannot parse the JSON\"}");
        assertError(api.post("/org-units", unit("{\"name\": \"甲\"}", ", \"displayOrder\": \"x\"")),
                "{\"error\":\"unknown_error\",\"error_description\":\"Cannot parse the JSON\"}");
        assertError(api.post("/hierarchies", ""),
                "{\"error\":\"invalid.required\",\"params\":{\"field\":\"body\"},\"message\":\"body is required.\"}");
    }

    private static void assertError(HttpResponse<String> response, String body) {
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }

    @Test
    void anImportFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("dup-import");
        Api api = realm.api;
        ObjectNode before = RealmExportIT.export(api);

        assertDuplicate(api.post("/imports", """
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [
                   {"externalId": "u1", "primaryLanguage": "ja", "names": {"ja": {"name": "甲"}}},
                   {"externalId": "u2", "primaryLanguage": "ja", "names": {"ja": {"name": "丙"}, "ja": {"name": "丁"}}}]}
                """), "orgUnits[1].names.ja");
        assertDuplicate(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "dryRun": true, "effectiveDate": "2026-05-01", "orgUnits": []}
                """), "effectiveDate");
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aRestoreFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("dup-restore");
        Api api = realm.api;
        assertEquals(201, api.post("/org-units", unit("{\"name\": \"PH-NAME\"}", "")).statusCode());
        ObjectNode before = RealmExportIT.export(api);
        String document = JSON.writeValueAsString(before);
        assertEquals(2, document.split("\"name\":\"PH-NAME\"", -1).length);

        assertDuplicate(api.post("/restore?replace=true",
                document.replace("\"name\":\"PH-NAME\"", "\"name\":\"PH-OTHER\",\"name\":\"PH-NAME\"")),
                pathOf(before, "PH-NAME", ""));
        assertEquals(before, RealmExportIT.export(api));
    }

    // The path of the field that holds a text, such as orgUnits[0].versions[0].names.ja.name.
    private static String pathOf(JsonNode node, String value, String path) {
        if (node.isTextual()) {
            return node.asText().equals(value) ? path : null;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String found = pathOf(node.get(i), value, path + "[" + i + "]");
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String found = pathOf(field.getValue(), value, path.isEmpty() ? field.getKey()
                    : path + "." + field.getKey());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aReorganizationStepFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("dup-reorganization");
        Api api = realm.api;
        String root = Api.json(api.post("/org-units", unit("{\"name\": \"病院\"}", ""))).get("id").asText();
        ObjectNode before = RealmExportIT.export(api);

        assertDuplicate(api.post("/reorganizations", """
                {"effectiveDate": "2027-04-01", "steps": [
                  {"op": "create", "ref": "a", "primaryLanguage": "ja", "names": {"ja": {"name": "甲"}},
                   "parentId": "%1$s"},
                  {"op": "create", "ref": "b", "primaryLanguage": "ja",
                   "names": {"ja": {"name": "乙"}, "ja": {"name": "丙"}}, "parentId": "%1$s"}
                ]}
                """.formatted(root)), "steps[1].names.ja");
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aKeycloakEndpointReadsItsBodyAsBefore() throws Exception {
        TestRealm realm = TestRealm.create("dup-keycloak");
        String token = KEYCLOAK.getKeycloakAdminClient().tokenManager().getAccessTokenString();

        HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm.name + "/groups"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"first\", \"name\": \"last\"}",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(201, created.statusCode(), created.body());
        assertEquals(List.of("last"), KEYCLOAK.getKeycloakAdminClient().realm(realm.name).groups().groups().stream()
                .map(group -> group.getName()).toList());
    }
}
