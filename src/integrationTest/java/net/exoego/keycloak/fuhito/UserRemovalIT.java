package net.exoego.keycloak.fuhito;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserRemovalIT {

    // The server decides what today is. The container runs in UTC.
    private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();
    private static final String TOMORROW = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    private static final String YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();

    @Test
    void removingAUserKeepsTheirHistoryAndEndsWhatIsOpen() throws Exception {
        TestRealm realm = TestRealm.create("user-removal");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"name\": \"課\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String removed = realm.user("removed");
        String kept = realm.user("kept");
        List<String> removedIds = employ(api, removed, unit);
        List<String> keptIds = employ(api, kept, unit);
        String realmId = realm.id();
        String logRows = "select count(*) from fuhito_change_log where realm_id = '" + realmId + "'";
        int logBefore = Integer.parseInt(KeycloakEnvironment.sql(logRows));
        assertEquals(List.of(kept, removed).stream().sorted().toList(),
                Api.json(api.get("/org-units/" + unit + "/members")).findValuesAsText("userId").stream().distinct().sorted().toList());

        realm.removeUser(removed);

        // No row is gone.
        assertEquals("4", rows("fuhito_employment", realmId));
        assertEquals("6", rows("fuhito_employment_terms", realmId));
        assertEquals("6", rows("fuhito_assignment", realmId));
        // The history of the removed user is still readable.
        JsonNode history = Api.json(api.get("/users/" + removed + "/history"));
        assertEquals(2, history.get("employments").size());
        assertEquals("2024-04-01", history.get("employments").get(0).get("validTo").asText());
        assertEquals(TODAY, history.get("employments").get(1).get("validTo").asText());
        assertEquals(2, history.get("assignments").size());
        for (JsonNode assignment : history.get("assignments")) {
            assertEquals(TODAY, assignment.get("validTo").asText());
        }
        assertEquals(200, api.get("/employments/" + removedIds.get(1)).statusCode());
        // The removed user was a member yesterday, and is not one tomorrow.
        assertEquals(2, Api.json(api.get("/users/" + removed + "/assignments?asOf=" + YESTERDAY)).size());
        assertEquals(0, Api.json(api.get("/users/" + removed + "/assignments?asOf=" + TOMORROW)).size());
        assertEquals(List.of(kept), Api.json(api.get("/org-units/" + unit + "/members?asOf=" + TOMORROW))
                .findValuesAsText("userId").stream().distinct().toList());
        // The other user is untouched.
        assertFalse(Api.json(api.get("/employments/" + keptIds.get(1))).has("validTo"));

        // The change log keeps every entry, and has one more for the end of the open employment. No user did it.
        assertEquals(logBefore + 1, Integer.parseInt(KeycloakEnvironment.sql(logRows)));
        JsonNode log = Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + removedIds.get(1)));
        assertEquals(List.of("CREATE", "UPDATE"), log.findValuesAsText("operation"));
        assertFalse(log.get(1).has("actorUserId"));
        assertEquals("fuhito/employments/" + removedIds.get(1) + "/end", log.get(1).get("resourcePath").asText());
        assertEquals(TODAY, log.get(1).get("representation").get("validTo").asText());
    }

    @Test
    void erasingAUserRemovesEverythingAboutThemAndNothingElse() throws Exception {
        TestRealm realm = TestRealm.create("user-erase");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"name\": \"課\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        realm.refItem("QUALIFICATION", "license", "免許");
        String erased = realm.user("erased");
        String gone = realm.user("gone");
        String kept = realm.user("kept");
        List<String> erasedIds = employ(api, erased, unit);
        List<String> goneIds = employ(api, gone, unit);
        List<String> keptIds = employ(api, kept, unit);
        for (String user : List.of(erased, gone, kept)) {
            assertEquals(201, api.post("/qualifications", "{\"userId\": \"" + user
                    + "\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}").statusCode());
        }
        String realmId = realm.id();
        // One user is removed from Keycloak first. Their data can still be erased afterwards.
        realm.removeUser(gone);
        assertEquals("6", rows("fuhito_employment", realmId));

        assertEquals(204, api.delete("/users/" + erased + "/data").statusCode());
        assertEquals(204, api.delete("/users/" + gone + "/data").statusCode());

        assertEquals("2", rows("fuhito_employment", realmId));
        assertEquals("3", rows("fuhito_employment_terms", realmId));
        assertEquals("3", rows("fuhito_assignment", realmId));
        assertEquals("1", rows("fuhito_qualification", realmId));
        for (String id : List.of(erasedIds.get(0), erasedIds.get(1), goneIds.get(1))) {
            assertEquals(404, api.get("/employments/" + id).statusCode());
            assertEquals(0, Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + id)).size());
        }
        assertEquals(200, api.get("/employments/" + keptIds.get(1)).statusCode());
        assertEquals(2, Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + keptIds.get(0))).size());
        // The erased user still exists in Keycloak, with no data. The removed one is unknown now.
        assertEquals(0, Api.json(api.get("/users/" + erased + "/history")).get("employments").size());
        assertEquals(404, api.get("/users/" + gone + "/history").statusCode());
        assertEquals(404, api.delete("/users/" + gone + "/data").statusCode());
        assertEquals(404, api.delete("/users/no-such-user/data").statusCode());
        // The log says that data was erased, and nothing more about the person.
        JsonNode erasure = Api.json(api.get("/change-log?resourceType=FUHITO_USER_DATA&resourceId=" + erased));
        assertEquals(List.of("DELETE"), erasure.findValuesAsText("operation"));
        assertFalse(erasure.get(0).has("representation"));
        assertEquals(401, new Api(realm.name, null).delete("/users/" + kept + "/data").statusCode());
    }

    // Two employments. The first ended in 2024 and has two sets of terms.
    // The second is open and has two assignments, one of them with two periods.
    private static List<String> employ(Api api, String userId, String unit) throws Exception {
        String first = Api.json(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2024-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/employments/" + first + "/terms",
                "{\"validFrom\": \"2022-04-01\", \"status\": \"ON_LEAVE\"}").statusCode());
        String second = Api.json(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2025-04-01\"}")).get("id").asText();
        String assignment = Api.json(api.post("/assignments", "{\"employmentId\": \"" + second + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2025-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/assignments/" + assignment + "/details",
                "{\"validFrom\": \"2025-10-01\", \"head\": true}").statusCode());
        assertEquals(201, api.post("/assignments", "{\"employmentId\": \"" + second + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2025-10-01\"}").statusCode());
        return List.of(first, second);
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }
}
