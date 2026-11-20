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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreEmptyValuesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static void ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
    }

    private static String id(HttpResponse<String> response) throws Exception {
        ok(response);
        return Api.json(response).get("id").asText();
    }

    @Test
    void anEmptyValueInAnyRowFailsTheRestoreAndNothingIsWritten() throws Exception {
        TestRealm target = TestRealm.create("restore-blank");
        ObjectNode emptyText = withPerson();
        named(((ArrayNode) emptyText.get("people")).addObject().put("id", "p-2")).putObject("attributes")
                .put("note", "");
        ObjectNode spaces = withPerson();
        spaces.putArray("hierarchies").addObject().put("id", "h-1").put("code", "project").put("name", " ");
        ObjectNode emptyList = withPerson();
        ObjectNode unit = emptyList.putArray("orgUnits").addObject().put("id", "u-1");
        named(unit.putArray("versions").addObject().put("id", "v-1").put("validFrom", "2020-04-01"))
                .putObject("attributes").putArray("sites");
        unit.putArray("parents");
        unit.putArray("successors");

        assertBlank(target.api, emptyText, "people", "p-2", "attributes.note");
        assertBlank(target.api, spaces, "hierarchies", "h-1", "name");
        assertBlank(target.api, emptyList, "org-unit-versions", "v-1", "attributes.sites");

        // Not even the good person of each document was written.
        assertEquals(0, Api.json(target.api.get("/export/people")).size());
        assertEquals(0, Api.json(target.api.get("/export/org-units")).size());
        assertFalse(Api.json(target.api.get("/export/hierarchies")).findValuesAsText("code").contains("project"));
    }

    private static ObjectNode withPerson() {
        ObjectNode document = JSON.createObjectNode().put("format", "fuhito.export.v1");
        named(document.putArray("people").addObject().put("id", "p-1")).putObject("attributes");
        return document;
    }

    private static void assertBlank(Api api, ObjectNode document, String list, String id, String field)
            throws Exception {
        HttpResponse<String> response = api.post("/restore", JSON.writeValueAsString(document));
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("invalid.blank", error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(Map.of("list", list, "id", id, "field", field)), error.get("params"));
    }

    @Test
    void anExportOfCheckedDataRestoresUnchanged() throws Exception {
        TestRealm realm = TestRealm.withEmployer("restore-checked");
        Api api = realm.api;
        ok(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"wards\", \"type\": \"STRING\", "
                + "\"multiple\": true}"));
        ok(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"note\", \"type\": \"STRING\"}"));
        String hospital = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病院\", \"shortName\": \"病\"}}, \"attributes\": {\"wards\": [\"東\", \"西\"]}, "
                + "\"validFrom\": \"2020-04-01\"}"));
        String person = id(api.post("/people", "{\"userId\": \"" + realm.user("nurse") + "\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\", \"phoneticName\": "
                + "\"やまだ はなこ\"}}, \"attributes\": {\"note\": \"夜勤なし\"}}"));
        ok(api.post("/staff", "{\"personId\": \"" + person + "\", \"orgUnitId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}"));
        ObjectNode before = RealmExportIT.export(api);
        // An empty list of the rows of a row is not an empty value.
        assertTrue(before.get("orgUnits").findValues("parents").stream().anyMatch(JsonNode::isEmpty),
                before.toString());
        assertTrue(before.get("orgUnits").findValues("successors").stream().allMatch(JsonNode::isEmpty));

        ok(api.post("/restore?replace=true", JSON.writeValueAsString(before)));

        assertEquals(before, RealmExportIT.export(api));
    }
}
