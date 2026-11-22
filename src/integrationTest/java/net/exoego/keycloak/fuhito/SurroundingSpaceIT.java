package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.ImportIT.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A write stores a text as it is sent. A text with white space at its start or end fails, in REST, in a JSON import,
 * and in a CSV import. U+3000 is the space of Japanese text.
 */
class SurroundingSpaceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}";

    private static String json(String text) throws Exception {
        return JSON.writeValueAsString(text);
    }

    // The error and the field of a failed write.
    private static String failure(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        return error.get("error").asText() + " " + error.get("params").get("field").asText();
    }

    // The error and the field of each bad row of an import.
    private static List<String> rowFailures(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        return Api.json(response).get("errors").valueStream()
                .map(e -> e.get("list").asText() + "[" + e.get("row").asText() + "] " + e.get("error").asText() + " "
                        + e.get("params").get("field").asText())
                .toList();
    }

    private static HttpResponse<String> csv(Api api, String list, String csv) throws Exception {
        return api.postBytes("/imports/csv/" + list + "?effectiveDate=2026-04-01", "text/csv; charset=UTF-8",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRestWriteRejectsATextWithSpacesAroundItAndStoresATextAsSent() throws Exception {
        TestRealm realm = TestRealm.create("surrounding-space-rest");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"note\", \"type\": \"STRING\"}").statusCode());
        String unit = Api.json(api.post("/org-units", "{" + NAMES.formatted("内科") + ", \"validFrom\": "
                + "\"2020-04-01\"}")).get("id").asText();
        String order = "{\"title\": %s, \"effectiveDate\": \"2026-04-01\", \"externalReference\": %s}";
        String id = "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + unit + "\", \"source\": \"payroll\", "
                + "\"externalId\": %s}";
        String hierarchies = rows(realm, "fuhito_hierarchy");

        assertEquals("invalid.surrounding_space names.ja.name", failure(api.post("/org-units", "{"
                + NAMES.formatted("外科　") + ", \"validFrom\": \"2020-04-01\"}")));
        assertEquals("invalid.surrounding_space names.ja.phoneticName", failure(api.post("/people",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田\", \"phoneticName\": \" やまだ\"}}}")));
        assertEquals("invalid.surrounding_space name", failure(api.post("/hierarchies",
                "{\"code\": \"project\", \"name\": \"Project \"}")));
        assertEquals("invalid.surrounding_space title", failure(api.post("/change-orders",
                order.formatted(json("\t異動"), "null"))));
        assertEquals("invalid.surrounding_space externalReference", failure(api.post("/change-orders",
                order.formatted(json("異動"), json("DOC-9\n")))));
        assertEquals("invalid.surrounding_space timeZone", failure(api.putWithVersion(
                "/org-units/" + unit + "/time-settings", "{\"timeZone\": \" Asia/Tokyo\"}")));
        assertEquals("invalid.surrounding_space dayStart", failure(api.putWithVersion(
                "/org-units/" + unit + "/time-settings", "{\"dayStart\": \"04:00　\"}")));
        assertEquals("invalid.surrounding_space externalId", failure(api.putWithVersion("/external-ids",
                id.formatted(json("P-1 ")))));
        assertEquals("invalid.surrounding_space attributes.note", failure(api.post("/people", "{"
                + NAMES.formatted("山田") + ", \"attributes\": {\"note\": " + json("line 1\nline 2\n") + "}}")));
        assertEquals("1", rows(realm, "fuhito_org_unit"));
        assertEquals("0", rows(realm, "fuhito_person"));
        assertEquals(hierarchies, rows(realm, "fuhito_hierarchy"));
        assertEquals("0", rows(realm, "fuhito_change_order"));
        assertEquals("0", rows(realm, "fuhito_external_id"));

        // A space or a line break inside a text is kept.
        HttpResponse<String> created = api.post("/change-orders", order.formatted(json("4月の 異動"),
                json("line 1\nline 2")));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("4月の 異動", Api.json(created).get("title").asText());
        assertEquals("line 1\nline 2", Api.json(created).get("externalReference").asText());
        assertEquals("P 1", ok(api.putWithVersion("/external-ids", id.formatted(json("P 1"))))
                .get("externalId").asText());
    }

    @Test
    void aJsonImportRejectsARowWithATextWithSpacesAroundIt() throws Exception {
        TestRealm realm = TestRealm.create("surrounding-space-json");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"tags\", \"type\": \"STRING\", \"multiple\": true}")
                .statusCode());
        String document = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "orgUnits": [
                   {"externalId": "A", "primaryLanguage": "ja", "names": {"ja": {"name": "内科 "}}},
                   {"externalId": "B\\t", "primaryLanguage": "ja", "names": {"ja": {"name": "外科"}}},
                   {"externalId": "C", "primaryLanguage": "ja", "names": {"ja": {"name": "眼科"}},
                    "timeZone": "　Asia/Tokyo"}],
                 "people": [{"externalId": "P", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}},
                             "attributes": {"tags": ["night", "day\\n"]}}],
                 "changeOrders": [{"externalId": "O", "title": "異動", "externalReference": " DOC-9"}]}
                """;

        assertEquals(List.of("changeOrders[0] invalid.surrounding_space externalReference",
                "orgUnits[0] invalid.surrounding_space names.ja.name",
                "orgUnits[1] invalid.surrounding_space externalId",
                "orgUnits[2] invalid.surrounding_space timeZone",
                "people[0] invalid.surrounding_space attributes.tags"), rowFailures(api.post("/imports", document)));
        assertEquals("0", rows(realm, "fuhito_org_unit"));
        assertEquals("0", rows(realm, "fuhito_person"));
        assertEquals("0", rows(realm, "fuhito_change_order"));
    }

    @Test
    void aCsvImportReadsEachCellAndEachItemAsItIs() throws Exception {
        TestRealm realm = TestRealm.create("surrounding-space-csv");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions",
                "{\"target\": \"ORG_UNIT\", \"name\": \"tags\", \"type\": \"STRING\", \"multiple\": true}")
                .statusCode());

        HttpResponse<String> header = csv(api, "org-units", "externalId,primaryLanguage,name \r\nA,ja,内科\r\n");
        assertEquals(400, header.statusCode(), header.body());
        assertEquals(Map.of("error", "csv.bad_column", "column", "name "), Map.of(
                "error", Api.json(header).get("error").asText(),
                "column", Api.json(header).get("params").get("column").asText()));

        HttpResponse<String> cells = csv(api, "org-units", """
                externalId,primaryLanguage,name,timeZone,attributes.tags
                A,ja,内科 ,,
                B,ja,  ,,
                C,ja,眼科,Asia/Tokyo\t,
                D,ja,皮膚科,,night| day
                E,ja,歯科,,night| |day
                F ,ja,耳鼻科,,
                """);
        assertEquals(List.of("orgUnits[1] invalid.blank name", "orgUnits[4] invalid.blank attributes.tags",
                "orgUnits[0] invalid.surrounding_space names.ja.name", "orgUnits[2] invalid.surrounding_space timeZone",
                "orgUnits[3] invalid.surrounding_space attributes.tags",
                "orgUnits[5] invalid.surrounding_space externalId"), rowFailures(cells));
        assertEquals("0", rows(realm, "fuhito_org_unit"));

        ok(csv(api, "org-units", "externalId,primaryLanguage,name,attributes.tags\r\nA,ja,\"内 科\",night|day\r\n"));
        JsonNode unit = Api.json(api.get("/org-units?asOf=2026-04-01")).get(0);
        assertEquals("内 科", unit.get("name").asText());
        assertEquals(List.of("night", "day"), unit.get("attributes").get("tags").valueStream().map(JsonNode::asText)
                .toList());
    }
}
