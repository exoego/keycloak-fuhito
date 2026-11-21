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

    // The export has the hierarchy project and no row of the default hierarchy, which every realm has.
    @Test
    void aParentLinkOfAHierarchyThatTheExportDoesNotHaveFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = RestoreKeysIT.target("restore-hierarchies");
        try {
            for (String code : List.of("race", "Project")) {
                ObjectNode document = RestoreKeysIT.document();
                RestoreKeysIT.change(document, "/orgUnits/1/parents/0", "{\"hierarchy\": \"" + code + "\"}");
                HttpResponse<String> response = target.api.post("/restore", JSON.writeValueAsString(document));
                assertEquals(400, response.statusCode(), response.body());
                JsonNode error = Api.json(response);
                assertEquals("restore.reference_unknown", error.get("error").asText(), response.body());
                assertEquals(JSON.valueToTree(Map.of("list", "org-units", "field", "hierarchy", "value", code)),
                        error.get("params"), response.body());
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
}
