package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    // Reads a resource, deletes it, and creates it again with the read as the body. The new one names the same
    // person. Returns the ID of the new one.
    private static String createdAgain(Api api, String list, String id) throws Exception {
        JsonNode read = ok(api.get(list + "/" + id));
        assertFalse(read.has("externalIds"), list);
        assertEquals(204, api.deleteWithVersion(list + "/" + id).statusCode(), list);
        JsonNode again = createdWithNoId(api, list, read.toString());
        for (String field : List.of("personId", "userId", "managerPersonId", "managerUserId")) {
            assertEquals(read.get(field), again.get(field), list + " " + field);
        }
        return again.get("id").asText();
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
        String nurse = realm.user("nurse");
        String ofUser = createdWithNoId(api, "/qualifications", "{\"userId\": \"" + nurse + "\", "
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
        // The read names the manager by the person and by the user, and a create takes both.
        createdAgain(api, "/reporting-lines", createdWithNoId(api, "/reporting-lines", "{\"assignmentId\": \""
                + assignment + "\", \"managerUserId\": \"" + realm.user("boss") + "\", \"validFrom\": \"2020-04-01\"}")
                .get("id").asText());
        createdAgain(api, "/unit-roles", createdWithNoId(api, "/unit-roles", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}")
                .get("id").asText());
        // A read of a person with a user names the person by both IDs, and a create takes both.
        createdAgain(api, "/employments", createdWithNoId(api, "/employments", "{\"userId\": \"" + nurse + "\", "
                + "\"validFrom\": \"2020-04-01\"}").get("id").asText());
        createdAgain(api, "/qualifications", ofUser);
    }

    private static JsonNode withoutVersion(JsonNode resource) {
        return ((ObjectNode) resource.deepCopy()).without("version");
    }

    // A read of each period has the fields that only a response fills, such as validTo. A write of it changes nothing.
    @Test
    void aReadOfEachPeriodIsTheBodyOfAChangeOfThatPeriod() throws Exception {
        TestRealm realm = TestRealm.withEmployer("read-period-as-body");
        Api api = realm.api;
        String unit = createdWithNoId(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}").get("id").asText();
        String ofUnit = "/org-units/" + unit;
        ok(api.putWithVersion(ofUnit, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"新病棟\"}}, "
                + "\"validFrom\": \"2025-04-01\"}"));
        String person = createdWithNoId(api, "/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"Nurse\"}}}").get("id").asText();
        String ofEmployment = "/employments/" + createdWithNoId(api, "/employments", "{\"personId\": \"" + person
                + "\", \"validFrom\": \"2020-04-01\"}").get("id").asText();
        ok(api.putWithVersion(ofEmployment + "/terms", "{\"validFrom\": \"2025-04-01\", \"workPercent\": 50}"));
        String ofAssignment = "/assignments/" + createdWithNoId(api, "/assignments", "{\"employmentId\": \""
                + ofEmployment.substring("/employments/".length()) + "\", \"orgUnitId\": \"" + unit + "\", "
                + "\"validFrom\": \"2020-04-01\"}").get("id").asText();
        ok(api.putWithVersion(ofAssignment + "/details", "{\"validFrom\": \"2025-04-01\", \"displayOrder\": 1}"));

        for (String asOf : List.of("2022-01-01", "2026-01-01")) {
            JsonNode read = ok(api.get(ofUnit + "?asOf=" + asOf));
            assertEquals(withoutVersion(read), withoutVersion(ok(api.put(ofUnit, read.toString()))), asOf);
        }
        String[][] periods = {{ofAssignment, "periods", "/details"}, {ofEmployment, "terms", "/terms"}};
        for (String[] resource : periods) {
            for (int i = 0; i < 2; i++) {
                JsonNode read = ok(api.get(resource[0]));
                ObjectNode body = ((ObjectNode) read.get(resource[1]).get(i).deepCopy())
                        .put("version", read.get("version").asLong());
                assertEquals(read.get(resource[1]), ok(api.put(resource[0] + resource[2], body.toString()))
                        .get(resource[1]), resource[0] + " " + i);
            }
        }

        // The response of a write of the settings or of the link of a unit is the body of the next one.
        JsonNode time = ok(api.putWithVersion(ofUnit + "/time-settings", "{\"timeZone\": \"Asia/Tokyo\"}"));
        assertEquals(withoutVersion(time), withoutVersion(ok(api.put(ofUnit + "/time-settings", time.toString()))));
        JsonNode link = ok(api.putWithVersion(ofUnit + "/organization", "{\"organizationId\": \""
                + realm.organization("read-period") + "\"}"));
        assertEquals(withoutVersion(link), withoutVersion(ok(api.put(ofUnit + "/organization", link.toString()))));
        // A read of an ID of another system is the body of a write of it.
        ok(api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + unit + "\", "
                + "\"source\": \"hr\", \"externalId\": \"U1\"}"));
        for (JsonNode id : ok(api.get("/external-ids?resourceType=ORG_UNIT&resourceId=" + unit))) {
            ok(api.put("/external-ids", id.toString()));
        }
    }
}
