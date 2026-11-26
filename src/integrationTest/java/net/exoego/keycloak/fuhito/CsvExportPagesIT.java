package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.PartialImportRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExportPagesIT {

    // The export reads 1,000 rows at a time, so this many rows of each list take two pages.
    private static final int ROWS = 1001;

    @Test
    void anExportOfMoreThanOnePageOfEachListLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-pages");
        Api api = realm.api;
        String employer = realm.legalEntity("法人");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        realm.refItem("QUALIFICATION", "license", "免許");
        List<UserRepresentation> users = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            var user = new UserRepresentation();
            user.setUsername("user" + i);
            user.setFirstName("Given" + i);
            user.setLastName("Family" + i);
            user.setEnabled(true);
            users.add(user);
        }
        var partial = new PartialImportRepresentation();
        partial.setIfResourceExists("FAIL");
        partial.setUsers(users);
        try (Response response = KEYCLOAK.getKeycloakAdminClient().realm(realm.name).partialImport(partial)) {
            assertEquals(200, response.getStatus());
        }
        Map<String, List<Map<String, Object>>> lists = new LinkedHashMap<>();
        for (String list : List.of("orgUnits", "people", "employments", "assignments", "reportingLines", "unitRoles",
                "qualifications", "changeOrders")) {
            lists.put(list, new ArrayList<>());
        }
        for (int i = 0; i < ROWS; i++) {
            lists.get("orgUnits").add(Map.of("externalId", "U" + i, "primaryLanguage", "ja",
                    "names", Map.of("ja", Map.of("name", "病棟" + i)), "parentId", employer));
            // A person with no names shows the name of the Keycloak user.
            lists.get("people").add(Map.of("externalId", "P" + i, "username", "user" + i));
            lists.get("employments").add(Map.of("externalId", "E" + i, "personExternalId", "P" + i,
                    "staffNumber", "S" + i));
            lists.get("assignments").add(Map.of("externalId", "A" + i, "employmentExternalId", "E" + i,
                    "orgUnitExternalId", "U" + i, "primary", true));
            lists.get("reportingLines").add(Map.of("externalId", "L" + i, "assignmentExternalId", "A" + i,
                    "managerPersonExternalId", "P" + ((i + 1) % ROWS)));
            lists.get("unitRoles").add(Map.of("externalId", "R" + i, "employmentExternalId", "E" + i,
                    "orgUnitExternalId", "U" + i, "role", "approver"));
            lists.get("qualifications").add(Map.of("externalId", "Q" + i, "personExternalId", "P" + i,
                    "kind", "license", "number", "N" + i));
            lists.get("changeOrders").add(Map.of("externalId", "O" + i, "number", "2026-" + i, "title", "異動" + i,
                    "effectiveDate", "2026-04-01"));
        }
        Map<String, Object> document = new LinkedHashMap<>(lists);
        document.put("effectiveDate", "2026-04-01");
        document.put("source", "hr");
        HttpResponse<String> imported = api.post("/imports", new ObjectMapper().writeValueAsString(document));
        assertEquals(200, imported.statusCode(), imported.body());

        Map<String, String> countsOf = new LinkedHashMap<>();
        countsOf.put("org-units", "orgUnits");
        countsOf.put("people", "people");
        countsOf.put("employments", "employments");
        countsOf.put("assignments", "assignments");
        countsOf.put("reporting-lines", "reportingLines");
        countsOf.put("unit-roles", "unitRoles");
        countsOf.put("qualifications", "qualifications");
        countsOf.put("external-ids", "externalIds");
        countsOf.put("change-orders", "changeOrders");
        List<String> undated = List.of("people", "qualifications", "external-ids", "change-orders");
        for (Map.Entry<String, String> list : countsOf.entrySet()) {
            HttpResponse<String> exported = api.get("/imports/csv/" + list.getKey()
                    + (undated.contains(list.getKey()) ? "" : "?asOf=2026-05-01"));
            assertEquals(200, exported.statusCode(), list.getKey() + " " + exported.body());
            List<String> lines = exported.body().lines().toList();
            assertTrue(lines.size() - 1 >= ROWS, list.getKey() + " has " + (lines.size() - 1) + " rows");
            HttpResponse<String> again = api.postBytes("/imports/csv/" + list.getKey() + "?effectiveDate=2026-05-01",
                    "text/csv; charset=UTF-8", exported.body().getBytes(StandardCharsets.UTF_8));
            assertEquals(200, again.statusCode(), list.getKey() + " " + again.body());
            JsonNode report = Api.json(again);
            assertEquals(0, report.get("errors").size(), list.getKey() + " " + again.body());
            JsonNode counts = report.get("counts").get(list.getValue());
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(),
                    list.getKey() + " " + counts);
            if (list.getKey().equals("people")) {
                // The people are in the order of their IDs, so the last row is on the second page.
                String[] last = lines.getLast().split(",", -1);
                assertEquals(List.of("", ""), List.of(last[3], last[4]), lines.getLast());
                JsonNode person = Api.json(api.get("/people/" + last[0]));
                assertEquals("KEYCLOAK", person.get("nameSource").asText(), person.toString());
                assertEquals(String.join(" ", realm.nameOfUser(person.get("userId").asText())),
                        person.get("name").asText(), person.toString());
            }
        }
    }
}
