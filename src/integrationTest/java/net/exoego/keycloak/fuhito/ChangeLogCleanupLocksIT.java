package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.opentest4j.AssertionFailedError;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.connection;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.deadlocksSince;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.holdingRealm;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.logLength;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.sql;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waiterOf;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waitingFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cleanup of the change log and the other work of a realm. The shared Keycloak runs the cleanup every few
 * seconds.
 */
class ChangeLogCleanupLocksIT {

    private static final String KEEP = "fuhito.changeLog.keepPropagatedDays";

    // A propagated change from 60 days ago, a few milliseconds earlier for a larger number.
    private static String propagated(Connection connection, String realmId, int sequence) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into fuhito_change_log (id, realm_id, sequence_number, recorded_at, kind, resource_type,
                  resource_id, operation, resource_path, cause)
                values (?, ?, ?, localtimestamp - interval '60 days' - ? * interval '1 millisecond', 'PROPAGATED',
                  'FUHITO_ORG_UNIT', ?, 'UPDATE', 'fuhito/org-units/' || ?, 'KEYCLOAK_ORGANIZATION_REMOVED')""")) {
            insert.setString(1, id);
            insert.setString(2, realmId);
            insert.setInt(3, sequence);
            insert.setInt(4, sequence);
            insert.setString(5, Integer.toString(sequence));
            insert.setString(6, Integer.toString(sequence));
            insert.executeUpdate();
        }
        return id;
    }

    // Three propagated changes B, P, and A. The cleanup meets them by their time, A first. The removal of the realm
    // meets them by the order of the table, of their numbers, or of their resources, B first.
    private static List<String> backwards(String realmId) throws Exception {
        String inTable = "select string_agg(sequence_number::text, ',' order by ctid) from fuhito_change_log"
                + " where realm_id = '" + realmId + "'";
        for (int attempt = 0; attempt < 5; attempt++) {
            sql("delete from fuhito_change_log where realm_id = '" + realmId + "'");
            try (Connection connection = connection()) {
                List<String> ids = List.of(propagated(connection, realmId, 1), propagated(connection, realmId, 2),
                        propagated(connection, realmId, 3));
                // A new row can take the place of a removed one, before the rows of earlier inserts.
                if (sql(inTable).equals("1,2,3")) {
                    return ids;
                }
            }
        }
        throw new AssertionFailedError("the table never kept the order of the inserts");
    }

    private static void keep(TestRealm realm, String days) {
        RealmResource resource = KEYCLOAK.getKeycloakAdminClient().realm(realm.name);
        var update = new RealmRepresentation();
        update.setAttributes(new HashMap<>(resource.toRepresentation().getAttributes()));
        update.getAttributes().put(KEEP, days);
        resource.update(update);
    }

    // The timer starts the cleanup, so the test waits until it waits for the connection.
    private static String cleanupWaitingFor(Connection holder) throws Exception {
        for (int i = 0; i < 300; i++) {
            String pid = waiterOf(holder);
            if (!pid.isEmpty()) {
                return pid;
            }
            Thread.sleep(100);
        }
        throw new AssertionFailedError("the cleanup never waited");
    }

    private static String rows(String table, String realmId) throws Exception {
        return sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }

    // A connection that holds the row of the realm as the removal of the realm does. The cleanup of the realm waits
    // for it before anything else.
    private static Connection removing(String realmId) throws Exception {
        Connection connection = connection();
        connection.setAutoCommit(false);
        try (PreparedStatement lock = connection.prepareStatement(
                "select realm_id from fuhito_realm_write where realm_id = ? for update")) {
            lock.setString(1, realmId);
            try (ResultSet row = lock.executeQuery()) {
                assertTrue(row.next());
            }
        }
        return connection;
    }

    // The holder of the realm that a run of the cleanup comes to first.
    private static int firstWaitedFor(List<Connection> holders) throws Exception {
        for (int i = 0; i < 300; i++) {
            for (int holder = 0; holder < holders.size(); holder++) {
                if (!waiterOf(holders.get(holder)).isEmpty()) {
                    return holder;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionFailedError("the cleanup never waited");
    }

    // For a realm with no writes that keeps no propagated change.
    private static void removedByTheCleanup(String realmId) throws Exception {
        for (int i = 0; i < 300 && !rows("fuhito_change_log", realmId).equals("0"); i++) {
            Thread.sleep(100);
        }
        assertEquals("0", rows("fuhito_change_log", realmId));
    }

    private static void addPropagated(String realmId, int sequence) throws Exception {
        try (Connection connection = connection()) {
            propagated(connection, realmId, sequence);
        }
    }

    // The same ID and name, with no setting of the days.
    private static Api madeAgain(Keycloak admin, String name, String id) {
        var realm = new RealmRepresentation();
        realm.setId(id);
        realm.setRealm(name);
        realm.setEnabled(true);
        realm.setOrganizationsEnabled(true);
        TestRealm.withTimeZone(realm);
        TestRealm.createRealm(admin, realm);
        return new Api(name, admin.tokenManager().getAccessTokenString());
    }

    // Links a unit to a Keycloak organization and removes the organization.
    private static void propagatedByKeycloak(Keycloak admin, String realm, Api api) throws Exception {
        String organization = OrganizationLinkIT.createOrganization(admin, realm, "o" + UUID.randomUUID());
        HttpResponse<String> unit = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"病院\"}}, \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, unit.statusCode(), unit.body());
        assertEquals(200, api.putWithVersion("/org-units/" + Api.json(unit).get("id").asText() + "/organization",
                "{\"organizationId\": \"" + organization + "\"}").statusCode());
        admin.realm(realm).organizations().get(organization).delete().close();
    }

    // Each would hold one entry that the other wants, if both took the entries first.
    @Test
    void theCleanupAndTheRemovalOfTheRealmMeetTheEntriesInTurn() throws Exception {
        TestRealm realm = TestRealm.create("cleanup-removal");
        String realmId = realm.id();
        List<String> entries = backwards(realmId);
        int log = logLength();

        CompletableFuture<Integer> removal;
        try (Connection holder = connection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "select id from fuhito_change_log where id = ? for update")) {
                lock.setString(1, entries.get(1));
                try (ResultSet row = lock.executeQuery()) {
                    assertTrue(row.next());
                }
            }
            keep(realm, "30");
            // The cleanup removed A, and waits for P.
            String cleanup = cleanupWaitingFor(holder);
            String query = sql("select query from pg_stat_activity where pid = " + cleanup);
            assertTrue(query.toLowerCase(Locale.ROOT).contains("recorded_at"), query);
            // The removal waits for the row of the realm, which the cleanup holds.
            removal = waitingFor(cleanup, () -> {
                try {
                    realm.remove();
                    return 204;
                } catch (WebApplicationException e) {
                    return e.getResponse().getStatus();
                }
            });
            holder.commit();
        }

        assertEquals(204, removal.get(60, TimeUnit.SECONDS));
        assertEquals(0, deadlocksSince(log));
        for (String table : List.of("fuhito_change_log", "fuhito_change_log_counter", "fuhito_realm_write")) {
            assertEquals("0", rows(table, realmId), table);
        }
    }

    // A bulk write, such as an import, holds the row of the realm alone.
    @Test
    void theCleanupDoesNotWaitForABulkWrite() throws Exception {
        TestRealm realm = TestRealm.create("cleanup-bulk", Map.of(KEEP, "30"));
        String realmId = realm.id();
        try (Connection bulk = holdingRealm(realmId, true)) {
            try (Connection connection = connection()) {
                propagated(connection, realmId, 1);
            }
            for (int i = 0; i < 300 && !rows("fuhito_change_log", realmId).equals("0"); i++) {
                Thread.sleep(100);
            }
            assertEquals("0", rows("fuhito_change_log", realmId));
            bulk.commit();
        }
    }

    // A run lists the realms first. A realm can be removed and made again with the same ID before the run comes to
    // it, as an import that overwrites realms does.
    @Test
    void theCleanupKeepsWhatARealmMadeAgainKeeps() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        List<TestRealm> realms = List.of(TestRealm.create("cleanup-again", Map.of(KEEP, "0")),
                TestRealm.create("cleanup-again", Map.of(KEEP, "0")));
        List<String> ids = realms.stream().map(TestRealm::id).toList();
        // A run that started before the second realm was made would not come to it. The run that removes this entry
        // started after.
        addPropagated(ids.get(1), 1);
        removedByTheCleanup(ids.get(1));

        int first;
        Api again;
        try (Connection one = removing(ids.get(0)); Connection two = removing(ids.get(1))) {
            List<Connection> holders = List.of(one, two);
            // The run waits in the realm that it comes to first, and comes to the other one later.
            first = firstWaitedFor(holders);
            int later = 1 - first;
            holders.get(later).rollback();
            TestRealm removed = realms.get(later);
            removed.remove();
            again = madeAgain(admin, removed.name, ids.get(later));
            propagatedByKeycloak(admin, removed.name, again);
            // The run removes this entry when it goes on.
            addPropagated(ids.get(first), 2);
            holders.get(first).rollback();
        }
        removedByTheCleanup(ids.get(first));
        // Only the next run removes this entry. So the run that waited is over when it is gone.
        addPropagated(ids.get(first), 3);
        removedByTheCleanup(ids.get(first));

        assertEquals(List.of("WRITE", "WRITE", "PROPAGATED"),
                Api.json(again.get("/change-log")).findValuesAsText("kind"));
        assertEquals(200, again.get("/change-log?after=1").statusCode());
    }
}
