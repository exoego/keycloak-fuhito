package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version of an assignment is in a row of its own. The rows of the periods of the assignment have none.
 */
class AssignmentVersionIT {

    private static final String UNIT = """
            {"primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validFrom": "2020-04-01"}""";

    private static final String PERSON = """
            {"primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}}""";

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return Api.json(response);
    }

    // A read that forgot the row of the version would give another version.
    @Test
    void everyReadOfAnAssignmentGivesTheVersionOfItsOwnRow() throws Exception {
        Api api = TestRealm.withEmployer("assignment-version-reads").api;
        String unit = ok(api.post("/org-units", UNIT)).get("id").asText();
        String person = ok(api.post("/people", PERSON)).get("id").asText();
        String assignment = ok(api.post("/staff", """
                {"personId": "%s", "orgUnitId": "%s", "validFrom": "2020-04-01"}""".formatted(person, unit)))
                .get("assignment").get("id").asText();
        KeycloakEnvironment.sql("update fuhito_assignment_version set version = 41 where id = '" + assignment + "'");

        for (String read : List.of("/assignments/" + assignment, "/assignments?orgUnitId=" + unit,
                "/people/" + person + "/assignments", "/org-units/" + unit + "/members", "/export/assignments")) {
            assertEquals(List.of("41"), ok(api.get(read)).findValuesAsText("version"), read);
        }
        assertEquals(List.of("41"), ok(api.get("/people/" + person + "/history")).get("assignments")
                .findValuesAsText("version"));
    }

    // A row of the version left behind would give a later assignment with the same ID the old version.
    @Test
    void anAssignmentWhoseLastRowGoesTakesTheRowOfItsVersionWithIt() throws Exception {
        Api api = TestRealm.withEmployer("assignment-version-gone").api;
        String unit = ok(api.post("/org-units", UNIT)).get("id").asText();
        String person = ok(api.post("/people", PERSON)).get("id").asText();
        String employment = ok(api.post("/employments", """
                {"personId": "%s", "validFrom": "2020-04-01"}""".formatted(person))).get("id").asText();
        String planned = ok(api.post("/assignments", """
                {"employmentId": "%s", "orgUnitId": "%s", "validFrom": "2027-04-01"}""".formatted(employment, unit)))
                .get("id").asText();
        assertEquals(1, KeycloakEnvironment.versionRows(planned));

        // The employment ends before the planned assignment starts, so the assignment goes away.
        ok(api.putWithVersion("/employments/" + employment + "/end", "{\"validTo\": \"2026-04-01\"}"));

        assertEquals(404, api.get("/assignments/" + planned).statusCode());
        assertEquals(0, KeycloakEnvironment.versionRows(planned));
    }
}
