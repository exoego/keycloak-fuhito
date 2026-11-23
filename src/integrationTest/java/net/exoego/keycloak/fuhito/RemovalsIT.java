package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemovalsIT {

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response);
    }

    @Test
    void aResourceMadeByMistakeGoesWhenNothingRefersToIt() throws Exception {
        TestRealm realm = TestRealm.withEmployer("removals");
        Api api = realm.api;
        String parent = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2026-04-01\"}"));
        String unit = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"誤り\"}}, \"parentId\": \"" + parent
                + "\", \"validFrom\": \"2026-04-01\", \"externalId\": \"X\", \"source\": \"hr\"}"));
        String employment = id(api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}"));
        String assignment = id(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}"));

        JsonNode used = errorOf(409, api.deleteWithVersion("/org-units/" + unit));
        assertEquals("org_unit.in_use", used.get("error").asText());
        assertEquals("assignments", used.get("params").get("uses").asText());
        assertEquals("org_unit.in_use", errorOf(409, api.deleteWithVersion("/org-units/" + parent)).get("error").asText());
        assertEquals("employment.in_use", errorOf(409, api.deleteWithVersion("/employments/" + employment)).get("error").asText());

        assertEquals(204, api.deleteWithVersion("/assignments/" + assignment).statusCode());
        assertEquals(204, api.deleteWithVersion("/employments/" + employment).statusCode());
        assertEquals(204, api.deleteWithVersion("/org-units/" + unit).statusCode());
        assertEquals(404, api.get("/assignments/" + assignment).statusCode());
        assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_assignment_version where id = '"
                + assignment + "'"));
        assertEquals(404, api.get("/employments/" + employment).statusCode());
        assertEquals(404, api.get("/org-units/" + unit).statusCode());
        assertEquals(List.of(), Api.json(api.get("/org-units/" + parent + "/children?asOf=2026-05-01"))
                .findValuesAsText("id"));
        assertEquals("org_unit.not_found", errorOf(404, api.deleteWithVersion("/org-units/" + unit)).get("error").asText());
        // The parent can go now, and the change log keeps the create and the delete.
        assertEquals(204, api.deleteWithVersion("/org-units/" + parent).statusCode());
        assertEquals(List.of("CREATE", "DELETE"), Api.json(api.get("/change-log?resourceType=FUHITO_ORG_UNIT&resourceId="
                + unit)).findValuesAsText("operation"));
    }
}
