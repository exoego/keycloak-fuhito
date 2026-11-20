package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.ImportIT.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DuplicateLanguagesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> TABLES = List.of("fuhito_org_unit", "fuhito_org_unit_version",
            "fuhito_org_unit_version_name", "fuhito_person", "fuhito_person_name", "fuhito_ref_item",
            "fuhito_ref_item_name", "fuhito_ref_kind", "fuhito_ref_kind_name", "fuhito_change_log");

    private static TestRealm realm;
    private static Api api;

    @FunctionalInterface
    private interface Write {
        HttpResponse<String> send() throws Exception;
    }

    @BeforeAll
    static void createRealm() {
        realm = TestRealm.create("duplicate-languages");
        api = realm.api;
    }

    private static List<String> counts(TestRealm of) throws Exception {
        List<String> counts = new ArrayList<>();
        for (String table : TABLES) {
            counts.add(table + "=" + rows(of, table));
        }
        return counts;
    }

    private static void assertDuplicate(String field, String language, Write write) throws Exception {
        assertDuplicate(realm, field, language, write);
    }

    private static void assertDuplicate(TestRealm of, String field, String language, Write write) throws Exception {
        List<String> before = counts(of);
        HttpResponse<String> response = write.send();
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("invalid.duplicate_language", error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(Map.of("field", field, "value", language)), error.get("params"));
        assertEquals(before, counts(of));
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    @Test
    void aRestWriteWithTwoTagsOfOneLanguageFailsAndWritesNothing() throws Exception {
        assertDuplicate("names.JA", "ja", () -> api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": "
                + "{\"ja\": {\"name\": \"営業部\"}, \"JA\": {\"name\": \"販売部\"}}, "
                + "\"validFrom\": \"2026-04-01\"}"));
        assertDuplicate("names.en-US", "en-US", () -> api.post("/org-units", "{\"primaryLanguage\": \"en\", "
                + "\"names\": {\"en\": {\"name\": \"Sales\"}, \"en-us\": {\"name\": \"Sales\"}, "
                + "\"en-US\": {\"name\": \"Sales Division\"}}, \"validFrom\": \"2026-04-01\"}"));
        assertDuplicate("names.ja", "ja", () -> api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": "
                + "{\"JA\": {\"name\": \"山田\"}, \"ja\": {\"name\": \"山田 花子\"}}}"));
        assertDuplicate("names.JA", "ja", () -> api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": "
                + "\"ward\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}, "
                + "\"JA\": {\"name\": \"病室\"}}}"));
        assertDuplicate("names.JA", "ja", () -> api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}, \"JA\": {\"name\": \"勤務地\"}}}"));

        String unit = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"人事部\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String person = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"佐藤\"}}}")).get("id").asText();
        String item = created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"room\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"部屋\"}}}")).get("id").asText();
        created(api.post("/ref-kinds", "{\"code\": \"area\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"地域\"}}}"));
        assertDuplicate("names.en-US", "en-US", () -> api.putWithVersion("/org-units/" + unit, "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"人事部\"}, \"en-us\": {\"name\": \"HR\"}, "
                + "\"en-US\": {\"name\": \"Human Resources\"}}, \"validFrom\": \"2027-04-01\"}"));
        assertDuplicate("names.JA", "ja", () -> api.putWithVersion("/people/" + person, "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"佐藤\"}, \"JA\": {\"name\": \"佐藤 一郎\"}}}"));
        assertDuplicate("names.JA", "ja", () -> api.putWithVersion("/ref-items/" + item, "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"部屋\"}, \"JA\": {\"name\": \"室\"}}}"));
        assertDuplicate("names.JA", "ja", () -> api.putWithVersion("/ref-kinds/area", "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"地域\"}, \"JA\": {\"name\": \"エリア\"}}}"));

        assertEquals("人事部", Api.json(api.get("/org-units/" + unit + "?asOf=2027-04-01")).get("name").asText());
        assertEquals(1, Api.json(api.get("/people/" + person)).get("names").size());
    }

    @Test
    void aSingleTagInAnyCaseStillWorks() throws Exception {
        JsonNode unit = created(api.post("/org-units", "{\"primaryLanguage\": \"JA\", \"names\": {\"ja\": "
                + "{\"name\": \"総務部\"}, \"en-us\": {\"name\": \"General Affairs\"}}, "
                + "\"validFrom\": \"2026-04-01\"}"));
        assertEquals("ja", unit.get("primaryLanguage").asText());
        assertEquals("General Affairs", unit.get("names").get("en-US").get("name").asText());
        JsonNode person = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"JA\": "
                + "{\"name\": \"鈴木\"}}}"));
        assertEquals("鈴木", person.get("names").get("ja").get("name").asText());
    }

    @Test
    void anImportRowWithTwoTagsOfOneLanguageFailsAndWritesNothing() throws Exception {
        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "orgUnits": [{"externalId": "H", "primaryLanguage": "ja",
                  "names": {"ja": {"name": "X病院"}, "en": {"name": "X Hospital"}}}]}
                """));
        List<String> before = counts(realm);

        HttpResponse<String> response = api.post("/imports", """
                {"effectiveDate": "2027-04-01",
                 "refKinds": [{"externalId": "K", "code": "floor", "primaryLanguage": "ja",
                   "names": {"ja": {"name": "階"}, "JA": {"name": "フロア"}}}],
                 "refItems": [{"externalId": "I", "kind": "UNIT_TYPE", "code": "clinic", "primaryLanguage": "en",
                   "names": {"en-us": {"name": "Clinic"}, "en-US": {"name": "Clinics"}, "en": {"name": "Clinic"}}}],
                 "orgUnits": [{"externalId": "H", "names": {"ja": {"name": "Y病院"}, "JA": null}}],
                 "people": [{"externalId": "P", "primaryLanguage": "ja",
                   "names": {"JA": {"name": "田中"}, "ja": {"name": "田中 太郎"}}}]}
                """);

        assertEquals(400, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertFalse(report.get("applied").asBoolean());
        List<String> errors = new ArrayList<>();
        for (JsonNode error : report.get("errors")) {
            errors.add(error.get("list").asText() + " " + error.get("error").asText() + " "
                    + error.get("params").path("field").asText() + " " + error.get("params").path("value").asText());
        }
        assertEquals(List.of("refKinds invalid.duplicate_language names.JA ja",
                "refItems invalid.duplicate_language names.en-US en-US",
                "orgUnits invalid.duplicate_language names.JA ja",
                "people invalid.duplicate_language names.ja ja"), errors);
        assertEquals(before, counts(realm));

        // One key of a language in any case still merges with the stored names.
        ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "orgUnits": [{"externalId": "H", "names": {"JA": {"name": "Y病院"}}}]}
                """));
        JsonNode hospital = unitFound("Hospital", "2027-04-01");
        assertEquals("Y病院", hospital.get("names").get("ja").get("name").asText());
        assertEquals("X Hospital", hospital.get("names").get("en").get("name").asText());
    }

    private static JsonNode unitFound(String query, String asOf) throws Exception {
        JsonNode found = Api.json(api.get("/org-units?asOf=" + asOf + "&q=" + query));
        assertEquals(1, found.size(), found.toString());
        return Api.json(api.get("/org-units/" + found.get(0).get("id").asText() + "?asOf=" + asOf));
    }

    private static HttpResponse<String> csv(String list, String body) throws Exception {
        return api.postBytes("/imports/csv/" + list + "?effectiveDate=2026-04-01", "text/csv; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCsvFileWithTwoColumnsOfOneNameInOneLanguageFailsAndWritesNothing() throws Exception {
        assertDuplicate("name:JA", "ja", () -> csv("org-units",
                "外部ID,主言語,名称:ja,name:JA\r\nC1,ja,経理部,経理課\r\n"));
        assertDuplicate("name:en-US", "en-US", () -> csv("org-units",
                "externalId,primaryLanguage,name,name:en-us,name:en-US\r\nC1,ja,経理部,Accounting,Accounts\r\n"));
        assertDuplicate("phoneticName:JA", "ja", () -> csv("people", "externalId,primaryLanguage,name,"
                + "phoneticName:ja,phoneticName:JA\r\nP1,ja,高橋,たかはし,タカハシ\r\n"));

        // Columns of other names may write the tag of one language in another case.
        ok(csv("org-units", """
                externalId,primaryLanguage,名称:JA,略称:ja,name:en-us
                C2,ja,経理部,経理,Accounting
                """));
        JsonNode unit = unitFound("Accounting", "2026-04-01");
        assertEquals("経理", unit.get("names").get("ja").get("shortName").asText());
        assertEquals("Accounting", unit.get("names").get("en-US").get("name").asText());
    }

    @Test
    void aRestoreWithTwoTagsOfOneLanguageFailsAndWritesNothing() throws Exception {
        TestRealm target = TestRealm.create("duplicate-languages-restore");
        ObjectNode person = JSON.createObjectNode().put("format", "fuhito.export.v1");
        ObjectNode row = person.putArray("people").addObject().put("id", "dup-lang-p").put("primaryLanguage", "ja");
        ObjectNode names = row.putObject("names");
        names.putObject("ja").put("name", "伊藤");
        names.putObject("JA").put("name", "伊藤 次郎");
        row.putObject("attributes");
        ObjectNode unit = JSON.createObjectNode().put("format", "fuhito.export.v1");
        ObjectNode stored = unit.putArray("orgUnits").addObject().put("id", "dup-lang-u");
        ObjectNode version = stored.putArray("versions").addObject().put("id", "dup-lang-v")
                .put("validFrom", "2026-04-01").put("primaryLanguage", "en");
        ObjectNode english = version.putObject("names");
        english.putObject("en-us").put("name", "Sales");
        english.putObject("en-US").put("name", "Sales Division");
        version.putObject("attributes");
        stored.putArray("parents");
        stored.putArray("successors");

        assertDuplicate(target, "names.JA", "ja", () -> target.api.post("/restore", JSON.writeValueAsString(person)));
        assertDuplicate(target, "names.en-US", "en-US",
                () -> target.api.post("/restore", JSON.writeValueAsString(unit)));

        names.remove("ja");
        ok(target.api.post("/restore", JSON.writeValueAsString(person)));
        JsonNode restored = Api.json(target.api.get("/people/dup-lang-p"));
        assertEquals("伊藤 次郎", restored.get("names").get("ja").get("name").asText());
    }
}
