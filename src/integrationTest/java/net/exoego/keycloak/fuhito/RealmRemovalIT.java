package net.exoego.keycloak.fuhito;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RealmRemovalIT {

    private static final List<String> TABLES = List.of("fuhito_hierarchy", "fuhito_ref_item", "fuhito_org_unit",
            "fuhito_org_unit_version", "fuhito_org_unit_link");

    // A new table must be added to this test and to the cleanup. This test fails until that is done.
    @Test
    void thisTestKnowsEveryTable() throws Exception {
        String tables = KeycloakEnvironment.sql("select string_agg(table_name, ',' order by table_name)"
                + " from information_schema.columns where table_name like 'fuhito\\_%' and column_name = 'realm_id'");

        assertEquals(TABLES.stream().sorted().toList(), List.of(tables.split(",")));
    }

    @Test
    void removingARealmDeletesItsRowsAndNoOthers() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String token = admin.tokenManager().getAccessTokenString();
        String removedRealm = createRealm(admin);
        String keptRealm = createRealm(admin);
        fill(new Api(removedRealm, token));
        fill(new Api(keptRealm, token));
        String removedId = admin.realm(removedRealm).toRepresentation().getId();
        String keptId = admin.realm(keptRealm).toRepresentation().getId();
        for (String table : TABLES) {
            assertEquals(rows(table, keptId), rows(table, removedId), table);
        }
        assertEquals("2", rows("fuhito_org_unit", removedId));

        admin.realm(removedRealm).remove();

        for (String table : TABLES) {
            assertEquals("0", rows(table, removedId), table);
        }
        assertEquals("1", rows("fuhito_hierarchy", keptId));
        assertEquals("1", rows("fuhito_ref_item", keptId));
        assertEquals("2", rows("fuhito_org_unit", keptId));
        assertEquals("2", rows("fuhito_org_unit_version", keptId));
        assertEquals("1", rows("fuhito_org_unit_link", keptId));
    }

    private static String createRealm(Keycloak admin) {
        var realm = new RealmRepresentation();
        realm.setRealm("removal-" + UUID.randomUUID());
        realm.setEnabled(true);
        admin.realms().create(realm);
        return realm.getRealm();
    }

    private static void fill(Api api) throws Exception {
        assertEquals(201, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"hospital\", \"name\": \"Hospital\"}").statusCode());
        var parent = api.post("/org-units", "{\"name\": \"P\", \"unitType\": \"hospital\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, parent.statusCode(), parent.body());
        assertEquals(201, api.post("/org-units", "{\"name\": \"C\", \"parentId\": \""
                + Api.json(parent).get("id").asText() + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }
}
