package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExternalIdsIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void aUnitHasTheIdsOfSeveralSystemsAndAMaskRemovesThoseOfAPerson() throws Exception {
        TestRealm realm = TestRealm.withEmployer("external-ids");
        Api api = realm.api;
        HttpResponse<String> unit = api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2020-04-01\", "
                + "\"externalId\": \"W-1\", \"source\": \"hr\"}");
        assertEquals(201, unit.statusCode(), unit.body());
        String ward = Api.json(unit).get("id").asText();

        ok(api.put("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"P-9\"}"));
        assertEquals(List.of("hr", "payroll"), ok(api.get("/external-ids?resourceType=ORG_UNIT&resourceId=" + ward))
                .findValuesAsText("source"));
        assertEquals(ward, ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=payroll&externalId=P-9"))
                .get("resourceId").asText());
        assertEquals(ward, ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=W-1"))
                .get("resourceId").asText());
        assertEquals("external_id.own_source", errorOf(409, api.put("/external-ids", "{\"resourceType\": "
                + "\"ORG_UNIT\", \"resourceId\": \"" + ward + "\", \"source\": \"hr\", \"externalId\": \"W-2\"}")));
        assertFalse(Api.json(api.get("/change-log?resourceType=FUHITO_EXTERNAL_ID")).isEmpty());

        // A person who left is masked, and the IDs of the person in other systems go away.
        String user = realm.user("former");
        HttpResponse<String> employment = api.post("/employments", "{\"userId\": \"" + user
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2021-04-01\"}");
        assertEquals(201, employment.statusCode(), employment.body());
        String person = Api.json(employment).get("personId").asText();
        ok(api.put("/external-ids", "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + person
                + "\", \"source\": \"payroll\", \"externalId\": \"E-77\"}"));
        assertEquals(200, api.post("/people/" + person + "/mask", "").statusCode());
        assertEquals(List.of(), ok(api.get("/external-ids?resourceType=PERSON&resourceId=" + person))
                .findValuesAsText("source"));
        assertEquals("external_id.unknown", errorOf(404, api.get(
                "/external-ids/lookup?resourceType=PERSON&source=payroll&externalId=E-77")));
        assertFalse(api.get("/change-log?resourceType=FUHITO_EXTERNAL_ID").body().contains("E-77"));

        assertEquals(204, api.delete("/external-ids?resourceType=ORG_UNIT&resourceId=" + ward + "&source=payroll")
                .statusCode());
    }
}
