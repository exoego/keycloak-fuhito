package net.exoego.keycloak.fuhito;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A text with NUL or with half of a surrogate pair fails with {@code invalid.character} in every write, because the
 * database cannot store it as sent. Other characters are stored as sent.
 */
class InvalidCharacterIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NUL = Character.toString(0);
    private static final String NAME = "{\"name\": \"n\"}";

    // A bad text as JSON writes it, and the code point that the error names.
    private record Bad(String json, String codePoint) {

        String quoted() {
            return "\"" + json + "\"";
        }
    }

    private static final List<Bad> BAD = List.of(new Bad("a\\u0000b", "U+0000"), new Bad("a\\ud800b", "U+D800"),
            new Bad("\\udc00", "U+DC00"));

    private static String unit(String names, String more) {
        return "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": " + names + "}, \"validFrom\": \"2020-04-01\""
                + more + "}";
    }

    private static void assertBad(HttpResponse<String> response, String field, String codePoint) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("invalid.character", error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(Map.of("field", field, "codePoint", codePoint)), error.get("params"));
    }

    @Test
    void aRestWriteFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("char-rest");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "note", "STRING");
        String user = realm.user("nurse");
        ObjectNode before = RealmExportIT.export(api);

        for (Bad bad : BAD) {
            String text = bad.quoted();
            assertBad(api.post("/org-units", unit("{\"name\": " + text + "}", "")), "names.ja.name",
                    bad.codePoint());
            assertBad(api.post("/org-units", unit(NAME, ", \"code\": " + text)), "code", bad.codePoint());
            assertBad(api.post("/org-units", unit(NAME, ", \"source\": \"hr\", \"externalId\": " + text)),
                    "externalId", bad.codePoint());
            assertBad(api.post("/org-units", unit(NAME, ", \"attributes\": {\"note\": " + text + "}")),
                    "attributes.note", bad.codePoint());
            assertBad(api.post("/change-orders", "{\"title\": \"t\", \"note\": " + text
                    + ", \"effectiveDate\": \"2026-04-01\"}"), "note", bad.codePoint());
            assertBad(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2026-04-01\", "
                    + "\"staffNumber\": " + text + "}"), "staffNumber", bad.codePoint());
        }
        // The JSON reader takes the bytes C0 80 as NUL.
        String[] around = unit("{\"name\": \"x?y\"}", "").split("\\?", -1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(around[0].getBytes(StandardCharsets.UTF_8));
        body.write(0xC0);
        body.write(0x80);
        body.writeBytes(around[1].getBytes(StandardCharsets.UTF_8));
        assertBad(api.postBytes("/org-units", "application/json", body.toByteArray()), "names.ja.name", "U+0000");
        HttpResponse<String> japanese = api.withAcceptLanguage("ja").post("/change-orders",
                "{\"title\": \"t\", \"note\": \"a\\u0000b\", \"effectiveDate\": \"2026-04-01\"}");

        assertEquals("note に保存できない文字 U+0000 があります。NUL (U+0000) と、サロゲートペアの片方だけの文字は"
                + "保存できません。", Api.json(japanese).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void anImportFailsEachBadRowAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("char-import");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "note", "STRING");
        realm.user("nurse");
        ObjectNode before = RealmExportIT.export(api);

        for (Bad bad : BAD) {
            HttpResponse<String> response = api.post("/imports", """
                    {"effectiveDate": "2026-04-01",
                     "changeOrders": [{"externalId": "c1", "title": "t", "note": %1$s}],
                     "orgUnits": [
                       {"externalId": "u1", "primaryLanguage": "ja", "names": {"ja": {"name": %1$s}}},
                       {"externalId": "u2", "code": %1$s, "primaryLanguage": "ja", "names": {"ja": {"name": "n"}}},
                       {"externalId": %1$s, "primaryLanguage": "ja", "names": {"ja": {"name": "n"}}},
                       {"externalId": "u4", "primaryLanguage": "ja", "names": {"ja": {"name": "n"}},
                        "attributes": {"note": %1$s}}],
                     "employments": [{"externalId": "e1", "username": "nurse", "staffNumber": %1$s}]}
                    """.formatted(bad.quoted()));

            assertEquals(400, response.statusCode(), response.body());
            assertEquals(List.of("changeOrders 0 note", "orgUnits 0 names.ja.name", "orgUnits 1 code",
                    "orgUnits 2 externalId", "orgUnits 3 attributes.note", "employments 0 staffNumber"),
                    rowErrors(response, bad.codePoint()));
        }
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aCsvCellWithNulFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("char-csv");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "note", "STRING");
        realm.user("nurse");
        ObjectNode before = RealmExportIT.export(api);
        String bad = "a" + NUL + "b";

        HttpResponse<String> units = csv(api, "org-units", "externalId,code,primaryLanguage,name,attributes.note\r\n"
                + "u1,,ja," + bad + ",\r\n"
                + "u2," + bad + ",ja,n,\r\n"
                // With no primary language, the file would look up the stored unit by the external ID.
                + bad + ",,,n,\r\n"
                + "u4,,ja,n," + bad + "\r\n");
        HttpResponse<String> orders = csv(api, "change-orders", "externalId,title,note\r\nc1,t," + bad + "\r\n");
        HttpResponse<String> employments = csv(api, "employments",
                "externalId,username,staffNumber\r\ne1,nurse," + bad + "\r\n");

        assertEquals(400, units.statusCode(), units.body());
        assertEquals(List.of("orgUnits 0 name", "orgUnits 1 code", "orgUnits 2 externalId",
                "orgUnits 3 attributes.note"), rowErrors(units, "U+0000"));
        assertEquals(400, orders.statusCode(), orders.body());
        assertEquals(List.of("changeOrders 0 note"), rowErrors(orders, "U+0000"));
        assertEquals(400, employments.statusCode(), employments.body());
        assertEquals(List.of("employments 0 staffNumber"), rowErrors(employments, "U+0000"));
        assertEquals(before, RealmExportIT.export(api));
    }

    private static HttpResponse<String> csv(Api api, String list, String file) throws Exception {
        return api.postBytes("/imports/csv/" + list + "?effectiveDate=2026-04-01", "text/csv; charset=UTF-8",
                file.getBytes(StandardCharsets.UTF_8));
    }

    // Each bad row as its list, its position, and its field. Every error is invalid.character with the code point.
    private static List<String> rowErrors(HttpResponse<String> response, String codePoint) throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode error : Api.json(response).get("errors")) {
            assertEquals("invalid.character", error.get("error").asText(), error.toString());
            assertEquals(codePoint, error.get("params").get("codePoint").asText(), error.toString());
            rows.add(error.get("list").asText() + " " + error.get("row").asInt() + " "
                    + error.get("params").get("field").asText());
        }
        return rows;
    }

    @Test
    void aRestoreFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("char-restore");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "note", "STRING");
        String user = realm.user("nurse");
        ok(api.post("/org-units", unit("{\"name\": \"PH-NAME\"}", ", \"code\": \"ph-code\", \"source\": \"hr\", "
                + "\"externalId\": \"PH-EXT\", \"attributes\": {\"note\": \"PH-NOTE\"}")));
        ok(api.post("/change-orders", "{\"title\": \"t\", \"note\": \"PH-ORDER\", \"effectiveDate\": \"2026-04-01\"}"));
        ok(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2026-04-01\", "
                + "\"staffNumber\": \"PH-STAFF\"}"));
        ObjectNode before = RealmExportIT.export(api);
        String document = JSON.writeValueAsString(before);
        // Each value of the export, with the list and the field of the row that holds it.
        Map<String, List<String>> values = Map.of("PH-NAME", List.of("org-unit-versions", "names.ja.name"),
                "ph-code", List.of("org-unit-versions", "code"), "PH-EXT", List.of("external-ids", "externalId"),
                "PH-NOTE", List.of("org-unit-versions", "attributes.note"),
                "PH-ORDER", List.of("change-orders", "note"), "PH-STAFF", List.of("employments", "staffNumber"));

        for (Bad bad : BAD) {
            for (Map.Entry<String, List<String>> value : values.entrySet()) {
                String placed = "\"" + value.getKey() + "\"";
                assertEquals(2, document.split(placed, -1).length, value.getKey());
                HttpResponse<String> response = api.post("/restore?replace=true",
                        document.replace(placed, bad.quoted()));

                assertEquals(400, response.statusCode(), response.body());
                JsonNode error = Api.json(response);
                assertEquals("invalid.character", error.get("error").asText(), response.body());
                assertEquals(JSON.valueToTree(Map.of("list", value.getValue().get(0),
                        "id", rowOf(before, value.getKey(), null), "field", value.getValue().get(1),
                        "codePoint", bad.codePoint())), error.get("params"));
            }
        }
        assertEquals(before, RealmExportIT.export(api));
    }

    // The ID of the row that holds a value, which is the nearest object around the value with an ID.
    private static String rowOf(JsonNode node, String value, String id) {
        if (node.isTextual()) {
            return node.asText().equals(value) ? id : null;
        }
        String own = node.has("id") ? node.get("id").asText() : id;
        for (JsonNode child : node) {
            String found = rowOf(child, value, own);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void ok(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
    }

    @Test
    void otherCharactersAreStoredAsSent() throws Exception {
        TestRealm realm = TestRealm.create("char-kept");
        Api api = realm.api;
        realm.attribute("ORG_UNIT", "note", "STRING");
        // Control characters, a line separator, emoji with a skin tone, a ZWJ sequence, a flag, a variation
        // selector, and a combining mark.
        String text = new String(new int[] {'a', 0x01, 0x1F, 0x7F, 0x2028, 0x1F600, 0x1F44D, 0x1F3FD, 0x1F468,
            0x200D, 0x1F469, 0x200D, 0x1F467, 0x1F1EF, 0x1F1F5, 0x2764, 0xFE0F, 0x304B, 0x3099}, 0, 19);
        ObjectNode body = JSON.createObjectNode().put("primaryLanguage", "ja").put("validFrom", "2020-04-01")
                .put("source", "hr").put("externalId", text);
        body.putObject("names").putObject("ja").put("name", text).put("shortName", text);
        body.putObject("attributes").put("note", text);
        HttpResponse<String> created = api.post("/org-units", JSON.writeValueAsString(body));
        HttpResponse<String> order = api.post("/change-orders", JSON.writeValueAsString(JSON.createObjectNode()
                .put("title", text).put("note", text).put("externalReference", text)
                .put("effectiveDate", "2026-04-01")));

        ok(created);
        ok(order);
        JsonNode unit = Api.json(api.get("/org-units/" + Api.json(created).get("id").asText()
                + "?asOf=2020-04-01"));
        assertEquals(List.of(text, text, text, "hr=" + text), List.of(unit.get("name").asText(),
                unit.get("shortName").asText(), unit.get("attributes").get("note").asText(),
                Api.externalIds(unit).getFirst()));
        JsonNode stored = Api.json(api.get("/change-orders/" + Api.json(order).get("id").asText()));
        assertEquals(List.of(text, text, text), List.of(stored.get("title").asText(), stored.get("note").asText(),
                stored.get("externalReference").asText()));
        ObjectNode export = RealmExportIT.export(api);
        HttpResponse<String> restored = api.post("/restore?replace=true", JSON.writeValueAsString(export));
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(export, RealmExportIT.export(api));
    }
}
