package net.exoego.keycloak.fuhito;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaIT {

    @Test
    void liquibaseCreatesTheTable() throws Exception {
        assertEquals("1", KeycloakEnvironment.sql(
                "select count(*) from information_schema.tables where table_name = 'fuhito_hierarchy'"));
    }

    // These rows go around the application, as a bug in it would.
    @Test
    void theDatabaseRejectsOverlappingVersionsOfOneUnit() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_org_unit (id, realm_id) values ('ex-u1', 'ex-realm'), ('ex-u2', 'ex-realm')");
        String insert = "insert into fuhito_org_unit_version (id, realm_id, org_unit_id, valid_from, valid_to,"
                + " name, names_i18n, short_names_i18n, attributes) values ";
        KeycloakEnvironment.sql(insert + "('ex-v1', 'ex-realm', 'ex-u1', '2026-04-01', '2027-04-01', 'a', '{}', '{}', '{}')");

        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-v2', 'ex-realm', 'ex-u1', '2027-03-31', null, 'b', '{}', '{}', '{}')")
                .contains("ex_fuhito_org_unit_version_period"));

        // The next period may start on the end date, and another unit may use the same dates.
        KeycloakEnvironment.sql(insert + "('ex-v3', 'ex-realm', 'ex-u1', '2027-04-01', null, 'c', '{}', '{}', '{}')");
        KeycloakEnvironment.sql(insert + "('ex-v4', 'ex-realm', 'ex-u2', '2026-04-01', null, 'd', '{}', '{}', '{}')");
    }

    @Test
    void theDatabaseRejectsTwoParentsAtTheSameTime() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_hierarchy (id, realm_id, code, name)"
                + " values ('ex-h1', 'ex-realm-2', 'default', 'Default'), ('ex-h2', 'ex-realm-2', 'other', 'Other')");
        KeycloakEnvironment.sql("insert into fuhito_org_unit (id, realm_id)"
                + " values ('ex-c', 'ex-realm-2'), ('ex-p1', 'ex-realm-2'), ('ex-p2', 'ex-realm-2')");
        String insert = "insert into fuhito_org_unit_link (id, realm_id, hierarchy_id, child_id, parent_id,"
                + " valid_from, valid_to) values ";
        KeycloakEnvironment.sql(insert + "('ex-l1', 'ex-realm-2', 'ex-h1', 'ex-c', 'ex-p1', '2026-04-01', null)");

        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-l2', 'ex-realm-2', 'ex-h1', 'ex-c', 'ex-p2', '2030-01-01', '2031-01-01')")
                .contains("ex_fuhito_org_unit_link_period"));

        // The same unit may have another parent in another hierarchy.
        KeycloakEnvironment.sql(insert + "('ex-l3', 'ex-realm-2', 'ex-h2', 'ex-c', 'ex-p2', '2026-04-01', null)");
    }
}
