package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aRealmAddsAKindAndAnAttributePointsToIt() throws Exception {
        TestRealm realm = TestRealm.create("ref-kinds");
        Api api = realm.api;
        JsonNode kind = created(api.post("/ref-kinds", "{\"code\": \"work-location\", \"name\": \"勤務地\"}"));
        assertEquals(false, kind.get("fixed").asBoolean());
        assertEquals("work-location", Api.json(api.get("/ref-kinds")).findValuesAsText("code").getLast());
        assertEquals("ref_kind.code_taken", errorOf(409, api.post("/ref-kinds",
                "{\"code\": \"GRADE\", \"name\": \"等級\"}")));

        created(api.post("/ref-items", "{\"kind\": \"work-location\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}"));
        HttpResponse<String> csv = api.postBytes("/imports/csv/ref-items?effectiveDate=2026-04-01",
                "text/csv; charset=UTF-8", "kind,code,primaryLanguage,name\nwork-location,osaka,ja,大阪\n".getBytes(StandardCharsets.UTF_8));
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
}
