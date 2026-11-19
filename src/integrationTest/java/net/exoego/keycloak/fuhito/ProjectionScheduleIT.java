package net.exoego.keycloak.fuhito;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Keycloak of its own with the timer on. The shared one turns the timer off, so that its tests see no
 * rebuild that they did not ask for. The timer checks once a minute, so this test takes about two minutes.
 */
class ProjectionScheduleIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    // The projection stays off until the realm is ready. A run of half a realm would count as its daily run.
    private static void createRealm(Keycloak admin, String name, String timeZone) {
        var realm = new RealmRepresentation();
        realm.setId(name);
        realm.setRealm(name);
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        Map<String, String> attributes = new HashMap<>();
        if (timeZone != null) {
            attributes.put("fuhito.timeZone", timeZone);
        }
        realm.setAttributes(attributes);
        TestRealm.createRealm(admin, realm);
    }

    private static void setAttributes(Keycloak admin, String name, Map<String, String> changes) {
        RealmRepresentation realm = admin.realm(name).toRepresentation();
        Map<String, String> attributes = new HashMap<>(Objects.requireNonNullElse(realm.getAttributes(), Map.of()));
        attributes.putAll(changes);
        realm.setAttributes(attributes);
        admin.realm(name).update(realm);
    }

    private static void turnOnTheProjection(Keycloak admin, String name) {
        setAttributes(admin, name, Map.of("fuhito.projection.enabled", "true"));
    }

    // The same rule as in the extension.
    private static Duration delay(String realmId) {
        return Duration.ofSeconds(Math.floorMod(realmId.hashCode(), 15 * 60));
    }

    // A fixed offset from UTC in which a new date starts at the given second.
    private static String zoneWithDayStartAt(Instant dayStart) {
        int second = LocalTime.ofInstant(dayStart, ZoneOffset.UTC).toSecondOfDay();
        return ZoneOffset.ofTotalSeconds(second <= 18 * 60 * 60 ? -second : 24 * 60 * 60 - second).getId();
    }

    private static String post(Api api, String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode json = Api.json(response);
        return json.has("id") ? json.get("id").asText() : "";
    }

    // The test runs longer than a token lasts, so each call asks for one. The admin client renews it.
    private static String token(Keycloak admin) {
        return admin.tokenManager().getAccessTokenString();
    }

    private static List<String> groupNames(KeycloakContainer keycloak, Keycloak admin, String realm,
            String organization) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(keycloak.getAuthServerUrl()
                        + "/admin/realms/" + realm + "/organizations/" + organization + "/groups"))
                .header("Authorization", "Bearer " + token(admin)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Api.names(Api.json(response)).stream().sorted().toList();
    }

    // The last run that is done, from a call or from the timer, or null when none is done.
    private static JsonNode lastRun(KeycloakContainer keycloak, Keycloak admin, String realm) throws Exception {
        HttpResponse<String> response = new Api(keycloak.getAuthServerUrl(), realm, token(admin))
                .get("/projection/last-run");
        if (response.statusCode() == 404) {
            return null;
        }
        assertEquals(200, response.statusCode(), response.body());
        JsonNode run = Api.json(response);
        return run.get("state").asText().equals("RUNNING") ? null : run;
    }

    // The first wait can be up to two checks of the timer, when it checked just before the realm was ready.
    private static JsonNode waitForRun(KeycloakContainer keycloak, Keycloak admin, String realm) throws Exception {
        for (int i = 0; i < 300; i++) {
            JsonNode run = lastRun(keycloak, admin, realm);
            if (run != null) {
                return run;
            }
            Thread.sleep(500);
        }
        return null;
    }

    // Waits for up to two and a half minutes, which is more than two checks of the timer.
    private static boolean waitFor(Callable<Boolean> condition) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static int count(String logs, String text) {
        return logs.split(Pattern.quote(text), -1).length - 1;
    }

    private static String timerRun(PostgreSQLContainer postgres, String realmId) throws Exception {
        return KeycloakEnvironment.run(postgres, "select state || ' ' || coalesce(error_code, '') from fuhito_job_run"
                + " where realm_id = '" + realmId + "' and job = 'projection-timer'");
    }

    private static String utc(Instant instant) {
        return "timestamp '" + LocalDateTime.ofInstant(instant, ZoneOffset.UTC) + "'";
    }

    // Two units below one parent have the same name here, so every rebuild of this realm fails.
    private static String failingRealm(KeycloakContainer keycloak, Keycloak admin) throws Exception {
        createRealm(admin, "a-failing", "UTC");
        String organization = OrganizationLinkIT.createOrganization(admin, "a-failing", "failing");
        Api api = new Api(keycloak.getAuthServerUrl(), "a-failing", token(admin));
        String clinic = post(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Clinic\"}}, \"validFrom\": \"2020-04-01\"}");
        for (int i = 0; i < 2; i++) {
            post(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Office\"}}, \"parentId\": \"" + clinic
                    + "\", \"validFrom\": \"2020-04-01\"}");
        }
        assertEquals(200, api.putWithVersion("/org-units/" + clinic + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        return organization;
    }

    // The org unit of this realm points to an organization that is gone, so its rebuild crashes.
    private static void crashingRealm(KeycloakContainer keycloak, Keycloak admin, PostgreSQLContainer postgres)
            throws Exception {
        createRealm(admin, "a-crashing", "UTC");
        String organization = OrganizationLinkIT.createOrganization(admin, "a-crashing", "crashing");
        Api api = new Api(keycloak.getAuthServerUrl(), "a-crashing", token(admin));
        String lost = post(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Lost\"}}, \"validFrom\": \"2020-04-01\"}");
        post(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Below\"}}, \"parentId\": \"" + lost
                + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(200, api.putWithVersion("/org-units/" + lost + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        // Keycloak would remove the link with the organization. Only broken data keeps it.
        KeycloakEnvironment.run(postgres, "update fuhito_org_unit set kc_org_id = 'no-such-organization'"
                + " where id = '" + lost + "'");
    }

    // A nurse in a unit below a linked hospital. The next day start of the realm is half a day away for now.
    private static String workingRealm(KeycloakContainer keycloak, Keycloak admin) throws Exception {
        createRealm(admin, "b-working", zoneWithDayStartAt(Instant.now().plus(12, ChronoUnit.HOURS)));
        String organization = OrganizationLinkIT.createOrganization(admin, "b-working", "hospital");
        Api api = new Api(keycloak.getAuthServerUrl(), "b-working", token(admin));
        TestRealm.legalEntity(api, "法人");
        String hospital = post(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Hospital\"}}, \"validFrom\": \"2020-04-01\"}");
        String nursing = post(api, "/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Nursing\"}}, \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}");
        var user = new UserRepresentation();
        user.setUsername("nurse");
        user.setEnabled(true);
        String nurse;
        try (Response response = admin.realm("b-working").users().create(user)) {
            nurse = CreatedResponseUtil.getCreatedId(response);
        }
        post(api, "/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + nursing
                + "\", \"validFrom\": \"2020-04-01\"}");
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        return organization;
    }

    // The timer ran this realm a moment ago, and its next day start is half a day away. A call ran it too.
    private static void busyRealm(Keycloak admin, PostgreSQLContainer postgres) throws Exception {
        createRealm(admin, "d-busy", zoneWithDayStartAt(Instant.now().plus(12, ChronoUnit.HOURS)));
        String now = utc(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        KeycloakEnvironment.run(postgres, "insert into fuhito_job_run (id, realm_id, job, state, started_at,"
                + " ended_at) values ('busy-timer', 'd-busy', 'projection-timer', 'DONE', " + now + ", " + now
                + "), ('busy-call', 'd-busy', 'projection', 'DONE', " + now + ", " + now + ")");
        turnOnTheProjection(admin, "d-busy");
    }

    // A call of POST /projection/rebuild goes on when the timer finds the realm due.
    private static void skipWhileACallRuns(KeycloakContainer keycloak, Keycloak admin, PostgreSQLContainer postgres)
            throws Exception {
        Api api = new Api(keycloak.getAuthServerUrl(), "d-busy", token(admin));
        CompletableFuture<HttpResponse<String>> call;
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            // The call writes the start of its run inside the lock of the realm. So it waits there for this row.
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("select id from fuhito_job_run where id = 'busy-call' for update");
            }
            call = CompletableFuture.supplyAsync(() -> {
                try {
                    return api.post("/projection/rebuild", "");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(waitFor(() -> !KeycloakEnvironment.run(postgres, "select count(*) from pg_stat_activity"
                    + " where wait_event_type = 'Lock' and query ilike '%fuhito_job_run%'").equals("0")),
                    "the call never waited");

            KeycloakEnvironment.run(postgres, "delete from fuhito_job_run where id = 'busy-timer'");
            assertTrue(waitFor(() -> keycloak.getLogs().contains("realm d-busy waits for the next check")),
                    "the timer skipped d-busy");
            assertEquals("", timerRun(postgres, "d-busy"), "the skip is not recorded");
            connection.rollback();
        }
        assertEquals(200, call.get().statusCode(), call.get().body());
    }

    @Test
    void theTimerRunsEachRealmOnceAfterItsDayStart() throws Exception {
        try (Network network = Network.newNetwork();
                PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
                        .withNetwork(network)
                        .withNetworkAliases("schedule-postgres")
                        .withDatabaseName("keycloak")
                        .withUsername("keycloak")
                        .withPassword("keycloak");
                KeycloakContainer keycloak =
                        Coverage.keycloak()
                                .withNetwork(network)
                                .withProviderLibsFrom(List.of(new File(System.getProperty("provider.jar"))))
                                .withEnv("KC_DB", "postgres")
                                .withEnv("KC_DB_URL", "jdbc:postgresql://schedule-postgres:5432/keycloak")
                                .withEnv("KC_DB_USERNAME", "keycloak")
                                .withEnv("KC_DB_PASSWORD", "keycloak")
                                // The timer logs a realm that it leaves for the next check at the debug level.
                                .withEnv("KC_LOG_LEVEL",
                                        "info,net.exoego.keycloak.fuhito.keycloak.ProjectionSchedule:debug")) {
            postgres.start();
            keycloak.start();
            Keycloak admin = keycloak.getKeycloakAdminClient();
            // This realm has no time zone. The timer skips it and goes on to the next realm.
            createRealm(admin, "a-broken", null);
            String brokenOrganization = OrganizationLinkIT.createOrganization(admin, "a-broken", "broken");
            String failingOrganization = failingRealm(keycloak, admin);
            crashingRealm(keycloak, admin, postgres);
            String organization = workingRealm(keycloak, admin);
            busyRealm(admin, postgres);

            for (String realm : List.of("a-broken", "a-failing", "a-crashing")) {
                turnOnTheProjection(admin, realm);
            }
            // The timer ran this realm a moment ago. A new date starts so that the run is due in a few seconds.
            Instant due = Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
            KeycloakEnvironment.run(postgres, "insert into fuhito_job_run (id, realm_id, job, state, started_at,"
                    + " ended_at) values ('earlier-run', 'b-working', 'projection-timer', 'DONE', "
                    + utc(due.minusSeconds(15)) + ", " + utc(due.minusSeconds(14)) + ")");
            setAttributes(admin, "b-working", Map.of("fuhito.projection.enabled", "true",
                    "fuhito.timeZone", zoneWithDayStartAt(due.minus(delay("b-working")))));
            skipWhileACallRuns(keycloak, admin, postgres);

            JsonNode run = waitForRun(keycloak, admin, "b-working");
            assertNotNull(run, "the timer ran the realm");
            Instant startedAt = Instant.parse(run.get("startedAt").asText());
            assertTrue(!startedAt.isBefore(due), "the run started at " + startedAt + ", before " + due);
            assertEquals(List.of("Nursing"), groupNames(keycloak, admin, "b-working", organization));

            // A realm that the timer never ran runs at the next check. That shows that one more check went by.
            createRealm(admin, "c-new", "UTC");
            turnOnTheProjection(admin, "c-new");
            assertNotNull(waitForRun(keycloak, admin, "c-new"), "the timer ran c-new");
            // The check goes on with the realms after it.
            Thread.sleep(3000);

            assertEquals(run.get("startedAt").asText(), lastRun(keycloak, admin, "b-working").get("startedAt").asText(),
                    "the run was once");
            assertEquals("DONE ", timerRun(postgres, "b-working"));
            assertEquals(List.of(), groupNames(keycloak, admin, "a-broken", brokenOrganization));
            assertEquals("", timerRun(postgres, "a-broken"));
            assertEquals(List.of(), groupNames(keycloak, admin, "a-failing", failingOrganization));
            assertEquals("FAILED projection.duplicate_name", timerRun(postgres, "a-failing"));
            assertEquals("FAILED internal", timerRun(postgres, "a-crashing"));
            // The skip left the realm due.
            assertTrue(waitFor(() -> timerRun(postgres, "d-busy").equals("DONE ")), "a later check ran d-busy");
            String logs = keycloak.getLogs();
            assertEquals(0, count(logs, "realm d-busy failed"), "the skip is no failure");
            assertEquals(1, count(logs, "The projection runs once a day"), "the start is logged");
            // A failed run waits for 15 minutes, and a skipped realm is logged once.
            assertEquals(1, count(logs, "skips realm a-broken"), "the skipped realm is logged");
            assertEquals(1, count(logs, "realm a-failing failed with projection.duplicate_name"), "the failure");
            assertEquals(1, count(logs, "The scheduled projection failed for the realm with the ID a-crashing"),
                    "the crash");
            // The log names the thread. The timer thread of Keycloak would hold up its other scheduled tasks.
            assertTrue(Pattern.compile("\\(kc-fuhito-projection-[^)]*\\) The scheduled projection of realm a-failing")
                    .matcher(logs).find(), "the rebuild ran on a thread of its own");
            Coverage.save(keycloak);
        }
    }
}
