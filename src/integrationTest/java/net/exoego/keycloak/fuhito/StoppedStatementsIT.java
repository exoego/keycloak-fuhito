package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.connection;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.holding;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.holdingRealm;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.logLength;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.sql;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.update;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waiterOf;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waitingFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request whose statement PostgreSQL stops gets an error of the extension, and leaves one line in the log of
 * Keycloak. A user of the sync and the last run of a job get the same code.
 */
class StoppedStatementsIT {

    private static final String UNIT = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}, "
            + "\"validFrom\": \"2026-04-01\"}";

    private static final String CANCELED = "PostgreSQL stopped a statement with 57014: canceling statement due to "
            + "user request.";

    private static final String DEADLOCK = "PostgreSQL stopped a statement with 40P01: deadlock detected.";

    private static String id(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return Api.json(response).get("id").asText();
    }

    private static void assertError(int status, String code, String message, HttpResponse<String> response)
            throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = Api.json(response);
        assertEquals(code, body.get("error").asText());
        assertTrue(body.path("params").isEmpty(), response.body());
        assertEquals(message, body.get("message").asText());
        assertTrue(response.headers().firstValue("Retry-After").isEmpty());
    }

    // Stops the statement that waits for the lock of the connection, as an administrator can.
    private static String cancelWaiterOf(Connection holder) throws Exception {
        String pid = waiterOf(holder);
        String query = sql("select query from pg_stat_activity where pid = " + pid);
        assertEquals("true", sql("select pg_cancel_backend(" + pid + ")"));
        return query.toLowerCase(Locale.ROOT);
    }

    // Stops the statements that wait for the lock of the connection, one after another. After a stop, a statement
    // can wait again in a new transaction.
    private static void cancelWaitersOf(Connection holder, int times) throws Exception {
        String waiting;
        try (Statement statement = holder.createStatement();
                ResultSet row = statement.executeQuery("select pg_backend_pid()")) {
            assertTrue(row.next());
            waiting = "select pid || ' ' || xact_start from pg_stat_activity where " + row.getString(1)
                    + " = any(pg_blocking_pids(pid))";
        }
        String before = "";
        for (int stopped = 0; stopped < times; stopped++) {
            String now = sql(waiting);
            for (int i = 0; i < 300 && (now.isEmpty() || now.equals(before)); i++) {
                Thread.sleep(100);
                now = sql(waiting);
            }
            assertFalse(now.isEmpty() || now.equals(before), "nothing waited again after " + stopped + " stops");
            assertEquals("true", sql("select pg_cancel_backend(" + now.substring(0, now.indexOf(' ')) + ")"));
            before = now;
        }
    }

    private static void assertLastRun(String state, String error, HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        JsonNode run = Api.json(response);
        assertEquals(List.of(state, error), List.of(run.get("state").asText(), run.path("error").asText()));
    }

    private static TestRealm syncRealm(String prefix) {
        TestRealm realm = TestRealm.withEmployer(prefix, Map.of("fuhito.sync.enabled", "true"));
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        // Without this, the user profile of Keycloak drops attributes that it does not know.
        UPConfig profile = admin.realm(realm.name).users().userProfile().getConfiguration();
        profile.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        admin.realm(realm.name).users().userProfile().update(profile);
        return realm;
    }

    private static String syncUser(TestRealm realm, String username, Map<String, String> attributes) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        attributes.forEach(user::singleAttribute);
        try (Response created = KEYCLOAK.getKeycloakAdminClient().realm(realm.name).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(created);
        }
    }

    // The line of the extension about the request. The container hands the log over a little later.
    private static void assertLogged(int since, String line) throws Exception {
        List<String> lines = List.of();
        for (int i = 0; i < 100 && lines.isEmpty(); i++) {
            Thread.sleep(100);
            lines = KEYCLOAK.getLogs().substring(since).lines().filter(each -> each.endsWith(line)).toList();
        }
        assertEquals(1, lines.size(), line);
        assertTrue(lines.getFirst().contains(" WARN "), lines.getFirst());
        String log = KEYCLOAK.getLogs().substring(since);
        assertFalse(log.lines().anyMatch(each -> each.startsWith("\tat ")), log);
    }

    // Takes the lock of the writes of the realm alone, as a bulk write does. The connection never looks for a
    // deadlock itself, so PostgreSQL stops the statement of Keycloak that waits for it.
    private static boolean lockRealmAlone(Connection connection, String realmId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select realm_id from fuhito_realm_write where realm_id = ? for no key update")) {
            statement.setString(1, realmId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static Connection neverLooksForADeadlock() throws Exception {
        Connection connection = connection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("set deadlock_timeout = '1min'");
        }
        return connection;
    }

    private static void lockRow(Connection connection, String sql, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
            }
        }
    }

    @Test
    void aWriteThatPostgreSqlStopsWhileItWaitsForARowGets503() throws Exception {
        TestRealm realm = TestRealm.create("stopped-row");
        String unit = id(realm.api.post("/org-units", UNIT.formatted("A")));
        int log = logLength();

        HttpResponse<String> response;
        String waited;
        try (Connection holder = holding("fuhito_org_unit", unit)) {
            CompletableFuture<HttpResponse<String>> write = waitingFor(holder,
                    () -> realm.api.putWithVersion("/org-units/" + unit, UNIT.formatted("B")));
            waited = cancelWaiterOf(holder);
            response = write.get(60, TimeUnit.SECONDS);
            holder.rollback();
        }

        assertTrue(waited.contains("fuhito_org_unit"), waited);
        assertError(503, "request.timed_out", "The operation timed out. Try it again later, or review the time limit "
                + "of the database.", response);
        assertLogged(log, " PUT /admin/realms/" + realm.name + "/fuhito/org-units/" + unit
                + " failed with 503 request.timed_out. " + CANCELED);
        JsonNode stored = Api.json(realm.api.get("/org-units/" + unit));
        assertEquals(List.of("A", "1"), List.of(stored.get("name").asText(), stored.get("version").asText()));
    }

    // The lock of the realm comes before the operation of the request.
    @Test
    void aWriteThatPostgreSqlStopsWhileItWaitsForTheLockOfTheRealmGets503() throws Exception {
        TestRealm realm = TestRealm.create("stopped-realm");
        Api japanese = realm.api.withAcceptLanguage("ja");
        String unit = id(realm.api.post("/org-units", UNIT.formatted("A")));
        int log = logLength();

        HttpResponse<String> response;
        String waited;
        try (Connection holder = holdingRealm(realm.id(), true)) {
            CompletableFuture<HttpResponse<String>> write = waitingFor(holder,
                    () -> japanese.putWithVersion("/org-units/" + unit, UNIT.formatted("B")));
            waited = cancelWaiterOf(holder);
            response = write.get(60, TimeUnit.SECONDS);
            holder.rollback();
        }

        assertTrue(waited.contains("fuhito_realm_write"), waited);
        assertError(503, "request.timed_out", "操作がタイムアウトしました。時間をおいてやり直すか、またはデータベースの制限時間を見直してください。",
                response);
        assertLogged(log, " PUT /admin/realms/" + realm.name + "/fuhito/org-units/" + unit
                + " failed with 503 request.timed_out. " + CANCELED);
        assertEquals("A", Api.json(realm.api.get("/org-units/" + unit)).get("name").asText());
    }

    // A read takes no lock of a row. It still waits for a table that another transaction locked whole.
    @Test
    void aReadThatPostgreSqlStopsGets503() throws Exception {
        TestRealm realm = TestRealm.create("stopped-read");
        String unit = id(realm.api.post("/org-units", UNIT.formatted("A")));
        int log = logLength();

        HttpResponse<String> response;
        try (Connection holder = connection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("lock table fuhito_org_unit in access exclusive mode");
            }
            CompletableFuture<HttpResponse<String>> read = waitingFor(holder,
                    () -> realm.api.get("/org-units/" + unit));
            cancelWaiterOf(holder);
            response = read.get(60, TimeUnit.SECONDS);
            holder.rollback();
        }

        assertError(503, "request.timed_out", "The operation timed out. Try it again later, or review the time limit "
                + "of the database.", response);
        assertLogged(log, " GET /admin/realms/" + realm.name + "/fuhito/org-units/" + unit
                + " failed with 503 request.timed_out. " + CANCELED);
    }

    @Test
    void aWriteThatLosesADeadlockGets409() throws Exception {
        TestRealm realm = TestRealm.create("stopped-deadlock");
        String realmId = realm.id();
        String unit = id(realm.api.post("/org-units", UNIT.formatted("A")));
        int log = logLength();

        HttpResponse<String> response;
        try (Connection other = neverLooksForADeadlock(); Connection first = holding("fuhito_org_unit", unit)) {
            // The write takes the counter of the change log after the unit.
            update(other, "update fuhito_change_log_counter set last_sequence = last_sequence where realm_id = ?",
                    realmId);
            CompletableFuture<HttpResponse<String>> write = waitingFor(first,
                    () -> realm.api.putWithVersion("/org-units/" + unit, UNIT.formatted("B")));
            // The write holds the lock of the realm while it waits for the unit.
            CompletableFuture<Boolean> alone = waitingFor(waiterOf(first), () -> lockRealmAlone(other, realmId));
            first.commit();
            response = write.get(60, TimeUnit.SECONDS);
            assertTrue(alone.get(60, TimeUnit.SECONDS));
            other.rollback();
        }

        assertError(409, "write.aborted", "The operation conflicted with another write at the same time. Try it "
                + "again later.", response);
        assertLogged(log, " PUT /admin/realms/" + realm.name + "/fuhito/org-units/" + unit
                + " failed with 409 write.aborted. " + DEADLOCK);
        JsonNode stored = Api.json(realm.api.get("/org-units/" + unit));
        assertEquals(List.of("A", "1"), List.of(stored.get("name").asText(), stored.get("version").asText()));
        assertEquals(200, realm.api.putWithVersion("/org-units/" + unit, UNIT.formatted("B")).statusCode());
    }

    // Each piece of a rebuild is a transaction of its own on a thread of its own. The pieces before stay.
    @Test
    void aRebuildWhosePieceLosesADeadlockGets409() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var representation = new RealmRepresentation();
        representation.setRealm("stopped-rebuild-" + UUID.randomUUID());
        representation.setEnabled(true);
        representation.setOrganizationsEnabled(true);
        representation.setAttributes(Map.of("fuhito.projection.enabled", "true"));
        TestRealm.withTimeZone(representation);
        TestRealm.createRealm(admin, representation);
        String realm = representation.getRealm();
        String realmId = admin.realm(realm).toRepresentation().getId();
        Api api = new Api(realm, admin.tokenManager().getAccessTokenString());
        TestRealm.legalEntity(api, "法人");
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "x-hospital");
        String hospital = id(api.post("/org-units", UNIT.formatted("X病院")));
        List<String> users = Stream.of("nurse", "doctor").map(username -> {
            var user = new UserRepresentation();
            user.setUsername(username);
            user.setEnabled(true);
            try (Response created = admin.realm(realm).users().create(user)) {
                return CreatedResponseUtil.getCreatedId(created);
            }
        }).sorted().toList();
        for (String user : users) {
            assertEquals(201, api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + hospital
                    + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        }
        assertEquals(200, api.putWithVersion("/org-units/" + hospital + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        assertEquals(200, api.post("/projection/rebuild", "").statusCode());
        // The next rebuild only forgets the two records, in the order of the users, in one piece.
        for (String user : users) {
            try (Response removed = admin.realm(realm).organizations().get(organization).members().member(user)
                    .delete()) {
                assertEquals(204, removed.getStatus());
            }
        }
        assertEquals(200, api.deleteWithVersion("/org-units/" + hospital + "/organization").statusCode());
        String record = "select id from fuhito_projected_member where user_id = ? for update";
        int log = logLength();

        HttpResponse<String> response;
        try (Connection other = neverLooksForADeadlock(); Connection first = connection()) {
            lockRow(other, record, users.getLast());
            first.setAutoCommit(false);
            lockRow(first, record, users.getFirst());
            CompletableFuture<HttpResponse<String>> rebuild = waitingFor(first,
                    () -> api.post("/projection/rebuild", ""));
            // The piece holds the lock of the realm while it waits for the first record.
            CompletableFuture<Boolean> alone = waitingFor(waiterOf(first), () -> lockRealmAlone(other, realmId));
            first.commit();
            assertTrue(alone.get(60, TimeUnit.SECONDS));
            // The rebuild records its failure after that, with the lock of the realm.
            other.rollback();
            response = rebuild.get(60, TimeUnit.SECONDS);
        }

        assertError(409, "write.aborted", "The operation conflicted with another write at the same time. Try it "
                + "again later.", response);
        assertLogged(log, " POST /admin/realms/" + realm + "/fuhito/projection/rebuild failed with 409 "
                + "write.aborted. " + DEADLOCK);
        assertLastRun("FAILED", "write.aborted", api.get("/projection/last-run"));
        assertEquals("2", sql("select count(*) from fuhito_projected_member where realm_id = '" + realmId + "'"));
        assertEquals(200, api.post("/projection/rebuild", "").statusCode());
        assertEquals("0", sql("select count(*) from fuhito_projected_member where realm_id = '" + realmId + "'"));
    }

    // Each piece of the sync takes the lock of the realm alone. The record of the run only shares it.
    @Test
    void aSyncThatPostgreSqlStopsKeepsTheCodeOfItsErrorAsTheLastRun() throws Exception {
        TestRealm realm = syncRealm("stopped-sync-run");
        int log = logLength();

        HttpResponse<String> response;
        try (Connection holder = holdingRealm(realm.id(), false)) {
            CompletableFuture<HttpResponse<String>> sync = waitingFor(holder,
                    () -> realm.api.post("/attribute-sync/run", ""));
            cancelWaiterOf(holder);
            response = sync.get(60, TimeUnit.SECONDS);
            holder.rollback();
        }

        assertError(503, "request.timed_out", "The operation timed out. Try it again later, or review the time limit "
                + "of the database.", response);
        assertLogged(log, " POST /admin/realms/" + realm.name + "/fuhito/attribute-sync/run failed with 503 "
                + "request.timed_out. " + CANCELED);
        assertLastRun("FAILED", "request.timed_out", realm.api.get("/attribute-sync/last-run"));
    }

    // A piece of users that fails runs again with each user alone. So the statement of bob is stopped twice.
    @Test
    void aUserOfTheSyncWhoseStatementPostgreSqlStopsFailsWithTheCodeOfTheError() throws Exception {
        TestRealm realm = syncRealm("stopped-sync-user");
        id(realm.api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"内科\"}}, "
                + "\"code\": \"naika\", \"validFrom\": \"2020-04-01\"}"));
        String alice = syncUser(realm, "alice", Map.of("department", "naika", "title", "院長"));
        String bob = syncUser(realm, "bob", Map.of("department", "naika", "title", "院長"));
        assertEquals(200, realm.api.post("/attribute-sync/run", "").statusCode());
        String assignment = Api.json(realm.api.get("/users/" + bob + "/assignments")).get(0).get("assignmentId")
                .asText();
        // The next run gives the title to both assignments.
        realm.refItem("JOB_TITLE", "head", "院長");
        int log = logLength();

        HttpResponse<String> response;
        try (Connection holder = connection()) {
            holder.setAutoCommit(false);
            update(holder, "update fuhito_assignment set version = version where assignment_id = ?", assignment);
            CompletableFuture<HttpResponse<String>> sync = waitingFor(holder,
                    () -> realm.api.post("/attribute-sync/run", ""));
            cancelWaitersOf(holder, 2);
            response = sync.get(60, TimeUnit.SECONDS);
            holder.rollback();
        }

        assertEquals(200, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        JsonNode failed = report.get("failed");
        assertEquals(1, failed.size(), response.body());
        assertEquals(List.of(bob, "request.timed_out"),
                List.of(failed.get(0).get("userId").asText(), failed.get(0).get("error").asText()));
        assertEquals(1, report.get("assignmentsChanged").asInt());
        assertLogged(log, " The sync from user attributes failed for the user with the ID " + bob
                + " with request.timed_out. " + CANCELED);
        assertEquals("head", Api.json(realm.api.get("/users/" + alice + "/assignments")).get(0).get("jobTitle")
                .asText());
        JsonNode again = Api.json(realm.api.post("/attribute-sync/run", ""));
        assertEquals(List.of(1, 0), List.of(again.get("assignmentsChanged").asInt(), again.get("failed").size()));
    }
}
