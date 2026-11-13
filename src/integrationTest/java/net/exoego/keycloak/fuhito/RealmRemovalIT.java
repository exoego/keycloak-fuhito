package net.exoego.keycloak.fuhito;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RealmRemovalIT {

    private static final List<String> TABLES = List.of("fuhito_hierarchy", "fuhito_ref_item", "fuhito_org_unit",
            "fuhito_org_unit_version", "fuhito_org_unit_link", "fuhito_employment", "fuhito_employment_terms");

    // A new table must be added to this test and to the cleanup. This test fails until that is done.
    @Test
    void thisTestKnowsEveryTable() throws Exception {
        String tables = KeycloakEnvironment.sql("select string_agg(table_name, ',' order by table_name)"
                + " from information_schema.columns where table_name like 'fuhito\\_%' and column_name = 'realm_id'");

        assertEquals(TABLES.stream().sorted().toList(), List.of(tables.split(",")));
    }

    @Test
    void removingARealmDeletesItsRowsAndNoOthers() throws Exception {
        TestRealm removed = TestRealm.create("removal");
        TestRealm kept = TestRealm.create("removal");
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
        assertEquals(201, api.post("/employments", "{\"userId\": \"" + realm.user("staff")
                + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }
}
