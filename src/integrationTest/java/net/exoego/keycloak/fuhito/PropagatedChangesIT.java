package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.opentest4j.AssertionFailedError;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hospitals that link Keycloak organizations, which an administrator removes in Keycloak. The read of each unit
 * changes, although nobody wrote the unit. The shared Keycloak runs the cleanup of the change log every few seconds.
 */
class PropagatedChangesIT {

    private static final Keycloak ADMIN = KEYCLOAK.getKeycloakAdminClient();

    private record Hospital(String realm, Api api, String unit, String organization) {
    }

    private static Hospital hospital(Map<String, String> attributes) throws Exception {
        var realm = new RealmRepresentation();
        realm.setRealm("propagated-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        realm.setAdminEventsEnabled(true);
        realm.setAttributes(new HashMap<>(attributes));
        TestRealm.withTimeZone(realm);
        TestRealm.createRealm(ADMIN, realm);
        Api api = new Api(realm.getRealm(), ADMIN.tokenManager().getAccessTokenString());
        return linked(realm.getRealm(), api, "X病院");
    }

    private static Hospital linked(String realm, Api api, String name) throws Exception {
        String organization = OrganizationLinkIT.createOrganization(ADMIN, realm, "o" + UUID.randomUUID());
        HttpResponse<String> created = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"" + name + "\"}}, \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        String unit = Api.json(created).get("id").asText();
        assertEquals(200, api.putWithVersion("/org-units/" + unit + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        return new Hospital(realm, api, unit, organization);
    }

    private static void removeOrganization(Hospital hospital) {
        ADMIN.realm(hospital.realm()).organizations().get(hospital.organization()).delete().close();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static JsonNode error(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String realmId(String realm) {
        return ADMIN.realm(realm).toRepresentation().getId();
    }

    private static void age(String realm, String where, int days) throws Exception {
        KeycloakEnvironment.sql("update fuhito_change_log set recorded_at = recorded_at - interval '" + days
                + " days' where realm_id = '" + realmId(realm) + "'" + where);
    }

    // The cleanup runs every few seconds. Two runs are well within the wait.
    private static void waitFor(String what, Callable<Boolean> condition) throws Exception {
        for (int i = 0; i < 240; i++) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionFailedError("the cleanup never " + what);
    }

    @Test
    void aRemovalOfALinkedOrganizationAddsAPropagatedChangeOfTheUnit() throws Exception {
        Hospital hospital = hospital(Map.of());
        Api api = hospital.api();
        long version = api.version("/org-units/" + hospital.unit());
        DerivedReads reads = DerivedReads.before(api, "2026-04-01");

        removeOrganization(hospital);

        reads.assertEveryChangeIsLogged();
        assertEquals(version + 1, api.version("/org-units/" + hospital.unit()));
        JsonNode entries = ok(api.get("/change-log?resourceId=" + hospital.unit()));
        assertEquals(List.of("WRITE CREATE", "WRITE UPDATE", "PROPAGATED UPDATE"), entries.valueStream()
                .map(entry -> entry.get("kind").asText() + " " + entry.get("operation").asText()).toList());
        JsonNode propagated = entries.get(2);
        assertEquals(List.of("FUHITO_ORG_UNIT", "fuhito/org-units/" + hospital.unit(), "KEYCLOAK_ORGANIZATION_REMOVED"),
                List.of(propagated.get("resourceType").asText(), propagated.get("resourcePath").asText(),
                        propagated.get("cause").asText()));
        for (String absent : List.of("actorUserId", "changeOrderId", "causeSequence")) {
            assertFalse(propagated.has(absent), absent);
        }

        // A reader that wants who wrote what leaves the propagated change out.
        assertEquals(List.of("CREATE", "UPDATE"), ok(api.get("/change-log?kind=WRITE&resourceId=" + hospital.unit()))
                .findValuesAsText("operation"));
        assertEquals(List.of(propagated), ok(api.get("/change-log?kind=PROPAGATED")).valueStream().toList());
        String ofUnit = "/change-log/count?resourceId=" + hospital.unit();
        assertEquals(List.of(2L, 1L, 3L), List.of(api.count(ofUnit + "&kind=WRITE"),
                api.count("/change-log/count?kind=PROPAGATED"), api.count(ofUnit)));
        assertEquals("invalid.choice", error(400, api.get("/change-log?kind=write")).get("error").asText());
        // Only the writes record admin events.
        assertEquals(List.of("CREATE fuhito/org-units/" + hospital.unit(),
                "UPDATE fuhito/org-units/" + hospital.unit() + "/organization"),
                ADMIN.realm(hospital.realm()).getAdminEvents().stream()
                        .filter(event -> event.getResourceType().startsWith("FUHITO_"))
                        .map(event -> event.getOperationType() + " " + event.getResourcePath())
                        .sorted()
                        .toList());
    }

    @Test
    void theCheckOfTheDerivedValuesFailsForAReadThatChangedWithNoEntry() throws Exception {
        Hospital hospital = hospital(Map.of());
        assertTrue(DerivedReads.values().contains(
                new DerivedReads.Value("FUHITO_ORG_UNIT", "/org-units/", "keycloakOrganizationId")));
        DerivedReads reads = DerivedReads.before(hospital.api());

        // A bug that changes the value of a read with no entry, as a write with no propagated change would.
        KeycloakEnvironment.sql("update fuhito_org_unit set kc_org_id = null where id = '" + hospital.unit() + "'");

        String failure = assertThrows(AssertionFailedError.class, reads::assertEveryChangeIsLogged).getMessage();
        assertTrue(failure.contains("no new version: FUHITO_ORG_UNIT " + hospital.unit()), failure);
        assertTrue(failure.contains("no entry: FUHITO_ORG_UNIT " + hospital.unit()), failure);
    }

    @Test
    void theCleanupRemovesTheOldPropagatedChangesAndKeepsEveryWrite() throws Exception {
        Hospital first = hospital(Map.of("fuhito.changeLog.keepPropagatedDays", "30"));
        Api api = first.api();
        Hospital second = linked(first.realm(), api, "Y病院");
        removeOrganization(first);
        removeOrganization(second);
        JsonNode propagated = ok(api.get("/change-log?kind=PROPAGATED"));
        long removed = propagated.get(0).get("sequence").asLong();
        long kept = propagated.get(1).get("sequence").asLong();
        long writes = api.count("/change-log/count?kind=WRITE");
        // The writes are older than any realm keeps a propagated change. The second propagated change is not.
        age(first.realm(), "", 400);
        age(first.realm(), " and sequence_number = " + kept, -380);

        waitFor("removed the old propagated change",
                () -> api.count("/change-log/count?kind=PROPAGATED") == 1);

        assertEquals(writes, api.count("/change-log/count?kind=WRITE"));
        assertEquals(List.of(kept), ok(api.get("/change-log?kind=PROPAGATED")).findValuesAsText("sequence").stream()
                .map(Long::valueOf).toList());
        // A reader that saw a number before the removed change would miss it, so it reads everything again.
        JsonNode expired = error(410, api.get("/change-log?after=" + (removed - 1)));
        assertEquals(List.of("change_log.cursor_expired", Long.toString(removed - 1), Long.toString(removed)),
                List.of(expired.get("error").asText(), expired.get("params").get("after").asText(),
                        expired.get("params").get("keptAfter").asText()));
        assertTrue(Api.json(api.withAcceptLanguage("ja").get("/change-log?after=1")).get("message").asText()
                .startsWith("変更ログには"));
        assertEquals(410, api.get("/change-log?after=1&kind=PROPAGATED&resourceId=" + first.unit()).statusCode());
        // Writes are never removed, and a read with no number starts with the first entry that is kept.
        assertFalse(ok(api.get("/change-log?after=1&kind=WRITE")).isEmpty());
        assertEquals(List.of(kept), ok(api.get("/change-log?after=" + removed)).findValuesAsText("sequence").stream()
                .map(Long::valueOf).toList());
        assertEquals(writes + 1, ok(api.get("/change-log")).size());
        assertEquals(writes + 1, ok(api.get("/change-log?after=0")).size());
        // A number that no entry ever had is a mistake of the reader, such as a number of another realm.
        JsonNode unknown = error(400, api.get("/change-log?after=" + (kept + 1) + "&kind=WRITE"));
        assertEquals(List.of("invalid.out_of_range", "after", "0", Long.toString(kept)),
                List.of(unknown.get("error").asText(), unknown.get("params").get("field").asText(),
                        unknown.get("params").get("min").asText(), unknown.get("params").get("max").asText()));
        assertEquals(List.of(), ok(api.get("/change-log?after=" + kept)).valueStream().toList());
    }

    private static int count(String logs, String text) {
        return logs.split(Pattern.quote(text), -1).length - 1;
    }

    // A realm with a bad setting is logged once, and a realm whose cleanup fails does not stop the others.
    @Test
    void aRealmWithABadSettingOrAFailureKeepsItsPropagatedChanges() throws Exception {
        Hospital bad = hospital(Map.of("fuhito.changeLog.keepPropagatedDays", "ninety"));
        Hospital failing = hospital(Map.of());
        Hospital healthy = hospital(Map.of());
        List<Hospital> hospitals = List.of(bad, failing, healthy);
        for (Hospital hospital : hospitals) {
            removeOrganization(hospital);
        }
        String failed = "The cleanup of the change log failed for the realm with the ID " + realmId(failing.realm());
        KeycloakEnvironment.sql("""
                create function fuhito_test_failure() returns trigger language plpgsql as
                'begin raise exception ''the test fails the cleanup''; end'""");
        try {
            KeycloakEnvironment.sql("create trigger fuhito_test_failure before delete on fuhito_change_log"
                    + " for each row when (old.realm_id = '" + realmId(failing.realm()) + "')"
                    + " execute function fuhito_test_failure()");
            for (Hospital hospital : hospitals) {
                age(hospital.realm(), "", 100);
            }

            waitFor("removed the propagated change of the healthy realm",
                    () -> healthy.api().count("/change-log/count?kind=PROPAGATED") == 0);
            waitFor("ran twice", () -> count(KEYCLOAK.getLogs(), failed) >= 2);
        } finally {
            KeycloakEnvironment.sql("drop trigger fuhito_test_failure on fuhito_change_log");
            KeycloakEnvironment.sql("drop function fuhito_test_failure()");
        }

        assertEquals(1, count(KEYCLOAK.getLogs(), "The cleanup of the change log skips realm " + bad.realm()
                + ". The realm attribute \"fuhito.changeLog.keepPropagatedDays\" must be a number of days of 0 or "
                + "more, not \"ninety\"."));
        for (Hospital hospital : List.of(bad, failing)) {
            assertEquals(1, hospital.api().count("/change-log/count?kind=PROPAGATED"), hospital.realm());
        }
    }
}
