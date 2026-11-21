package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreReferencesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void assertFails(TestRealm target, String at, String row, String code, Map<String, String> params)
            throws Exception {
        ObjectNode document = RestoreKeysIT.document();
        RestoreKeysIT.change(document, at, row);
        HttpResponse<String> response = target.api.post("/restore", JSON.writeValueAsString(document));
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals(code, error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(params), error.get("params"), response.body());
    }

    // The export has the hierarchy project and no row of the default hierarchy, which every realm has.
    @Test
    void aParentLinkOfAHierarchyThatTheExportDoesNotHaveFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-hierarchies");
        try {
            for (String code : List.of("race", "Project")) {
                assertFails(target, "/orgUnits/1/parents/0", "{\"hierarchy\": \"" + code + "\"}",
                        "restore.reference_unknown", Map.of("list", "org-units", "field", "hierarchy", "value", code));
            }
            ObjectNode document = RestoreKeysIT.document();
            RestoreKeysIT.change(document, "/orgUnits/1/parents", "{\"id\": \"l-2\", \"hierarchy\": \"project\", "
                    + "\"parentId\": \"u-1\", \"validFrom\": \"2020-04-01\"}");
            HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(document));
            assertEquals(200, restored.statusCode(), restored.body());
            assertEquals(List.of("default", "project"),
                    Api.json(target.api.get("/export/org-units")).findValuesAsText("hierarchy"));
        } finally {
            target.remove();
        }
    }

    // The realm has only the user nurse.
    @Test
    void aUsernameThatTheRealmDoesNotHaveFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-usernames");
        try {
            assertFails(target, "/people/0", "{\"username\": \"nobody\"}", "restore.username_unknown",
                    Map.of("list", "people", "id", "p-1", "field", "username", "username", "nobody"));
            for (String field : List.of("approver", "requester", "announcedBy")) {
                assertFails(target, "/changeOrders/0", "{\"announcedAt\": \"2024-03-02T00:00:00Z\", \"" + field
                        + "\": \"nobody\"}", "restore.username_unknown",
                        Map.of("list", "change-orders", "id", "o-1", "field", field, "username", "nobody"));
            }
            HttpResponse<String> restored = target.api.post("/restore",
                    JSON.writeValueAsString(RestoreKeysIT.document()));
            assertEquals(200, restored.statusCode(), restored.body());
        } finally {
            target.remove();
        }
    }

    // The realm has only the organization x-hospital.
    @Test
    void anOrganizationAliasThatTheRealmDoesNotHaveFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-organizations");
        try {
            assertFails(target, "/orgUnits/0", "{\"organizationAlias\": \"nope\"}", "restore.organization_unknown",
                    Map.of("list", "org-units", "id", "u-1", "field", "organizationAlias", "alias", "nope"));
            HttpResponse<String> restored = target.api.post("/restore",
                    JSON.writeValueAsString(RestoreKeysIT.document()));
            assertEquals(200, restored.statusCode(), restored.body());
        } finally {
            target.remove();
        }
    }
}
