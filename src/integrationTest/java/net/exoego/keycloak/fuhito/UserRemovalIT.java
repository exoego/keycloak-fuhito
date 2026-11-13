package net.exoego.keycloak.fuhito;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserRemovalIT {

    @Test
    void removingAUserDeletesTheirEmploymentsAndNoOthers() throws Exception {
        TestRealm realm = TestRealm.create("user-removal");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"name\": \"課\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String removed = realm.user("removed");
        String kept = realm.user("kept");
        List<String> removedIds = employ(api, removed, unit);
        List<String> keptIds = employ(api, kept, unit);
        String realmId = realm.id();
        assertEquals("4", rows("fuhito_employment", realmId));
        assertEquals("6", rows("fuhito_employment_terms", realmId));
        assertEquals("6", rows("fuhito_assignment", realmId));

        realm.removeUser(removed);

        assertEquals("2", rows("fuhito_employment", realmId));
        assertEquals("3", rows("fuhito_employment_terms", realmId));
        assertEquals("3", rows("fuhito_assignment", realmId));
        assertEquals("0", KeycloakEnvironment.sql(
                "select count(*) from fuhito_employment where user_id = '" + removed + "'"));
        for (String id : removedIds) {
            assertEquals(404, api.get("/employments/" + id).statusCode());
        }
        for (String id : keptIds) {
            assertEquals(200, api.get("/employments/" + id).statusCode());
        }
        // The org unit is not about the user, so it stays.
        assertEquals(200, api.get("/org-units/" + unit + "?asOf=2026-04-01").statusCode());
    }

    // Two employments. The first has two sets of terms and two assignments, one of them with two periods.
    private static List<String> employ(Api api, String userId, String unit) throws Exception {
        String first = Api.json(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2024-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/employments/" + first + "/terms",
                "{\"validFrom\": \"2022-04-01\", \"status\": \"ON_LEAVE\"}").statusCode());
        String second = Api.json(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        // The unit starts on 2026-04-01, so all assignments belong to the second employment.
        String assignment = Api.json(api.post("/assignments", "{\"employmentId\": \"" + second + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/assignments/" + assignment + "/details",
                "{\"validFrom\": \"2027-04-01\", \"head\": true}").statusCode());
        assertEquals(201, api.post("/assignments", "{\"employmentId\": \"" + second + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2026-10-01\"}").statusCode());
        return List.of(first, second);
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }
}
