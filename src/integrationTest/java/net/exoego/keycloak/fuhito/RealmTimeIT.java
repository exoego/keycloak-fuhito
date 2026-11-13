package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Keycloak container runs in UTC. These realms are on another date than the server.
 */
class RealmTimeIT {

    // At any moment, at least one of these two zones is on another date than UTC.
    static String zoneOnAnotherDate() {
        for (String zone : List.of("Pacific/Kiritimati", "Etc/GMT+12")) {
            if (!LocalDate.now(ZoneId.of(zone)).equals(LocalDate.now(ZoneOffset.UTC))) {
                return zone;
            }
        }
        throw new AssertionError("no zone is on another date than UTC");
    }

    // Hires a user in the past and removes the user. Returns the end date of the employment, which is today.
    private static String endedOn(TestRealm realm) throws Exception {
        String user = realm.user("someone");
        String unit = Api.json(realm.api.post("/org-units", "{\"name\": \"Unit\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        HttpResponse<String> staff = realm.api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, staff.statusCode(), staff.body());
        realm.removeUser(user);
        // Removal ends the employment on the date of the realm.
        return Api.json(realm.api.get("/users/" + user + "/history")).get("employments").get(0).get("validTo").asText();
    }

    @Test
    void todayIsTheDateOfTheRealmNotOfTheServer() throws Exception {
        String zone = zoneOnAnotherDate();
        TestRealm realm = TestRealm.create("realm-time", Map.of("fuhito.timeZone", zone));
        LocalDate serverToday = LocalDate.now(ZoneOffset.UTC);
        LocalDate realmToday = LocalDate.now(ZoneId.of(zone));
        assertNotEquals(serverToday, realmToday);
        // A unit that starts on the later of the two dates exists today for only one of them.
        LocalDate later = realmToday.isAfter(serverToday) ? realmToday : serverToday;
        assertEquals(201, realm.api.post("/org-units", "{\"name\": \"Starts on the later date\", \"validFrom\": \""
                + later + "\"}").statusCode());

        JsonNode defaultRead = Api.json(realm.api.get("/org-units"));

        assertEquals(realmToday.equals(later) ? 1 : 0, defaultRead.size(), "zone " + zone);
        assertEquals(1, Api.json(realm.api.get("/org-units?asOf=" + later)).size());
        assertEquals(realmToday.toString(), endedOn(realm));
    }

    @Test
    void theDayStartMovesTheChangeOfTheDate() throws Exception {
        // With a day start of 23:59, the date of the realm is yesterday for all but one minute of the day.
        TestRealm realm = TestRealm.create("realm-day-start", Map.of("fuhito.timeZone", "UTC", "fuhito.dayStart", "23:59"));
        LocalDate before = LocalDateTime.now(ZoneOffset.UTC).minusHours(23).minusMinutes(59).toLocalDate();

        String ended = endedOn(realm);

        LocalDate after = LocalDateTime.now(ZoneOffset.UTC).minusHours(23).minusMinutes(59).toLocalDate();
        // The minute may change between the two lines above, so either date is right.
        assertTrue(List.of(before.toString(), after.toString()).contains(ended), ended);
        TestRealm plain = TestRealm.create("realm-day-start-default");
        assertEquals(LocalDate.now(ZoneOffset.UTC).toString(), endedOn(plain));
    }

    @Test
    void aRealmMustSetItsTimeZone() throws Exception {
        TestRealm realm = TestRealm.create("realm-no-zone", Map.of("fuhito.timeZone", ""));

        for (HttpResponse<String> response : List.of(realm.api.get("/org-units"),
                realm.api.get("/org-units?asOf=2026-04-01"),
                realm.api.post("/org-units", "{\"name\": \"x\", \"validFrom\": \"2026-04-01\"}"),
                realm.api.get("/ref-items?kind=UNIT_TYPE"))) {
            assertEquals(409, response.statusCode(), response.body());
            assertEquals("realm.time_zone_missing", Api.json(response).get("error").asText());
        }
        assertTrue(Api.json(realm.api.withAcceptLanguage("ja").get("/org-units")).get("message").asText()
                .contains("fuhito.timeZone"));
        assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_org_unit where realm_id = '" + realm.id() + "'"));
        assertEquals(401, new Api(realm.name, null).get("/org-units").statusCode());
    }

    @Test
    void badSettingsAreReported() throws Exception {
        TestRealm badZone = TestRealm.create("realm-bad-zone", Map.of("fuhito.timeZone", "Tokyo"));
        TestRealm badStart = TestRealm.create("realm-bad-start", Map.of("fuhito.timeZone", "Asia/Tokyo", "fuhito.dayStart", "4am"));

        JsonNode zoneError = Api.json(badZone.api.get("/org-units"));
        assertEquals("realm.time_zone_invalid", zoneError.get("error").asText());
        assertEquals("Tokyo", zoneError.get("params").get("value").asText());
        JsonNode startError = Api.json(badStart.api.get("/org-units"));
        assertEquals("realm.day_start_invalid", startError.get("error").asText());
        assertEquals("4am", startError.get("params").get("value").asText());
    }

    @Test
    void removingAUserWorksInARealmWithNoTimeZone() throws Exception {
        TestRealm realm = TestRealm.create("realm-no-zone-removal", Map.of("fuhito.timeZone", ""));
        String user = realm.user("someone");

        realm.removeUser(user);

        assertEquals(404, statusInKeycloak(realm, user));
    }

    private static int statusInKeycloak(TestRealm realm, String userId) {
        try {
            KeycloakEnvironment.KEYCLOAK.getKeycloakAdminClient().realm(realm.name).users().get(userId).toRepresentation();
            return 200;
        } catch (NotFoundException e) {
            return 404;
        }
    }
}
