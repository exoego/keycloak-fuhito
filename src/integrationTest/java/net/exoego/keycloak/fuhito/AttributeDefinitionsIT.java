package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeDefinitionsIT {

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void writesAreCheckedAgainstTheDefinitions() throws Exception {
        TestRealm realm = TestRealm.withEmployer("attributes");
        Api api = realm.api;
        JsonNode beds = created(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\", \"required\": true}"));
        assertEquals(List.of("beds"), Api.json(api.get("/attribute-definitions?target=ORG_UNIT")).findValuesAsText("name"));
        assertEquals(beds, Api.json(api.get("/attribute-definitions/" + beds.get("id").asText())));
        assertEquals(List.of("fuhito/attribute-definitions/" + beds.get("id").asText()), Api.json(api.get(
                "/change-log?resourceType=FUHITO_ATTRIBUTE_DEFINITION")).findValuesAsText("resourcePath"));

        assertEquals("attribute.required", errorOf(400, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2026-04-01\"}")));
        assertEquals("attribute.bad_value", errorOf(400, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2026-04-01\", \"attributes\": {\"beds\": \"many\"}}")));
        JsonNode ward = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2026-04-01\", "
                + "\"externalId\": \"W\", \"source\": \"import\", \"attributes\": {\"beds\": 30}}"));
        assertEquals(30, ward.get("attributes").get("beds").asInt());

        // A CSV column sets the defined attribute.
        HttpResponse<String> csv = api.postBytes("/imports/csv/org-units?effectiveDate=2026-05-01",
                "text/csv; charset=UTF-8", "externalId,name,attributes.beds\nW,1病棟,40\n"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(200, csv.statusCode(), csv.body());
        assertEquals(40, Api.json(api.get("/org-units/" + ward.get("id").asText() + "?asOf=2026-05-01"))
                .get("attributes").get("beds").asInt());
        // The same file again matches what is stored.
        HttpResponse<String> again = api.postBytes("/imports/csv/org-units?effectiveDate=2026-05-01",
                "text/csv; charset=UTF-8", "externalId,name,attributes.beds\nW,1病棟,40\n"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(1, Api.json(again).get("counts").get("orgUnits").get("unchanged").asInt(), again.body());

        assertEquals(200, api.putWithVersion("/attribute-definitions/" + beds.get("id").asText(),
                "{\"type\": \"NUMBER\", \"required\": false}").statusCode());
        assertEquals(201, api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"2病棟\"}}, \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(204, api.deleteWithVersion("/attribute-definitions/" + beds.get("id").asText()).statusCode());
    }

    @Test
    void attributesMayHave64KbAsJson() throws Exception {
        TestRealm realm = TestRealm.create("attributes-size");
        realm.attribute("PERSON", "note", "STRING");
        HttpResponse<String> large = realm.api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, \"attributes\": "
                + "{\"note\": \"" + "a".repeat(65_536) + "\"}}");

        assertEquals("attribute.too_large", errorOf(400, large));
        assertEquals(201, realm.api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, \"attributes\": {\"note\": \""
                + "a".repeat(65_000) + "\"}}").statusCode());
    }

    @Test
    void aWriteWithANameThatHasNoDefinitionFails() throws Exception {
        TestRealm realm = TestRealm.create("attributes-unknown");
        Api api = realm.api;
        created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"住所\", \"type\": \"STRING\"}"));

        assertEquals("attribute.unknown", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, "
                + "\"attributes\": {\"電話\": \"03\"}}")));
        assertEquals(201, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, \"attributes\": {\"住所\": \"東京都\"}}")
                .statusCode());
    }

    @Test
    void aListAttributeChecksEachElement() throws Exception {
        TestRealm realm = TestRealm.create("attributes-list");
        Api api = realm.api;
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("OCCUPATION", "clerk", "事務");
        JsonNode occupations = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", "
                + "\"name\": \"occupations\", \"type\": \"REF_ITEM\", \"refKind\": \"OCCUPATION\", \"multiple\": true, "
                + "\"required\": true}"));
        assertTrue(occupations.get("multiple").asBoolean());

        assertEquals("attribute.required", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, "
                + "\"attributes\": {\"occupations\": []}}")));
        assertEquals("attribute.bad_value", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, "
                + "\"attributes\": {\"occupations\": \"nurse\"}}")));
        assertEquals("attribute.bad_value", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, "
                + "\"attributes\": {\"occupations\": [\"nurse\", \"driver\"]}}")));
        JsonNode person = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, "
                + "\"attributes\": {\"occupations\": [\"nurse\", \"clerk\"]}}"));
        assertEquals(List.of("nurse", "clerk"), List.of(person.get("attributes").get("occupations").get(0).asText(),
                person.get("attributes").get("occupations").get(1).asText()));

        assertEquals(200, api.putWithVersion("/attribute-definitions/" + occupations.get("id").asText(), "{\"type\": "
                + "\"REF_ITEM\", \"refKind\": \"OCCUPATION\"}").statusCode());
        assertEquals("attribute.bad_value", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 太郎\"}}, "
                + "\"attributes\": {\"occupations\": [\"nurse\"]}}")));
    }

    @Test
    void anAttributeRejectsAnEmptyText() throws Exception {
        TestRealm realm = TestRealm.create("attributes-empty-item");
        Api api = realm.api;
        created(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"shifts\", "
                + "\"type\": \"STRING\", \"multiple\": true}"));
        realm.attribute("ORG_UNIT", "note", "STRING");
        String unit = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, "
                + "\"validFrom\": \"2026-04-01\", \"attributes\": %s}";

        HttpResponse<String> item = api.post("/org-units", unit.formatted("{\"shifts\": [\"night\", \"\"]}"));
        assertEquals("invalid.blank", errorOf(400, item));
        assertEquals("attributes.shifts", Api.json(item).get("params").get("field").asText());
        HttpResponse<String> imported = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "orgUnits": [
                  {"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "1病棟"}},
                   "attributes": {"shifts": ["night", ""]}}
                ]}
                """);
        assertEquals(400, imported.statusCode(), imported.body());
        assertEquals(List.of("invalid.blank"), Api.json(imported).get("errors").findValuesAsText("error"));
        for (String note : List.of("\"\"", "\" \"")) {
            HttpResponse<String> single = api.post("/org-units", unit.formatted("{\"note\": " + note + "}"));
            assertEquals("invalid.blank", errorOf(400, single));
            assertEquals("attributes.note", Api.json(single).get("params").get("field").asText());
        }

        JsonNode ward = created(api.post("/org-units", unit.formatted("{\"shifts\": [\"night\"], \"note\": \"x\"}")));
        String path = "/org-units/" + ward.get("id").asText();
        assertEquals("invalid.blank", errorOf(400, api.putWithVersion(path, unit.formatted("{\"note\": \"\"}"))));
        assertEquals("x", Api.json(api.get(path)).get("attributes").get("note").asText());
        assertEquals(200, api.putWithVersion(path, unit.formatted("{\"shifts\": [\"night\"]}")).statusCode());
        assertFalse(Api.json(api.get(path)).path("attributes").has("note"));
    }

    @Test
    void aRealmAddsAKindAndAnAttributePointsToIt() throws Exception {
        TestRealm realm = TestRealm.create("ref-kinds");
        Api api = realm.api;
        JsonNode kind = created(api.post("/ref-kinds", "{\"code\": \"work-location\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"勤務地\"}}}"));
        assertEquals(false, kind.get("fixed").asBoolean());
        assertEquals("work-location", Api.json(api.get("/ref-kinds")).findValuesAsText("code").getLast());
        assertEquals("ref_kind.code_taken", errorOf(409, api.post("/ref-kinds",
                "{\"code\": \"GRADE\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"等級\"}}}")));

        created(api.post("/ref-items", "{\"kind\": \"work-location\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}"));
        HttpResponse<String> csv = api.postBytes("/imports/csv/ref-items?effectiveDate=2026-04-01",
                "text/csv; charset=UTF-8", "externalId,kind,code,primaryLanguage,name\nOSAKA,work-location,osaka,ja,大阪\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, csv.statusCode(), csv.body());
        assertEquals(List.of("osaka", "tokyo"), Api.json(api.get("/ref-items?kind=work-location"))
                .findValuesAsText("code"));
        assertEquals("invalid.kind", errorOf(400, api.get("/ref-items?kind=cost-center")));

        created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"workLocation\", "
                + "\"type\": \"REF_ITEM\", \"refKind\": \"work-location\"}"));
        assertEquals("attribute.bad_value", errorOf(400, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, "
                + "\"attributes\": {\"workLocation\": \"nagoya\"}}")));
        assertEquals(201, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}, \"attributes\": {\"workLocation\": "
                + "\"tokyo\"}}").statusCode());

        assertEquals("ref_kind.in_use", errorOf(409, api.deleteWithVersion("/ref-kinds/work-location")));
    }

    @Test
    void aKindHasANameInEachLanguage() throws Exception {
        TestRealm realm = TestRealm.create("ref-kind-names");
        Api api = realm.api;
        JsonNode kind = created(api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"JA\", "
                + "\"names\": {\"JA\": {\"name\": \" 拠点 \"}, \"en-us\": {\"name\": \"Site\"}}}"));
        assertEquals("拠点", kind.get("name").asText());
        assertEquals("ja", kind.get("primaryLanguage").asText());
        assertEquals("拠点", kind.get("names").get("ja").get("name").asText());
        assertEquals("Site", kind.get("names").get("en-US").get("name").asText());
        assertEquals(kind, kindOf(api, "site"));

        assertEquals("Site", kindOf(api.withAcceptLanguage("en-US,ja;q=0.5"), "site").get("name").asText());
        assertEquals("拠点", kindOf(api.withAcceptLanguage("fr"), "site").get("name").asText());
        JsonNode fixed = kindOf(api, "UNIT_TYPE");
        assertFalse(fixed.has("name") || fixed.has("primaryLanguage") || fixed.has("names"), fixed.toString());

        // A write of the same names still counts up the version.
        JsonNode same = Api.json(api.withAcceptLanguage("en-US").putWithVersion("/ref-kinds/site",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}, \"en-US\": {\"name\": \"Site\"}}}"));
        assertEquals(2, same.get("version").asLong());
        assertEquals("Site", same.get("name").asText());
        JsonNode renamed = Api.json(api.putWithVersion("/ref-kinds/site",
                "{\"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"Location\"}}}"));
        assertEquals(3, renamed.get("version").asLong());
        assertEquals("Location", kindOf(api.withAcceptLanguage("ja"), "site").get("name").asText());
        assertEquals(1, renamed.get("names").size());

        assertEquals("invalid.primary_language", errorOf(400, api.post("/ref-kinds", "{\"code\": \"other\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"en\": {\"name\": \"Other\"}}}")));
        assertEquals("invalid.language_tag", errorOf(400, api.post("/ref-kinds", "{\"code\": \"other\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"他\"}, \"ja_JP\": {\"name\": \"他\"}}}")));
        HttpResponse<String> noName = api.post("/ref-kinds", "{\"code\": \"other\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {}}}");
        assertEquals("invalid.required", errorOf(400, noName));
        assertTrue(Api.json(noName).get("message").asText().contains("names.ja.name"), noName.body());
        assertEquals("invalid.required", errorOf(400, api.putWithVersion("/ref-kinds/site", "{\"names\": "
                + "{\"en\": {\"name\": \"Location\"}}}")));
    }

    private static JsonNode kindOf(Api api, String code) throws Exception {
        for (JsonNode kind : Api.json(api.get("/ref-kinds"))) {
            if (kind.get("code").asText().equals(code)) {
                return kind;
            }
        }
        throw new AssertionError(code + " is not a kind");
    }
}
