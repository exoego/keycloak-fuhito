package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.holding;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.holdingRealm;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.inBackground;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.update;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waiterOf;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waitingFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bulk write, such as an import, and the other writes of its realm wait for each other.
 */
class BulkWriteLocksIT {

    private static final String UNIT = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}, "
            + "\"validFrom\": \"2026-04-01\"}";

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return Api.json(response);
    }

    private static String id(HttpResponse<String> response) throws Exception {
        return ok(response).get("id").asText();
    }

    // The call must not wait for a lock. A call that waits would fail the test only after a long time.
    private static <T> T soon(Callable<T> call) throws Exception {
        return inBackground(call).get(30, TimeUnit.SECONDS);
    }

    private static String renameHierarchy(String hierarchy, String name) {
        return """
                {"effectiveDate": "2026-04-01", "hierarchies": [{"id": "%s", "name": "%s"}]}"""
                .formatted(hierarchy, name);
    }

    @Test
    void aWriteWaitsForAnImportAndThenSeesItsResult() throws Exception {
        TestRealm realm = TestRealm.create("bulk-import-first");
        Api api = realm.api;
        String hierarchy = id(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        String person = id(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Ann\"}}}"));

        CompletableFuture<HttpResponse<String>> imported;
        CompletableFuture<HttpResponse<String>> write;
        try (Connection first = holding("fuhito_hierarchy", hierarchy)) {
            // The import waits for the hierarchy before it gets to the person, and holds the realm meanwhile. It
            // already let go of its entities after the item.
            imported = waitingFor(first, () -> api.post("/imports", """
                    {"effectiveDate": "2026-04-01",
                     "refItems": [{"externalId": "m1", "kind": "JOB_TITLE", "code": "m1", "primaryLanguage": "ja", "names": {"ja": {"name": "課長"}}}],
                     "hierarchies": [{"id": "%s", "name": "Q"}],
                     "people": [{"id": "%s", "names": {"ja": {"name": "Bob"}}}]}""".formatted(hierarchy, person)));
            write = waitingFor(waiterOf(first), () -> api.put("/people/" + person,
                    "{\"version\": 1, \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Amy\"}}}"));
            first.commit();
        }

        ok(imported.get());
        // The write read the person after the import, so the version that it names is old.
        HttpResponse<String> conflict = write.get();
        assertEquals(409, conflict.statusCode(), conflict.body());
        assertEquals("2", Api.json(conflict).get("params").get("version").asText());
        assertEquals("Bob", ok(api.get("/people/" + person)).get("name").asText());
    }

    @Test
    void everyBulkWriteWaitsForAWriteThatRuns() throws Exception {
        TestRealm realm = TestRealm.withEmployer("bulk-after-write", Map.of("fuhito.sync.enabled", "true"));
        Api api = realm.api;
        String unit = id(api.post("/org-units", UNIT.formatted("A")));
        realm.refItem("WORK_LOCATION", "tokyo", "東京");
        // A write that can change the work location that applies to other units holds the realm alone too.
        String placed = id(api.post("/org-units", UNIT.formatted("B")));
        String child = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"C\"}}, \"parentId\": \"" + placed + "\", \"validFrom\": \"2026-04-01\"}"));
        String renamed = id(api.post("/org-units", UNIT.formatted("D")));
        JsonNode export = RealmExportIT.export(api);
        String definition = id(api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"note\", \"type\": \"STRING\"}"));
        record Bulk(String name, Callable<HttpResponse<String>> call) {
        }
        List<Bulk> bulkWrites = List.of(
                new Bulk("import", () -> api.post("/imports", """
                        {"effectiveDate": "2026-04-01", "hierarchies": [{"externalId": "p", "code": "p", "name": "P"}]}""")),
                new Bulk("dry run", () -> api.post("/imports", """
                        {"effectiveDate": "2026-04-01", "dryRun": true,
                         "hierarchies": [{"externalId": "q", "code": "q", "name": "Q"}]}""")),
                new Bulk("csv", () -> api.postBytes("/imports/csv/hierarchies?effectiveDate=2026-04-01",
                        "text/csv; charset=UTF-8", "externalId,code,name\r\nr,r,R\r\n".getBytes(StandardCharsets.UTF_8))),
                new Bulk("sync", () -> api.post("/attribute-sync/run", "")),
                new Bulk("fill", () -> api.post("/attribute-definitions", "{\"target\": \"PERSON\", "
                        + "\"name\": \"shift\", \"type\": \"STRING\", \"required\": true, \"fillValue\": \"day\"}")),
                new Bulk("clear", () -> api.deleteWithVersion("/attribute-definitions/" + definition
                        + "?clearValues=true")),
                new Bulk("work location", () -> api.putWithVersion("/org-units/" + placed, "{\"primaryLanguage\": "
                        + "\"ja\", \"names\": {\"ja\": {\"name\": \"B\"}}, \"workLocation\": \"tokyo\", "
                        + "\"validFrom\": \"2026-04-01\"}")),
                new Bulk("move", () -> api.putWithVersion("/org-units/" + child + "/parent",
                        "{\"validFrom\": \"2026-04-01\"}")),
                new Bulk("reorganization", () -> api.postWithVersion("/reorganizations", """
                        {"effectiveDate": "2026-04-01", "steps": [{"op": "move", "id": "%s", "parentId": "%s"}]}"""
                        .formatted(child, placed))),
                new Bulk("restore", () -> api.post("/restore?replace=true", export.toString())));

        for (Bulk bulk : bulkWrites) {
            CompletableFuture<HttpResponse<String>> write;
            CompletableFuture<HttpResponse<String>> bulkWrite;
            try (Connection first = holding("fuhito_org_unit", unit)) {
                // The write of the unit waits for its row, and holds its share of the realm meanwhile.
                write = waitingFor(first, () -> api.putWithVersion("/org-units/" + unit, UNIT.formatted(bulk.name())));
                // The other writes share the realm and go on. So does a change of details that keeps the places.
                assertEquals(201, soon(() -> api.post("/org-units", UNIT.formatted(bulk.name()))).statusCode(),
                        bulk.name());
                assertEquals(200, soon(() -> api.putWithVersion("/org-units/" + renamed,
                        UNIT.formatted(bulk.name()))).statusCode(), bulk.name());
                bulkWrite = waitingFor(waiterOf(first), bulk.call());
                first.commit();
            }
            ok(write.get());
            ok(bulkWrite.get());
        }
        // The restore came last, and put back the hierarchies of the export.
        assertEquals(List.of("default"), ok(api.get("/hierarchies")).findValuesAsText("code"));
    }

    @Test
    void twoImportsRunOneAfterTheOther() throws Exception {
        TestRealm realm = TestRealm.create("bulk-two-imports");
        Api api = realm.api;
        String hierarchy = id(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));

        CompletableFuture<HttpResponse<String>> firstImport;
        CompletableFuture<HttpResponse<String>> secondImport;
        try (Connection first = holding("fuhito_hierarchy", hierarchy)) {
            firstImport = waitingFor(first, () -> api.post("/imports", renameHierarchy(hierarchy, "Q")));
            // The second import changes another hierarchy, and still waits for the first import.
            secondImport = waitingFor(waiterOf(first), () -> api.post("/imports", """
                    {"effectiveDate": "2026-04-01", "hierarchies": [{"externalId": "m", "code": "matrix", "name": "M"}]}"""));
            first.commit();
        }

        ok(firstImport.get());
        ok(secondImport.get());
        assertEquals("Q", ok(api.get("/hierarchies/" + hierarchy)).get("name").asText());
    }

    @Test
    void aMaskThatEmptiesTheNameWaitsForAnImportAndHoldsTheUserMeanwhile() throws Exception {
        TestRealm realm = TestRealm.create("bulk-mask");
        Api api = realm.api;
        String hierarchy = id(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        String user = realm.user("leaver", "Taro", "Sato");
        String person = id(api.post("/people", "{\"userId\": \"" + user + "\"}"));

        CompletableFuture<HttpResponse<String>> imported;
        CompletableFuture<HttpResponse<String>> mask;
        try (Connection first = holding("fuhito_hierarchy", hierarchy)) {
            imported = waitingFor(first, () -> api.post("/imports", renameHierarchy(hierarchy, "Q")));
            mask = waitingFor(waiterOf(first), () -> api.postWithVersion("/people/" + person
                    + "/mask?clearKeycloakName=true", ""));
            // So a change of the user in Keycloak waits for the import too.
            assertTrue(KeycloakEnvironment.sqlError("select id from user_entity where id = '" + user
                    + "' for no key update nowait").contains("could not obtain lock"));
            first.commit();
        }

        ok(imported.get());
        assertTrue(ok(mask.get()).get("keycloakNameCleared").asBoolean());
        assertEquals(List.of("", ""), realm.nameOfUser(user));
        assertEquals("Q", ok(api.get("/hierarchies/" + hierarchy)).get("name").asText());
    }

    // The mask reads the user of the person before it locks anything. A restore keeps the versions of its export, so
    // it can link the person to another user meanwhile, with the same version or another one.
    @Test
    void aMaskFailsWhenTheLinkOfThePersonChangesWhileItWaits() throws Exception {
        for (boolean countUp : List.of(true, false)) {
            TestRealm realm = TestRealm.create("bulk-mask-link");
            Api api = realm.api;
            String linked = realm.user("linked", "Taro", "Sato");
            String other = realm.user("other", "Hanako", "Suzuki");
            String person = id(api.post("/people", "{\"userId\": \"" + linked + "\"}"));

            CompletableFuture<HttpResponse<String>> mask;
            try (Connection bulk = holdingRealm(realm.id(), true)) {
                mask = waitingFor(bulk, () -> api.postWithVersion("/people/" + person
                        + "/mask?clearKeycloakName=true", ""));
                update(bulk, "update fuhito_person set user_id = '" + other + "'"
                        + (countUp ? ", version = version + 1" : "") + " where id = ?", person);
                bulk.commit();
            }

            HttpResponse<String> conflict = mask.get();
            assertEquals(409, conflict.statusCode(), conflict.body());
            JsonNode error = Api.json(conflict);
            assertEquals("version.conflict", error.get("error").asText());
            assertEquals(countUp ? "2" : null, error.path("params").path("version").textValue());
            assertEquals(List.of("Taro", "Sato"), realm.nameOfUser(linked));
            assertEquals(List.of("Hanako", "Suzuki"), realm.nameOfUser(other));
        }
    }

    @Test
    void aPieceOfTheSyncKeepsTheOtherWritesOfTheRealmWaiting() throws Exception {
        TestRealm realm = TestRealm.withEmployer("bulk-sync", Map.of("fuhito.sync.enabled", "true"));
        Api api = realm.api;
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        // Without this, the user profile of Keycloak drops attributes that it does not know.
        UPConfig profile = admin.realm(realm.name).users().userProfile().getConfiguration();
        profile.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        admin.realm(realm.name).users().userProfile().update(profile);
        ok(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"内科\"}}, "
                + "\"code\": \"naika\", \"validFrom\": \"2020-04-01\"}"));
        var user = new UserRepresentation();
        user.setUsername("alice");
        user.setEnabled(true);
        user.singleAttribute("department", "naika");
        String alice;
        try (Response response = admin.realm(realm.name).users().create(user)) {
            alice = CreatedResponseUtil.getCreatedId(response);
        }
        ok(api.post("/attribute-sync/run", ""));
        String assignment = ok(api.get("/users/" + alice + "/assignments")).get(0).get("assignmentId").asText();
        realm.refItem("JOB_TITLE", "head", "院長");
        user = admin.realm(realm.name).users().get(alice).toRepresentation();
        user.singleAttribute("title", "院長");
        admin.realm(realm.name).users().get(alice).update(user);

        CompletableFuture<HttpResponse<String>> sync;
        CompletableFuture<HttpResponse<String>> write;
        try (Connection first = KeycloakEnvironment.connection()) {
            first.setAutoCommit(false);
            update(first, "update fuhito_assignment set version = version where assignment_id = ?", assignment);
            // The piece of alice waits for her assignment, and holds the realm meanwhile.
            sync = waitingFor(first, () -> api.post("/attribute-sync/run", ""));
            write = waitingFor(waiterOf(first), () -> api.post("/org-units", UNIT.formatted("B")));
            first.commit();
        }

        assertEquals(1, ok(sync.get()).get("assignmentsChanged").asInt());
        ok(write.get());
    }

    @Test
    void theOtherWritesOfTheRealmWaitForAnImportButReadsAndOtherRealmsDoNot() throws Exception {
        TestRealm realm = TestRealm.withEmployer("bulk-others");
        TestRealm other = TestRealm.create("bulk-others");
        Api api = realm.api;
        String hierarchy = id(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        String unit = id(api.post("/org-units", UNIT.formatted("A")));
        String organization = realm.organization("bulk-others");
        ok(api.putWithVersion("/org-units/" + unit + "/organization", "{\"organizationId\": \"" + organization + "\"}"));
        String user = realm.user("leaver");
        String employment = id(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2020-04-01\"}"));

        CompletableFuture<HttpResponse<String>> imported;
        CompletableFuture<HttpResponse<String>> write;
        CompletableFuture<HttpResponse<String>> userRemoval;
        CompletableFuture<HttpResponse<String>> organizationRemoval;
        try (Connection first = holding("fuhito_hierarchy", hierarchy)) {
            imported = waitingFor(first, () -> api.post("/imports", renameHierarchy(hierarchy, "Q")));
            String importer = waiterOf(first);

            assertEquals(200, soon(() -> api.get("/org-units/" + unit)).statusCode());
            assertEquals(201, soon(() -> other.api.post("/org-units", UNIT.formatted("B"))).statusCode());

            write = waitingFor(importer, () -> api.post("/org-units", UNIT.formatted("C")));
            userRemoval = waitingFor(importer, () -> {
                realm.removeUser(user);
                return null;
            });
            organizationRemoval = waitingFor(importer, () -> {
                KEYCLOAK.getKeycloakAdminClient().realm(realm.name).organizations().get(organization).delete()
                        .close();
                return null;
            });
            first.commit();
        }

        ok(imported.get());
        ok(write.get());
        userRemoval.get();
        organizationRemoval.get();
        // The removals came after the import, and wrote over what it left.
        assertTrue(ok(api.get("/employments/" + employment)).has("validTo"));
        assertFalse(ok(api.get("/org-units/" + unit + "?asOf=2026-04-01")).has("keycloakOrganizationId"));
    }

    @Test
    void theFirstWritesOfARealmAddItsRowOnce() throws Exception {
        TestRealm realm = TestRealm.create("bulk-first-write");
        Api api = realm.api;
        String rows = "select count(*) from fuhito_realm_write where realm_id = '" + realm.id() + "'";
        assertEquals("1", KeycloakEnvironment.sql(rows));

        for (boolean commit : List.of(false, true)) {
            // A realm from before the row has none.
            KeycloakEnvironment.sql("delete from fuhito_realm_write where realm_id = '" + realm.id() + "'");
            CompletableFuture<HttpResponse<String>> write;
            try (Connection first = KeycloakEnvironment.connection()) {
                // Another first write added the row and has not ended yet.
                first.setAutoCommit(false);
                update(first, "insert into fuhito_realm_write (realm_id) values (?)", realm.id());
                write = waitingFor(first, () -> api.post("/org-units", UNIT.formatted("A")));
                if (commit) {
                    first.commit();
                } else {
                    first.rollback();
                }
            }
            ok(write.get());
            assertEquals("1", KeycloakEnvironment.sql(rows));
        }
    }
}
