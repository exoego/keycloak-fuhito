package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key that an import row leaves out keeps the stored value, and null clears it, in JSON and in CSV.
 */
class ImportNullsIT {

    private static final String WARD = """
            {"effectiveDate": "2026-04-01", "orgUnits": [
              {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "X病院"}}},
              {"externalId": "W", "primaryLanguage": "ja", "parentExternalId": "H", "code": "w1", "displayOrder": 2,
               "timeZone": "Asia/Tokyo", "names": {"ja": {"name": "1病棟", "shortName": "1病"}, "en": {"name": "Ward 1"}}}
            ]}
            """;

    @Test
    void aJsonRowKeepsWhatItLeavesOutAndClearsWhatIsNull() throws Exception {
        Api api = TestRealm.create("import-null").api;
        ok(api.post("/imports", WARD));
        String ward = Api.json(api.get("/org-units?asOf=2026-04-01&code=w1")).get(0).get("id").asText();

        JsonNode kept = ok(api.post("/imports", """
                {"effectiveDate": "2026-05-01", "orgUnits": [{"externalId": "W"}]}
                """));
        JsonNode cleared = ok(api.post("/imports", """
                {"effectiveDate": "2026-06-01", "orgUnits": [
                  {"externalId": "W", "code": null, "timeZone": null, "names": {"ja": {"shortName": null}, "en": null}}
                ]}
                """));
        HttpResponse<String> blank = api.withAcceptLanguage("ja").post("/imports", """
                {"effectiveDate": "2026-06-01", "orgUnits": [{"externalId": "W", "code": " "},
                  {"externalId": "H", "validTo": "2027-04-01", "successorIds": []}]}
                """);

        assertEquals(1, kept.get("counts").get("orgUnits").get("unchanged").asInt(), kept.toString());
        assertEquals(1, cleared.get("counts").get("orgUnits").get("changed").asInt(), cleared.toString());
        JsonNode june = Api.json(api.get("/org-units/" + ward + "?asOf=2026-06-01"));
        assertFalse(june.has("code"), june.toString());
        assertFalse(june.has("shortName"), june.toString());
        assertFalse(june.has("timeZone"), june.toString());
        assertEquals(1, june.get("names").size(), june.toString());
        assertTrue(june.get("names").has("ja"), june.toString());
        assertEquals(2, june.get("displayOrder").asInt());
        assertEquals("1病棟", june.get("name").asText());
        assertEquals(Api.json(api.get("/org-units?roots=true&asOf=2026-06-01")).get(0).get("id").asText(),
                june.get("parentId").asText());
        assertEquals(400, blank.statusCode(), blank.body());
        assertEquals(List.of("invalid.blank", "invalid.blank"),
                Api.json(blank).get("errors").findValuesAsText("error"));
        assertEquals(List.of("code", "successorIds"), Api.json(blank).get("errors").findValuesAsText("field"));
        assertEquals("code を空や空白だけにすることはできません。値がないときは null にしてください。",
                Api.json(blank).get("errors").get(0).get("message").asText());
    }

    @Test
    void aBlankCellKeepsTheStoredValueAndClearIsNull() throws Exception {
        Api api = TestRealm.create("import-null-csv").api;
        ok(api.post("/imports", WARD));
        String ward = Api.json(api.get("/org-units?asOf=2026-04-01&code=w1")).get(0).get("id").asText();

        HttpResponse<String> kept = csv(api, "2026-05-01", "externalId,code,shortName,timeZone\nW,,,\n");
        HttpResponse<String> cleared = csv(api, "2026-06-01", """
                externalId,code,shortName,name:en,timeZone
                W,#CLEAR,#CLEAR,#CLEAR,#CLEAR
                """);

        assertEquals(1, ok(kept).get("counts").get("orgUnits").get("unchanged").asInt(), kept.body());
        assertEquals(1, ok(cleared).get("counts").get("orgUnits").get("changed").asInt(), cleared.body());
        JsonNode june = Api.json(api.get("/org-units/" + ward + "?asOf=2026-06-01"));
        assertFalse(june.has("code"), june.toString());
        assertFalse(june.has("shortName"), june.toString());
        assertFalse(june.has("timeZone"), june.toString());
        assertEquals(1, june.get("names").size(), june.toString());
        assertEquals("1病棟", june.get("name").asText());
    }

    private static HttpResponse<String> csv(Api api, String effectiveDate, String file) throws Exception {
        return api.postBytes("/imports/csv/org-units?effectiveDate=" + effectiveDate, "text/csv; charset=UTF-8",
                file.getBytes(StandardCharsets.UTF_8));
    }
}
