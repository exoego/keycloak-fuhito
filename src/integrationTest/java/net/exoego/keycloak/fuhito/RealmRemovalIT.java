package net.exoego.keycloak.fuhito;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RealmRemovalIT {

    private static final List<String> TABLES = List.of("fuhito_hierarchy", "fuhito_ref_item", "fuhito_ref_item_name",
            "fuhito_attribute_definition", "fuhito_ref_kind", "fuhito_external_id", "fuhito_idempotency_key", "fuhito_job_run", "fuhito_org_unit",
            "fuhito_org_unit_version", "fuhito_org_unit_link", "fuhito_org_unit_succession", "fuhito_employment", "fuhito_employment_terms",
            "fuhito_assignment", "fuhito_change_order", "fuhito_change_log", "fuhito_change_log_counter", "fuhito_qualification",
            "fuhito_reporting_line", "fuhito_unit_role", "fuhito_user_mask", "fuhito_person", "fuhito_erasure");

    // A new table must be added to this test and to the cleanup. This test fails until that is done.
    @Test
    void thisTestKnowsEveryTable() throws Exception {
        String tables = KeycloakEnvironment.sql("select string_agg(table_name, ',' order by table_name)"
                + " from information_schema.columns where table_name like 'fuhito\\_%' and column_name = 'realm_id'");

        assertEquals(TABLES.stream().sorted().toList(), List.of(tables.split(",")));
    }

    @Test
    void removingARealmDeletesItsRowsAndNoOthers() throws Exception {
        TestRealm removed = TestRealm.withEmployer("removal");
        TestRealm kept = TestRealm.withEmployer("removal");
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

    // Puts at least one row into every table.
    private static void fill(TestRealm realm) throws Exception {
        Api api = realm.api;
        realm.refItem("UNIT_TYPE", "hospital", "Hospital");
        var parent = api.post("/org-units", "{\"name\": \"P\", \"unitType\": \"hospital\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, parent.statusCode(), parent.body());
        assertEquals(201, api.post("/org-units", "{\"name\": \"C\", \"parentId\": \""
                + Api.json(parent).get("id").asText() + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        var old = api.post("/org-units", "{\"name\": \"Old\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, old.statusCode(), old.body());
        var succession = api.postWithVersion("/reorganizations", "{\"effectiveDate\": \"2027-04-01\", \"steps\": [{\"op\": "
                + "\"succeed\", \"id\": \"" + Api.json(old).get("id").asText() + "\", \"successorIds\": [\""
                + Api.json(parent).get("id").asText() + "\"]}]}");
        assertEquals(200, succession.statusCode(), succession.body());
        assertEquals(201, api.post("/change-orders",
                "{\"title\": \"Order\", \"effectiveDate\": \"2026-04-01\"}").statusCode());
        assertEquals(201, api.post("/people", "{\"name\": \"Visitor\"}").statusCode());
        var erased = api.post("/people", "{\"name\": \"Erased\"}");
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
        assertEquals(201, api.post("/people", "{\"name\": \"Guest\"}", "Idempotency-Key", "guest-1").statusCode());
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"site\", \"name\": \"Site\"}").statusCode());
        // The projection is off in this realm, so the rebuild fails, and its run is recorded.
        assertEquals(409, api.post("/projection/rebuild", "").statusCode());
        realm.refItem("UNIT_ROLE", "approver", "Approver");
        assertEquals(201, api.post("/unit-roles", "{\"employmentId\": \"" + Api.json(employment).get("id").asText()
                + "\", \"orgUnitId\": \"" + Api.json(parent).get("id").asText()
                + "\", \"role\": \"approver\", \"validFrom\": \"2026-04-01\"}").statusCode());
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }
}
