package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A realm in UTC with a branch in a time zone that is on another date.
 */
class UnitTimeIT {

    private static String unit(TestRealm realm, String name, String parentId, LocalDate validFrom) throws Exception {
        HttpResponse<String> response = realm.api.post("/org-units", "{\"name\": \"" + name + "\", \"validFrom\": \""
                + validFrom + "\"" + (parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"") + "}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static int count(TestRealm realm, String path) throws Exception {
        HttpResponse<String> response = realm.api.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).size();
    }

    @Test
    void aBranchInAnotherTimeZoneIsOnItsOwnDate() throws Exception {
        String zone = RealmTimeIT.zoneOnAnotherDate();
        TestRealm realm = TestRealm.create("unit-time");
        LocalDate realmToday = LocalDate.now(ZoneOffset.UTC);
        LocalDate branchToday = LocalDate.now(ZoneId.of(zone));
        assertNotEquals(realmToday, branchToday);
        LocalDate earlier = realmToday.isBefore(branchToday) ? realmToday : branchToday;
        LocalDate later = realmToday.isBefore(branchToday) ? branchToday : realmToday;
        LocalDate past = earlier.minusYears(1);
        String head = unit(realm, "Head office", null, past);
        String branch = unit(realm, "Branch", null, past);
        String branchTeam = unit(realm, "Branch team", branch, past);
        // These exist only for the place where the later date already started.
        unit(realm, "New in head office", head, later);
        unit(realm, "New in branch", branchTeam, later);
        String headUser = realm.user("head-user");
        String branchUser = realm.user("branch-user");
        for (String[] staff : new String[][] {{headUser, head}, {branchUser, branchTeam}}) {
            assertEquals(201, realm.api.post("/staff", "{\"userId\": \"" + staff[0] + "\", \"orgUnitId\": \""
                    + staff[1] + "\", \"validFrom\": \"" + later + "\"}").statusCode());
        }
        int headSees = realmToday.equals(later) ? 1 : 0;
        int branchSees = branchToday.equals(later) ? 1 : 0;
        assertEquals(headSees, count(realm, "/org-units/" + branchTeam + "/children"));

        HttpResponse<String> set = realm.api.put("/org-units/" + branch + "/time-settings",
                "{\"timeZone\": \"" + zone + "\"}");

        assertEquals(200, set.statusCode(), set.body());
        assertEquals(zone, Api.json(set).get("timeZone").asText());
        assertEquals(branch, Api.json(set).get("orgUnitId").asText());
        assertEquals(zone, Api.json(realm.api.get("/org-units/" + branch)).get("timeZone").asText());
        assertFalse(Api.json(realm.api.get("/org-units/" + branchTeam)).has("timeZone"));
        assertEquals(zone, KeycloakEnvironment.sql("select time_zone from fuhito_org_unit where id = '" + branch + "'"));
        // The unit below the branch follows the branch. The head office follows the realm.
        String where = "zone " + zone;
        assertEquals(branchSees, count(realm, "/org-units/" + branchTeam + "/children"), where);
        assertEquals(2 + branchSees, count(realm, "/org-units/" + branch + "/subtree"), where);
        assertEquals(branchSees, count(realm, "/org-units/" + branchTeam + "/members"), where);
        assertEquals(branchSees, count(realm, "/users/" + branchUser + "/assignments"), where);
        assertEquals(headSees, count(realm, "/org-units/" + head + "/children"), where);
        assertEquals(headSees, count(realm, "/org-units/" + head + "/members"), where);
        assertEquals(headSees, count(realm, "/users/" + headUser + "/assignments"), where);
        assertEquals(1 - headSees, branchSees);
        // The list of the whole realm is on the date of the realm.
        assertEquals(3 + 2 * headSees, count(realm, "/org-units"), where);
        // A date in the request never depends on a time zone.
        assertEquals(1, count(realm, "/org-units/" + branchTeam + "/children?asOf=" + later));
        assertEquals(0, count(realm, "/org-units/" + branchTeam + "/children?asOf=" + earlier));

        assertEquals(200, realm.api.put("/org-units/" + branch + "/time-settings", "{}").statusCode());

        assertEquals(headSees, count(realm, "/org-units/" + branchTeam + "/children"));
        assertEquals("", KeycloakEnvironment.sql(
                "select time_zone from fuhito_org_unit where id = '" + branch + "' and time_zone is not null"));
    }

    @Test
    void rejectsBadSettings() throws Exception {
        TestRealm realm = TestRealm.create("unit-time-bad");
        String unit = unit(realm, "Branch", null, LocalDate.of(2020, 4, 1));
        assertEquals(200, realm.api.put("/org-units/" + unit + "/time-settings",
                "{\"timeZone\": \"Asia/Tokyo\", \"dayStart\": \"04:00\"}").statusCode());

        HttpResponse<String> badZone = realm.api.withAcceptLanguage("ja")
                .put("/org-units/" + unit + "/time-settings", "{\"timeZone\": \"Tokyo\"}");
        HttpResponse<String> badStart = realm.api.put("/org-units/" + unit + "/time-settings",
                "{\"dayStart\": \"4am\"}");

        assertEquals(400, badZone.statusCode(), badZone.body());
        JsonNode zoneError = Api.json(badZone);
        assertEquals("org_unit.time_zone_invalid", zoneError.get("error").asText());
        assertEquals("Tokyo", zoneError.get("params").get("value").asText());
        assertTrue(zoneError.get("message").asText().contains("タイムゾーン"), zoneError.toString());
        assertEquals(400, badStart.statusCode(), badStart.body());
        assertEquals("org_unit.day_start_invalid", Api.json(badStart).get("error").asText());
        assertEquals(404, realm.api.put("/org-units/nope/time-settings", "{}").statusCode());
        JsonNode kept = Api.json(realm.api.get("/org-units/" + unit));
        assertEquals("Asia/Tokyo", kept.get("timeZone").asText());
        assertEquals("04:00", kept.get("dayStart").asText());
    }
}
