package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.holding;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.sql;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waiterOf;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waitingFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RealmRemovalIT {

    private static final List<String> TABLES = List.of("fuhito_hierarchy", "fuhito_ref_item", "fuhito_ref_item_name",
            "fuhito_attribute_definition", "fuhito_ref_kind", "fuhito_ref_kind_name", "fuhito_external_id", "fuhito_job_run", "fuhito_org_unit",
            "fuhito_org_unit_version", "fuhito_org_unit_version_name", "fuhito_org_unit_link", "fuhito_org_unit_succession", "fuhito_employment", "fuhito_employment_terms",
            "fuhito_assignment", "fuhito_change_order", "fuhito_change_log", "fuhito_change_log_counter", "fuhito_qualification",
            "fuhito_reporting_line", "fuhito_unit_role", "fuhito_user_mask", "fuhito_person", "fuhito_person_name", "fuhito_erasure",
            "fuhito_projected_member", "fuhito_realm_write");

    private static final String UNIT = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"%s\"}}, "
            + "\"validFrom\": \"2026-04-01\"}";

    // A new table must be added to this test and to the cleanup. This test fails until that is done.
    @Test
    void thisTestKnowsEveryTable() throws Exception {
        String tables = KeycloakEnvironment.sql("select string_agg(table_name, ',' order by table_name)"
                + " from information_schema.columns where table_name like 'fuhito\\_%' and column_name = 'realm_id'");

        assertEquals(TABLES.stream().sorted().toList(), List.of(tables.split(",")));
    }

    @Test
    void removingARealmDeletesItsRowsAndNoOthers() throws Exception {
        TestRealm removed = TestRealm.withEmployer("removal", Map.of("fuhito.projection.enabled", "true"));
        TestRealm kept = TestRealm.withEmployer("removal", Map.of("fuhito.projection.enabled", "true"));
        fill(removed);
        fill(kept);
        String removedId = removed.id();
        String keptId = kept.id();
        for (String table : TABLES) {
            assertEquals(rows(table, keptId), rows(table, removedId), table);
            assertNotEquals("0", rows(table, removedId), table);
        }

        removed.remove();

        for (String table : TABLES) {
            assertEquals("0", rows(table, removedId), table);
            assertNotEquals("0", rows(table, keptId), table);
        }
    }

    @Test
    void aWriteThatWaitsForTheRemovalOfItsRealmFailsAndLeavesNoRows() throws Exception {
        record Write(String name, Function<Api, Callable<HttpResponse<String>>> call) {
        }
        List<Write> writes = List.of(
                new Write("write", api -> () -> api.post("/org-units", UNIT.formatted("A"))),
                new Write("bulk write", api -> () -> api.post("/imports", """
                        {"effectiveDate": "2026-04-01", "hierarchies": [{"externalId": "p", "code": "p", "name": "P"}]}""")));
        for (Write write : writes) {
            // A realm from before the row has none, and its first write adds it.
            for (boolean withRow : List.of(true, false)) {
                String name = write.name() + (withRow ? "" : " of a realm without its row");
                TestRealm realm = TestRealm.create("removal-wait");
                String realmId = realm.id();
                if (!withRow) {
                    sql("delete from fuhito_realm_write where realm_id = '" + realmId + "'");
                }
                String hierarchy = sql("select id from fuhito_hierarchy where realm_id = '" + realmId + "'");
                CompletableFuture<HttpResponse<String>> removal;
                CompletableFuture<HttpResponse<String>> late;
                try (Connection first = holding("fuhito_hierarchy", hierarchy)) {
                    // The removal deleted the row of the realm, and waits for the hierarchy.
                    removal = waitingFor(first, () -> {
                        realm.remove();
                        return null;
                    });
                    late = waitingFor(waiterOf(first), write.call().apply(realm.api));
                    first.commit();
                }

                removal.get();
                HttpResponse<String> response = late.get();
                for (String table : TABLES) {
                    assertEquals("0", rows(table, realmId), name + " " + table);
                }
                assertEquals(404, response.statusCode(), name + " " + response.body());
                assertEquals("realm.not_found", Api.json(response).get("error").asText(), name);
            }
        }
    }

    @Test
    void theRemovalWaitsForTheFirstWriteOfARealmWithoutItsRowAndDeletesWhatItWrote() throws Exception {
        TestRealm realm = TestRealm.create("removal-after-write");
        Api api = realm.api;
        String realmId = realm.id();
        var created = api.post("/org-units", UNIT.formatted("A"));
        assertEquals(201, created.statusCode(), created.body());
        String unit = Api.json(created).get("id").asText();
        sql("delete from fuhito_realm_write where realm_id = '" + realmId + "'");

        CompletableFuture<HttpResponse<String>> write;
        CompletableFuture<HttpResponse<String>> removal;
        try (Connection first = holding("fuhito_org_unit", unit)) {
            // The write added the row of the realm, and waits for the unit.
            write = waitingFor(first, () -> api.putWithVersion("/org-units/" + unit, UNIT.formatted("B")));
            removal = waitingFor(waiterOf(first), () -> {
                realm.remove();
                return null;
            });
            first.commit();
        }

        HttpResponse<String> written = write.get();
        assertEquals(200, written.statusCode(), written.body());
        removal.get();
        for (String table : TABLES) {
            assertEquals("0", rows(table, realmId), table);
        }
    }

    // Puts at least one row into every table.
    private static void fill(TestRealm realm) throws Exception {
        Api api = realm.api;
        realm.refItem("UNIT_TYPE", "hospital", "Hospital");
        var parent = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"P\"}}, \"unitType\": \"hospital\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, parent.statusCode(), parent.body());
        assertEquals(201, api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"C\"}}, \"parentId\": \""
                + Api.json(parent).get("id").asText() + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        var old = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Old\"}}, \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, old.statusCode(), old.body());
        var succession = api.postWithVersion("/reorganizations", "{\"effectiveDate\": \"2027-04-01\", \"steps\": [{\"op\": "
                + "\"succeed\", \"id\": \"" + Api.json(old).get("id").asText() + "\", \"successorIds\": [\""
                + Api.json(parent).get("id").asText() + "\"]}]}");
        assertEquals(200, succession.statusCode(), succession.body());
        assertEquals(201, api.post("/change-orders",
                "{\"title\": \"Order\", \"effectiveDate\": \"2026-04-01\"}").statusCode());
        assertEquals(201, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Visitor\"}}}").statusCode());
        var erased = api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Erased\"}}}");
        assertEquals(201, erased.statusCode(), erased.body());
        assertEquals(204, api.deleteWithVersion("/people/" + Api.json(erased).get("id").asText() + "/data").statusCode());
        String staff = realm.user("staff");
        realm.refItem("QUALIFICATION", "license", "License");
        assertEquals(201, api.post("/qualifications", "{\"userId\": \"" + staff
                + "\", \"kind\": \"license\", \"validFrom\": \"2020-04-01\"}").statusCode());
        String former = realm.user("former");
        assertEquals(201, api.post("/employments", "{\"userId\": \"" + former
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2021-04-01\"}").statusCode());
        assertEquals(200, api.post("/users/" + former + "/mask", "").statusCode());
        var employment = api.post("/employments", "{\"userId\": \"" + staff
                + "\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, employment.statusCode(), employment.body());
        var assignment = api.post("/assignments", "{\"employmentId\": \"" + Api.json(employment).get("id").asText()
                + "\", \"orgUnitId\": \"" + Api.json(parent).get("id").asText()
                + "\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, assignment.statusCode(), assignment.body());
        assertEquals(201, api.post("/reporting-lines", "{\"assignmentId\": \"" + Api.json(assignment).get("id").asText()
                + "\", \"managerUserId\": \"" + realm.user("manager") + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(201, api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"note\", \"type\": \"STRING\"}").statusCode());
        assertEquals(200, api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \""
                + Api.json(parent).get("id").asText() + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}")
                .statusCode());
        assertEquals(201, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Guest\"}}}").statusCode());
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"Site\"}}}").statusCode());
        // The rebuild records its run, and the staff as a member that it added to the organization.
        assertEquals(200, api.putWithVersion("/org-units/" + Api.json(parent).get("id").asText() + "/organization",
                "{\"organizationId\": \"" + realm.organization("removal") + "\"}").statusCode());
        var rebuilt = api.post("/projection/rebuild", "");
        assertEquals(200, rebuilt.statusCode(), rebuilt.body());
        realm.refItem("UNIT_ROLE", "approver", "Approver");
        assertEquals(201, api.post("/unit-roles", "{\"employmentId\": \"" + Api.json(employment).get("id").asText()
                + "\", \"orgUnitId\": \"" + Api.json(parent).get("id").asText()
                + "\", \"role\": \"approver\", \"validFrom\": \"2026-04-01\"}").statusCode());
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }
}
