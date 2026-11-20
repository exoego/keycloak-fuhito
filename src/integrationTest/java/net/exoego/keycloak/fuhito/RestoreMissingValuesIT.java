package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RealmExportIT.named;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreMissingValuesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aMissingRowOrValueFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = TestRealm.create("restore-missing");
        ObjectNode noAttributes = withPerson();
        named(((ArrayNode) noAttributes.get("people")).addObject().put("id", "p-2"));
        ObjectNode noTitle = withPerson();
        noTitle.putArray("changeOrders").addObject().put("id", "o-1").put("effectiveDate", "2026-04-01")
                .put("createdAt", "2026-03-01T00:00:00Z");
        ObjectNode nullRow = withPerson();
        ((ArrayNode) nullRow.get("people")).addNull();
        ObjectNode nullVersion = withPerson();
        ObjectNode unit = nullVersion.putArray("orgUnits").addObject().put("id", "u-1");
        unit.putArray("versions").addNull();
        unit.putArray("parents");
        unit.putArray("successors");

        assertBadValue(target.api, noAttributes, Map.of("list", "people", "id", "p-2", "field", "attributes"));
        assertBadValue(target.api, noTitle, Map.of("list", "change-orders", "id", "o-1", "field", "title"));
        assertBadValue(target.api, nullRow, Map.of("list", "people"));
        assertBadValue(target.api, nullVersion, Map.of("list", "org-unit-versions"));

        // Not even the good person of each document was written.
        assertEquals(0, Api.json(target.api.get("/export/people")).size());
        assertEquals(0, Api.json(target.api.get("/export/change-orders")).size());
        assertEquals(0, Api.json(target.api.get("/export/org-units")).size());
    }

    static ObjectNode withPerson() {
        ObjectNode document = JSON.createObjectNode().put("format", "fuhito.export.v1");
        named(document.putArray("people").addObject().put("id", "p-1")).putObject("attributes");
        return document;
    }

    static void assertBadValue(Api api, ObjectNode document, Map<String, String> params) throws Exception {
        HttpResponse<String> response = api.post("/restore", JSON.writeValueAsString(document));
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("restore.bad_value", error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(params), error.get("params"));
    }
}
