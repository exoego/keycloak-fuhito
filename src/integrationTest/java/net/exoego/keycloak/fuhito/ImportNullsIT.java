package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    @Test
    void aCsvRowThatRemovesALanguageAndFillsAnotherNameOfItFailsInAnyOrderOfTheColumns() throws Exception {
        Api api = TestRealm.create("import-null-csv-language").api;
        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01",
                 "orgUnits": [
                   {"externalId": "U", "primaryLanguage": "ja",
                    "names": {"ja": {"name": "営業部", "shortName": "営業"}, "en": {"name": "Sales", "shortName": "S"}}},
                   {"externalId": "V", "primaryLanguage": "ja",
                    "names": {"ja": {"name": "経理部"}, "en": {"name": "Accounting"}}}],
                 "people": [
                   {"externalId": "P", "primaryLanguage": "ja",
                    "names": {"ja": {"name": "山田", "phoneticName": "やまだ"}, "en": {"name": "Yamada"}}},
                   {"externalId": "Q", "primaryLanguage": "ja", "names": {"ja": {"name": "鈴木"}}}]}
                """));
        String units = export(api, "org-units");
        String people = export(api, "people");

        // A blank primary language takes the stored one, whose names are also in the columns with no language.
        assertEquals(List.of("line 2 csv.clear_conflict shortName:en en", "line 3 csv.clear_conflict 略称 ja"),
                rowErrors(csv(api, "org-units", "2026-05-01", """
                        externalId,name:en,shortName:en,略称,名称:JA
                        U,#CLEAR,Sales Division,,
                        V,,,経理課,#CLEAR
                        """)));
        assertEquals(List.of("line 2 csv.clear_conflict shortName:en en", "line 3 csv.clear_conflict 略称 ja"),
                rowErrors(csv(api, "org-units", "2026-05-01", """
                        externalId,名称:JA,略称,shortName:en,name:en
                        U,,,Sales Division,#CLEAR
                        V,#CLEAR,経理課,,
                        """)));
        HttpResponse<String> person = api.withAcceptLanguage("ja").postBytes(
                "/imports/csv/people?effectiveDate=2026-05-01", "text/csv; charset=UTF-8", """
                        externalId,phoneticName:EN,name:en,name:ja,phoneticName
                        P,#CLEAR,#CLEAR,,
                        Q,,,#CLEAR,すずき
                        """.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("line 2 csv.clear_conflict phoneticName:EN en",
                "line 3 csv.clear_conflict phoneticName ja"), rowErrors(person));
        assertEquals("行が名称の列の #CLEAR で言語「en」を削除していますが、phoneticName:EN にもその言語のセルがあります。"
                + "どちらか一方にしてください。", Api.json(person).get("errors").get(0).get("message").asText());
        assertEquals(units, export(api, "org-units"));
        assertEquals(people, export(api, "people"));

        // #CLEAR of a language alone removes it, and a name alone sets it.
        ok(csv(api, "org-units", "2026-05-01", """
                externalId,name:en,shortName:en,shortName
                U,#CLEAR,,営業課
                V,,Acct,
                """));
        ok(csv(api, "people", "2026-05-01", "externalId,name:en,phoneticName:en\nP,#CLEAR,\n"));
        JsonNode sales = Api.json(api.get("/org-units/" + idOf(api, "ORG_UNIT", "U") + "?asOf=2026-05-01"))
                .get("names");
        assertEquals(1, sales.size(), sales.toString());
        assertEquals("営業課", sales.get("ja").get("shortName").asText());
        JsonNode accounting = Api.json(api.get("/org-units/" + idOf(api, "ORG_UNIT", "V") + "?asOf=2026-05-01"))
                .get("names");
        assertEquals("Accounting", accounting.get("en").get("name").asText());
        assertEquals("Acct", accounting.get("en").get("shortName").asText());
        JsonNode yamada = Api.json(api.get("/people/" + idOf(api, "PERSON", "P"))).get("names");
        assertEquals(1, yamada.size(), yamada.toString());
        assertEquals("やまだ", yamada.get("ja").get("phoneticName").asText());

        for (String list : List.of("org-units", "people")) {
            JsonNode counts = ok(csv(api, list, "2026-05-01", export(api, list))).get("counts");
            assertEquals(0, counts.findValues("created").stream().mapToInt(JsonNode::asInt).sum()
                    + counts.findValues("changed").stream().mapToInt(JsonNode::asInt).sum(), list + " " + counts);
        }
    }

    private static String export(Api api, String list) throws Exception {
        HttpResponse<String> exported = api.get("/imports/csv/" + list + "?asOf=2026-05-01");
        assertEquals(200, exported.statusCode(), exported.body());
        return exported.body();
    }

    private static String idOf(Api api, String resourceType, String externalId) throws Exception {
        return ok(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    private static List<String> rowErrors(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertFalse(report.get("applied").asBoolean());
        List<String> errors = new ArrayList<>();
        for (JsonNode error : report.get("errors")) {
            errors.add("line " + error.get("line").asText() + " " + error.get("error").asText() + " "
                    + error.get("params").path("field").asText() + " " + error.get("params").path("value").asText());
        }
        return errors;
    }

    private static HttpResponse<String> csv(Api api, String effectiveDate, String file) throws Exception {
        return csv(api, "org-units", effectiveDate, file);
    }

    private static HttpResponse<String> csv(Api api, String list, String effectiveDate, String file)
            throws Exception {
        return api.postBytes("/imports/csv/" + list + "?effectiveDate=" + effectiveDate, "text/csv; charset=UTF-8",
                file.getBytes(StandardCharsets.UTF_8));
    }
}
