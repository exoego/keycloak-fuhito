package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReadAsBodyIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    // Creates a resource with no ID of any system, and returns it.
    private static JsonNode createdWithNoId(Api api, String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode resource = Api.json(response);
        assertFalse(resource.has("externalIds"), path);
        return resource;
    }

    // Reads a resource, deletes it, and creates it again with the read as the body. Returns the ID of the new one.
    private static String createdAgain(Api api, String list, String id) throws Exception {
        JsonNode read = ok(api.get(list + "/" + id));
        assertFalse(read.has("externalIds"), list);
        assertEquals(204, api.deleteWithVersion(list + "/" + id).statusCode(), list);
        return createdWithNoId(api, list, read.toString()).get("id").asText();
    }

    @Test
    void aReadOfAResourceWithNoIdIsTheBodyOfAWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("read-as-body");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String unit = createdWithNoId(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}").get("id").asText();
        String person = createdWithNoId(api, "/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"Nurse\"}}}").get("id").asText();
        createdWithNoId(api, "/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"拠点\"}}}");
        String item = createdWithNoId(api, "/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"primaryLanguage\": "
                + "\"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}").get("id").asText();
        String definition = createdWithNoId(api, "/attribute-definitions", "{\"target\": \"PERSON\", \"name\": "
                + "\"beds\", \"type\": \"NUMBER\"}").get("id").asText();
        String hierarchy = createdWithNoId(api, "/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}")
                .get("id").asText();
        String order = createdWithNoId(api, "/change-orders", "{\"title\": \"異動\", \"effectiveDate\": "
                + "\"2026-04-01\"}").get("id").asText();
        String qualification = createdWithNoId(api, "/qualifications", "{\"personId\": \"" + person + "\", \"kind\": "
                + "\"license\", \"validFrom\": \"2020-04-01\"}").get("id").asText();
        // A read of a qualification of a person with a user has both IDs.
        String ofUser = createdWithNoId(api, "/qualifications", "{\"userId\": \"" + realm.user("nurse") + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"}").get("id").asText();

        for (String path : List.of("/org-units/" + unit, "/people/" + person, "/ref-kinds/site", "/ref-items/" + item,
                "/attribute-definitions/" + definition, "/hierarchies/" + hierarchy, "/change-orders/" + order,
                "/qualifications/" + qualification, "/qualifications/" + ofUser)) {
            JsonNode before = ExternalIdsIT.read(api, path);
            assertFalse(before.has("externalIds"), path);
            assertFalse(ok(api.put(path, before.toString())).has("externalIds"), path);
        }
        assertFalse(ok(api.get("/org-units/" + unit + "/history")).has("externalIds"));
        assertFalse(ok(api.get("/ref-items/" + item + "/history")).has("externalIds"));

        // These resources have no replace, so a create takes the read after a delete.
        String employment = createdAgain(api, "/employments", createdWithNoId(api, "/employments", "{\"personId\": \""
                + person + "\", \"validFrom\": \"2020-04-01\"}").get("id").asText());
        String assignment = createdAgain(api, "/assignments", createdWithNoId(api, "/assignments", "{\"employmentId\": \""
                + employment + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"2020-04-01\"}").get("id").asText());
        createdAgain(api, "/reporting-lines", createdWithNoId(api, "/reporting-lines", "{\"assignmentId\": \""
                + assignment + "\", \"managerUserId\": \"" + realm.user("boss") + "\", \"validFrom\": \"2020-04-01\"}")
                .get("id").asText());
        createdAgain(api, "/unit-roles", createdWithNoId(api, "/unit-roles", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}")
                .get("id").asText());
    }
}
