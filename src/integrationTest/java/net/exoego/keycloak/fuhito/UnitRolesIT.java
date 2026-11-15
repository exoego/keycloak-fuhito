package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitRolesIT {

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    @Test
    void anHrContactOfADivisionCoversItsWardsUntilTheEmploymentEnds() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unit-roles");
        Api api = realm.api;
        realm.refItem("UNIT_ROLE", "hr-contact", "人事担当");
        String division = created(api.post("/org-units", "{\"name\": \"看護部\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String ward = created(api.post("/org-units", "{\"name\": \"1病棟\", \"parentId\": \"" + division
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String user = realm.user("hr-contact");
        String employment = created(api.post("/employments", "{\"userId\": \"" + user
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        JsonNode role = created(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + division + "\", \"role\": \"hr-contact\", \"includesDescendants\": true, "
                + "\"validFrom\": \"2026-04-01\"}"));
        String id = role.get("id").asText();
        String person = role.get("personId").asText();

        assertEquals(user, role.get("userId").asText());
        assertEquals(role, Api.json(api.get("/unit-roles/" + id)));
        assertEquals(List.of(id), Api.json(api.get("/org-units/" + ward + "/role-holders?asOf=2026-05-01"))
                .findValuesAsText("id"));
        assertEquals(List.of(id), Api.json(api.get("/users/" + user + "/unit-roles?asOf=2026-05-01"))
                .findValuesAsText("id"));
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String approver = created(api.post("/unit-roles", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + ward + "\", \"role\": \"approver\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        assertEquals(List.of(approver), Api.json(api.get("/org-units/" + ward
                + "/role-holders?role=approver&asOf=2026-05-01")).findValuesAsText("id"));
        HttpResponse<String> ended = api.put("/unit-roles/" + approver + "/end", "{\"validTo\": \"2026-06-01\"}");
        assertEquals(200, ended.statusCode(), ended.body());
        assertEquals("2026-06-01", Api.json(ended).get("validTo").asText());
        assertEquals(204, api.delete("/unit-roles/" + approver).statusCode());
        assertEquals(List.of(id), Api.json(api.get("/people/" + person + "/unit-roles?asOf=2026-05-01"))
                .findValuesAsText("id"));
        assertEquals(List.of("fuhito/unit-roles/" + id), Api.json(api.get(
                "/change-log?resourceType=FUHITO_UNIT_ROLE&resourceId=" + id)).findValuesAsText("resourcePath"));
        assertTrue(Api.json(api.get("/effective-changes?from=2026-04-01")).findValuesAsText("resourceType")
                .contains("FUHITO_UNIT_ROLE"));
        HttpResponse<String> unitEnd = api.put("/org-units/" + ward + "/end", "{\"validTo\": \"2026-09-01\"}");
        assertEquals(200, unitEnd.statusCode(), unitEnd.body());
        HttpResponse<String> divisionEnd = api.put("/org-units/" + division + "/end", "{\"validTo\": \"2026-10-01\"}");
        assertEquals("org_unit.has_unit_roles", Api.json(divisionEnd).get("error").asText());

        // The person leaves, and the role ends with the employment.
        assertEquals(200, api.put("/employments/" + employment + "/end", "{\"validTo\": \"2026-10-01\"}").statusCode());
        assertEquals("2026-10-01", Api.json(api.get("/unit-roles/" + id)).get("validTo").asText());
        assertEquals(List.of(), Api.json(api.get("/org-units/" + division + "/role-holders?asOf=2026-10-01"))
                .findValuesAsText("id"));

        // An erase removes the role and its entries in the change log.
        assertEquals(204, api.delete("/people/" + person + "/data").statusCode());
        assertEquals(404, api.get("/unit-roles/" + id).statusCode());
        assertEquals(0, Api.json(api.get("/change-log?resourceType=FUHITO_UNIT_ROLE&resourceId=" + id)).size());
    }
}
