package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitRolesIT {

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String error(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void listsTheRolesOfADateAcrossTheRealmInPagesByIdWithTheirNames() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unit-roles-list");
        Api api = realm.api;
        realm.refItem("UNIT_ROLE", "safety-officer", "安全管理者");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String division = created(api.post("/org-units", "{\"name\": \"看護部\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String ward = created(api.post("/org-units", "{\"name\": \"1病棟\", \"parentId\": \"" + division
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        List<String> ids = new ArrayList<>();
        List<String> roles = List.of("safety-officer", "safety-officer", "approver");
        List<String> units = List.of(division, ward, ward);
        List<String> starts = List.of("2026-04-01", "2026-04-01", "2026-05-01");
        for (int i = 0; i < 3; i++) {
            String employment = created(api.post("/employments", "{\"userId\": \"" + realm.user("holder-" + i)
                    + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
            ids.add(created(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                    + units.get(i) + "\", \"role\": \"" + roles.get(i) + "\", \"includesDescendants\": " + (i == 0)
                    + ", \"validFrom\": \"" + starts.get(i) + "\"}")).get("id").asText());
        }
        String on = "asOf=2026-05-01";

        JsonNode every = Api.json(api.get("/unit-roles?" + on));
        assertEquals(ids.stream().sorted().toList(), every.findValuesAsText("id"));
        JsonNode approver = every.get(every.findValuesAsText("id").indexOf(ids.get(2)));
        JsonNode stored = Api.json(api.get("/unit-roles/" + ids.get(2)));
        for (String field : List.of("employmentId", "personId", "userId", "orgUnitId", "role", "validFrom")) {
            assertEquals(stored.get(field), approver.get(field), field);
        }
        assertEquals("承認者", approver.get("roleName").asText());
        assertEquals("1病棟", approver.get("orgUnitName").asText());
        assertFalse(approver.get("includesDescendants").asBoolean());
        assertFalse(approver.has("validTo"));
        // The default is today of the realm.
        assertEquals(ids.stream().sorted().toList(), Api.json(api.get("/unit-roles")).findValuesAsText("id"));
        assertEquals(ids.subList(0, 2).stream().sorted().toList(),
                Api.json(api.get("/unit-roles?asOf=2026-04-30")).findValuesAsText("id"));

        assertEquals(ids.subList(0, 2).stream().sorted().toList(),
                Api.json(api.get("/unit-roles?role=safety-officer&" + on)).findValuesAsText("id"));
        // The safety officer of the division covers the ward, but is over the division.
        assertEquals(ids.subList(1, 3).stream().sorted().toList(),
                Api.json(api.get("/unit-roles?orgUnitId=" + ward + "&" + on)).findValuesAsText("id"));
        assertEquals(List.of(ids.get(2)),
                Api.json(api.get("/unit-roles?role=approver&orgUnitId=" + ward + "&" + on)).findValuesAsText("id"));
        assertEquals(2, api.count("/unit-roles/count?role=safety-officer&" + on));
        assertEquals(1, api.count("/unit-roles/count?role=approver&orgUnitId=" + ward + "&" + on));
        assertEquals(2, api.count("/unit-roles/count?asOf=2026-04-30"));

        List<String> followed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String path = "/unit-roles?max=2&" + on;
        for (int first = 0; path != null; first += 2) {
            HttpResponse<String> page = api.get(path);
            assertEquals(200, page.statusCode(), page.body());
            followed.addAll(Api.json(page).findValuesAsText("id"));
            skipped.addAll(Api.json(api.get("/unit-roles?max=2&first=" + first + "&" + on)).findValuesAsText("id"));
            path = page.headers().firstValue("Link")
                    .map(link -> link.substring(link.indexOf("/fuhito") + "/fuhito".length(), link.indexOf('>')))
                    .orElse(null);
        }
        assertEquals(every.findValuesAsText("id"), followed);
        assertEquals(followed, skipped);

        assertEquals("ref_item.unknown", error(400, api.get("/unit-roles?role=nurse")));
        assertEquals("ref_item.unknown", error(400, api.get("/unit-roles/count?role=nurse")));
        assertEquals("invalid.cursor_with_first", error(400, api.get("/unit-roles?first=1&after=unit-roles.v1__"
                + ids.getFirst())));
        assertEquals("invalid.cursor", error(400, api.get("/unit-roles?after=people.v1__" + ids.getFirst())));
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
        String wardContact = created(api.post("/unit-roles", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + ward + "\", \"role\": \"hr-contact\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        assertEquals(List.of(wardContact, id), Api.json(api.get("/org-units/" + ward
                + "/role-holders?asOf=2026-05-01")).findValuesAsText("id"));
        // The HR contact of the ward hides the one of the division when only the nearest are asked for.
        assertEquals(List.of(wardContact), Api.json(api.get("/org-units/" + ward
                + "/role-holders?nearest=true&asOf=2026-05-01")).findValuesAsText("id"));
        assertEquals(List.of(wardContact), Api.json(api.get("/org-units/" + ward
                + "/role-holders?role=hr-contact&nearest=true&asOf=2026-05-01")).findValuesAsText("id"));
        assertEquals(2, api.count("/org-units/" + ward + "/role-holders/count?asOf=2026-05-01"));
        assertEquals(1, api.count("/org-units/" + ward + "/role-holders/count?nearest=true&asOf=2026-05-01"));
        assertEquals(1, api.count("/org-units/" + ward
                + "/role-holders/count?role=hr-contact&nearest=true&asOf=2026-05-01"));
        assertEquals(1, api.count("/org-units/" + division + "/role-holders/count?role=hr-contact&asOf=2026-05-01"));
        assertEquals(0, api.count("/org-units/" + ward + "/role-holders/count?role=approver&asOf=2026-05-01"));
        assertEquals("ref_item.unknown", Api.json(api.get("/org-units/" + ward
                + "/role-holders/count?role=nurse")).get("error").asText());
        assertEquals(404, api.get("/org-units/none/role-holders/count").statusCode());
        assertEquals(2, api.count("/people/" + person + "/unit-roles/count?asOf=2026-05-01"));
        assertEquals(2, api.count("/users/" + user + "/unit-roles/count?asOf=2026-05-01"));
        assertEquals(0, api.count("/users/" + user + "/unit-roles/count?asOf=2026-03-31"));
        assertEquals(204, api.delete("/unit-roles/" + wardContact).statusCode());
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
        // The user is still in Keycloak, but has no person and so no role.
        assertEquals(0, api.count("/users/" + user + "/unit-roles/count"));
        assertEquals(404, api.get("/users/nobody/unit-roles/count").statusCode());
        assertEquals(404, api.get("/people/" + person + "/unit-roles/count").statusCode());
        assertEquals(0, Api.json(api.get("/change-log?resourceType=FUHITO_UNIT_ROLE&resourceId=" + id)).size());
    }
}
