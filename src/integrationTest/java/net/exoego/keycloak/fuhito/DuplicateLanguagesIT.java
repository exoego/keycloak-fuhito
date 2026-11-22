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
import static net.exoego.keycloak.fuhito.LanguageTagsIT.keys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateLanguagesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> TABLES = List.of("fuhito_org_unit", "fuhito_org_unit_version",
            "fuhito_org_unit_version_name", "fuhito_person", "fuhito_person_name", "fuhito_ref_item",
            "fuhito_ref_item_name", "fuhito_ref_kind", "fuhito_ref_kind_name", "fuhito_change_log");

    private static TestRealm realm;
    private static Api api;

    @FunctionalInterface
    interface Write {
        HttpResponse<String> send() throws Exception;
    }

    @BeforeAll
    static void createRealm() {
        realm = TestRealm.create("duplicate-languages");
        api = realm.api;
    }

    static List<String> counts(TestRealm of) throws Exception {
        List<String> counts = new ArrayList<>();
        for (String table : TABLES) {
            counts.add(table + "=" + rows(of, table));
        }
        return counts;
    }

    private static void assertDuplicate(String field, String language, Write write) throws Exception {
        assertFails(realm, "invalid.duplicate_language", Map.of("field", field, "value", language), write);
    }

    static void assertFails(TestRealm of, String code, Map<String, String> params, Write write)
            throws Exception {
        List<String> before = counts(of);
        HttpResponse<String> response = write.send();
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals(code, error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(params), error.get("params"));
        assertEquals(before, counts(of));
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    @Test
    void aRestWriteWithTwoTagsThatDifferOnlyInCaseFailsAndWritesNothing() throws Exception {
        assertDuplicate("names.JA", "JA", () -> api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": "
                + "{\"ja\": {\"name\": \"営業部\"}, \"JA\": {\"name\": \"販売部\"}}, "
                + "\"validFrom\": \"2026-04-01\"}"));
        assertDuplicate("names.en-US", "en-US", () -> api.post("/org-units", "{\"primaryLanguage\": \"en\", "
                + "\"names\": {\"en\": {\"name\": \"Sales\"}, \"en-us\": {\"name\": \"Sales\"}, "
                + "\"en-US\": {\"name\": \"Sales Division\"}}, \"validFrom\": \"2026-04-01\"}"));
        assertDuplicate("names.ja", "ja", () -> api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": "
                + "{\"JA\": {\"name\": \"山田\"}, \"ja\": {\"name\": \"山田 花子\"}}}"));
        assertDuplicate("names.JA", "JA", () -> api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": "
                + "\"ward\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}, "
                + "\"JA\": {\"name\": \"病室\"}}}"));
        assertDuplicate("names.JA", "JA", () -> api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": "
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
        assertDuplicate("names.JA", "JA", () -> api.putWithVersion("/people/" + person, "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"佐藤\"}, \"JA\": {\"name\": \"佐藤 一郎\"}}}"));
        assertDuplicate("names.JA", "JA", () -> api.putWithVersion("/ref-items/" + item, "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"部屋\"}, \"JA\": {\"name\": \"室\"}}}"));
        assertDuplicate("names.JA", "JA", () -> api.putWithVersion("/ref-kinds/area", "{\"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"地域\"}, \"JA\": {\"name\": \"エリア\"}}}"));

        assertEquals("人事部", Api.json(api.get("/org-units/" + unit + "?asOf=2027-04-01")).get("name").asText());
        assertEquals(1, Api.json(api.get("/people/" + person)).get("names").size());
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
        assertEquals(List.of("refKinds invalid.duplicate_language names.JA JA",
                "refItems invalid.duplicate_language names.en-US en-US",
                "orgUnits invalid.duplicate_language names.JA JA",
                "people invalid.duplicate_language names.ja ja"), errors);
        assertEquals(before, counts(realm));
    }

    private static JsonNode unitFound(String query, String asOf) throws Exception {
        JsonNode found = Api.json(api.get("/org-units?asOf=" + asOf + "&q=" + query));
        assertEquals(1, found.size(), found.toString());
        return Api.json(api.get("/org-units/" + found.get(0).get("id").asText() + "?asOf=" + asOf));
    }

    private static HttpResponse<String> csv(String list, String body) throws Exception {
        return csv(api, list, body);
    }

    private static HttpResponse<String> csv(Api to, String list, String body) throws Exception {
        return to.postBytes("/imports/csv/" + list + "?effectiveDate=2026-04-01", "text/csv; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static String idOf(Api to, String resourceType, String externalId) throws Exception {
        return ok(to.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
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

    // The columns with no language have the text of primaryLanguage.
    @Test
    void aCsvRowWithOneLanguageInTwoTextsFailsAndWritesNothing() throws Exception {
        List<String> before = counts(realm);
        assertEquals(List.of("line 2 invalid.duplicate_language name:JA JA",
                "line 3 invalid.duplicate_language shortName:ja ja", "line 4 invalid.duplicate_language 略称:JA JA",
                "line 5 invalid.duplicate_language name:en-US en-US"), rowErrors(csv("org-units", """
                        externalId,primaryLanguage,name,名称:ja,name:JA,shortName:ja,略称:JA,name:en-us,name:en-US
                        C1,en,Accounting,経理部,経理課,,,,
                        C2,en,Accounting,,経理部,経理,,,
                        C3,ja,経理部,,,,経理,,
                        C4,ja,経理部,,,,,Accounting,Accounts
                        """)));
        assertEquals(List.of("line 2 invalid.duplicate_language phoneticName:JA JA"), rowErrors(csv("people",
                "externalId,primaryLanguage,name,phoneticName:ja,phoneticName:JA\r\nP1,ja,高橋,たかはし,タカハシ\r\n")));
        assertEquals(before, counts(realm));

        // A file may have columns of one name in two cases, and a row fills one of them.
        ok(csv("org-units", """
                externalId,primaryLanguage,name,name:ja,name:JA,shortName:JA
                C5,en,Bookkeeping,簿記課,,
                C6,en,Planning,,企画部,企画
                """));
        assertEquals(List.of("en", "ja"), keys(unitFound("Bookkeeping", "2026-04-01").get("names")));
        JsonNode planning = unitFound("Planning", "2026-04-01").get("names");
        assertEquals(List.of("JA", "en"), keys(planning));
        assertEquals("企画", planning.get("JA").get("shortName").asText());
    }

    @Test
    void aCsvRowWithANameOfThePrimaryLanguageInBothOfItsColumnsFailsAndWritesNothing() throws Exception {
        TestRealm target = TestRealm.create("duplicate-languages-csv-row");
        Api to = target.api;
        ok(to.post("/imports", """
                {"effectiveDate": "2026-04-01",
                 "refKinds": [{"externalId": "K", "code": "floor", "primaryLanguage": "ja",
                   "names": {"ja": {"name": "階"}}}],
                 "refItems": [{"externalId": "I", "kind": "UNIT_TYPE", "code": "clinic", "primaryLanguage": "ja",
                   "names": {"ja": {"name": "診療所"}}}],
                 "orgUnits": [{"externalId": "U", "primaryLanguage": "ja",
                   "names": {"ja": {"name": "営業部", "shortName": "営業"}}}],
                 "people": [{"externalId": "P", "primaryLanguage": "ja", "names": {"ja": {"name": "田中"}}}]}
                """));
        List<String> before = counts(target);

        // A row of a stored thing with a blank primary language takes the stored one.
        assertEquals(List.of("line 2 invalid.duplicate_language 略称:JA JA",
                "line 3 invalid.duplicate_language 名称:ja ja", "line 4 invalid.duplicate_language よみがな:ja ja"),
                rowErrors(csv(to, "org-units", """
                        外部ID,主言語,名称,略称,よみがな,名称:ja,略称:JA,よみがな:ja,名称:en
                        U,,,営業課,,,営業,,
                        N1,ja,経理部,,,#CLEAR,,,Accounting
                        N2,ja,人事部,,#CLEAR,,,じんじぶ,
                        N3,en,Planning,,,企画部,,,
                        """)));
        assertEquals(List.of("line 2 invalid.duplicate_language Name:JA JA",
                "line 3 invalid.duplicate_language phoneticName:ja ja"), rowErrors(csv(to, "people", """
                        externalId,primaryLanguage,name,Name:JA,phoneticName,phoneticName:ja
                        P,,田中 太郎,田中,,
                        Q,ja,鈴木,,すずき,すずき
                        """)));
        assertEquals(List.of("line 2 invalid.duplicate_language name:ja ja"), rowErrors(csv(to, "ref-items", """
                externalId,kind,code,primaryLanguage,name,name:ja
                I,,,,医院,診療所
                """)));
        assertEquals(List.of("line 3 invalid.duplicate_language 名称:EN EN"), rowErrors(csv(to, "ref-kinds", """
                外部ID,コード,主言語,名称,名称:EN
                K,,,階層,
                K2,room,en,Room,Rooms
                """)));
        assertEquals(before, counts(target));
    }

    @Test
    void aCsvRowGivesANameOfThePrimaryLanguageInEitherColumnAndAnExportLoadsAgain() throws Exception {
        TestRealm target = TestRealm.create("duplicate-languages-csv-export");
        Api to = target.api;
        // Some records have ja and others JA. An export keeps each as stored.
        ok(csv(to, "org-units", """
                externalId,primaryLanguage,name,shortName,phoneticName,name:ja,shortName:ja,phoneticName:ja,name:en,name:JA
                A,ja,経理部,,,,経理,けいり,Accounting,
                B,en,,Planning,,企画部,,,Planning Division,
                C,en,Legal,,,,,,,法務部
                D,JA,総務部,,,,,,General Affairs,
                """));
        ok(csv(to, "people", """
                externalId,primaryLanguage,name,phoneticName,name:ja,phoneticName:ja,name:en,name:JA
                P,ja,,やまだ,山田,,Yamada,
                Q,en,Suzuki,,鈴木,すずき,,
                R,en,Sato,,,,,佐藤
                """));
        ok(csv(to, "ref-items", """
                externalId,kind,code,primaryLanguage,name,name:ja,name:en,name:JA
                I1,UNIT_TYPE,ward,ja,,病棟,Ward,
                I2,UNIT_TYPE,room,en,Room,部屋,,
                I3,UNIT_TYPE,lab,en,Lab,,,検査室
                """));
        ok(csv(to, "ref-kinds", """
                externalId,code,primaryLanguage,name,name:ja,name:en,name:JA
                K1,site,ja,拠点,,Site,
                K2,area,en,,地域,Area,
                K3,zone,en,Zone,,,区域
                """));
        // A blank cell keeps the stored name, and #CLEAR in either column removes it.
        ok(csv(to, "org-units", "externalId,shortName,phoneticName,shortName:ja,phoneticName:ja\nA,,#CLEAR,#CLEAR,\n"));

        JsonNode accounting = Api.json(to.get("/org-units/" + idOf(to, "ORG_UNIT", "A") + "?asOf=2026-04-01"))
                .get("names");
        assertEquals("経理部", accounting.get("ja").get("name").textValue());
        assertNull(accounting.get("ja").path("shortName").textValue());
        assertNull(accounting.get("ja").path("phoneticName").textValue());
        assertEquals("Accounting", accounting.get("en").get("name").textValue());
        JsonNode planning = Api.json(to.get("/org-units/" + idOf(to, "ORG_UNIT", "B") + "?asOf=2026-04-01"));
        assertEquals("en", planning.get("primaryLanguage").textValue());
        assertEquals("Planning Division", planning.get("names").get("en").get("name").textValue());
        assertEquals("Planning", planning.get("names").get("en").get("shortName").textValue());
        assertEquals("企画部", planning.get("names").get("ja").get("name").textValue());
        JsonNode yamada = Api.json(to.get("/people/" + idOf(to, "PERSON", "P"))).get("names");
        assertEquals("山田", yamada.get("ja").get("name").textValue());
        assertEquals("やまだ", yamada.get("ja").get("phoneticName").textValue());
        JsonNode kinds = Api.json(to.get("/ref-kinds"));
        JsonNode area = kinds.get(kinds.findValuesAsText("code").indexOf("area"));
        assertEquals("Area", area.get("name").textValue());
        assertEquals("地域", area.get("names").get("ja").get("name").textValue());
        JsonNode legal = Api.json(to.get("/org-units/" + idOf(to, "ORG_UNIT", "C") + "?asOf=2026-04-01"));
        assertEquals("法務部", legal.get("names").get("JA").get("name").textValue());
        assertEquals("JA", Api.json(to.get("/org-units/" + idOf(to, "ORG_UNIT", "D") + "?asOf=2026-04-01"))
                .get("primaryLanguage").textValue());

        for (String list : List.of("org-units", "people", "ref-items", "ref-kinds")) {
            boolean dated = list.startsWith("org") || list.equals("ref-items");
            HttpResponse<String> exported = to.get("/imports/csv/" + list + (dated ? "?asOf=2026-04-01" : ""));
            assertEquals(200, exported.statusCode(), exported.body());
            String header = exported.body().lines().findFirst().orElseThrow();
            assertTrue(header.contains(",name:JA") && header.contains(",name:ja"), header);
            JsonNode counts = ok(csv(to, list, exported.body())).get("counts");
            assertEquals(0, counts.findValues("created").stream().mapToInt(JsonNode::asInt).sum()
                    + counts.findValues("changed").stream().mapToInt(JsonNode::asInt).sum(),
                    list + " " + counts + "\n" + exported.body());
        }
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

        // A restore names the row.
        assertFails(target, "restore.bad_value", Map.of("list", "people", "id", "dup-lang-p", "field", "names.JA",
                "rule", "invalid.duplicate_language"),
                () -> target.api.post("/restore", JSON.writeValueAsString(person)));
        assertFails(target, "restore.bad_value", Map.of("list", "org-unit-versions", "id", "dup-lang-v", "field",
                "names.en-US", "rule", "invalid.duplicate_language"),
                () -> target.api.post("/restore", JSON.writeValueAsString(unit)));

        names.remove("ja");
        // primaryLanguage is the same text as a key, as in a write. A restore stores each tag as the document has it.
        assertFails(target, "restore.bad_value", Map.of("list", "people", "id", "dup-lang-p", "field",
                "primaryLanguage", "rule", "invalid.primary_language"),
                () -> target.api.post("/restore", JSON.writeValueAsString(person)));
        row.put("primaryLanguage", "JA");
        ok(target.api.post("/restore", JSON.writeValueAsString(person)));
        JsonNode restored = Api.json(target.api.get("/people/dup-lang-p"));
        assertEquals("JA", restored.get("primaryLanguage").asText());
        assertEquals("伊藤 次郎", restored.get("names").get("JA").get("name").asText());
    }
}
