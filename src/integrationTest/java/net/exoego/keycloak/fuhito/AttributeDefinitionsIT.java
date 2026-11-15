package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(List.of("beds"), Api.json(api.get("/attribute-definitions?target=ORG_UNIT"))
                .findValuesAsText("name"));
        assertEquals(beds, Api.json(api.get("/attribute-definitions/" + beds.get("id").asText())));
        assertEquals(List.of("fuhito/attribute-definitions/" + beds.get("id").asText()), Api.json(api.get(
                "/change-log?resourceType=FUHITO_ATTRIBUTE_DEFINITION")).findValuesAsText("resourcePath"));

        assertEquals("attribute.required", errorOf(400, api.post("/org-units",
                "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("attribute.bad_value", errorOf(400, api.post("/org-units",
                "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\", \"attributes\": {\"beds\": \"many\"}}")));
        JsonNode ward = created(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\", "
                + "\"externalId\": \"W\", \"source\": \"import\", \"attributes\": {\"beds\": 30, \"note\": \"free\"}}"));
        assertEquals(30, ward.get("attributes").get("beds").asInt());

        // A CSV column sets the defined attribute.
        HttpResponse<String> csv = api.postBytes("/imports/csv/org-units?effectiveDate=2026-05-01",
                "text/csv; charset=UTF-8", "externalId,name,attributes.beds\nW,1病棟,40\n"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(200, csv.statusCode(), csv.body());
        assertEquals(40, Api.json(api.get("/org-units/" + ward.get("id").asText() + "?asOf=2026-05-01"))
                .get("attributes").get("beds").asInt());

        assertEquals(200, api.put("/attribute-definitions/" + beds.get("id").asText(),
                "{\"type\": \"NUMBER\", \"required\": false}").statusCode());
        assertEquals(201, api.post("/org-units", "{\"name\": \"2病棟\", \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(204, api.delete("/attribute-definitions/" + beds.get("id").asText()).statusCode());
    }

    @Test
    void attributesMayHave64KbAsJson() throws Exception {
        TestRealm realm = TestRealm.create("attributes-size");
        HttpResponse<String> large = realm.api.post("/people", "{\"name\": \"山田 花子\", \"attributes\": "
                + "{\"note\": \"" + "a".repeat(65_536) + "\"}}");

        assertEquals("attribute.too_large", errorOf(400, large));
        assertEquals(201, realm.api.post("/people", "{\"name\": \"山田 花子\", \"attributes\": {\"note\": \""
                + "a".repeat(65_000) + "\"}}").statusCode());
    }

    @Test
    void aRealmCanRejectAttributesWithNoDefinition() throws Exception {
        TestRealm realm = TestRealm.create("attributes-strict", Map.of("fuhito.attributes.unknown", "reject"));
        Api api = realm.api;
        created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"住所\", \"type\": \"STRING\"}"));

        assertEquals("attribute.unknown", errorOf(400, api.post("/people", "{\"name\": \"山田 花子\", "
                + "\"attributes\": {\"電話\": \"03\"}}")));
        assertEquals(201, api.post("/people", "{\"name\": \"山田 花子\", \"attributes\": {\"住所\": \"東京都\"}}")
                .statusCode());
    }
}
