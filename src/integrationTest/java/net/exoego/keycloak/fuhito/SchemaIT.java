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
                + " attributes) values ";
        KeycloakEnvironment.sql(insert + "('ex-v1', 'ex-realm', 'ex-u1', '2026-04-01', '2027-04-01', '{}')");

        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-v2', 'ex-realm', 'ex-u1', '2027-03-31', null, '{}')")
                .contains("ex_fuhito_org_unit_version_period"));

        // The next period may start on the end date, and another unit may use the same dates.
        KeycloakEnvironment.sql(insert + "('ex-v3', 'ex-realm', 'ex-u1', '2027-04-01', null, '{}')");
        KeycloakEnvironment.sql(insert + "('ex-v4', 'ex-realm', 'ex-u2', '2026-04-01', null, '{}')");
    }

    @Test
    void theDatabaseRejectsASecondPrimaryNameOfOneVersion() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_org_unit (id, realm_id) values ('ex-u5', 'ex-realm-4')");
        KeycloakEnvironment.sql("insert into fuhito_org_unit_version (id, realm_id, org_unit_id, valid_from, attributes)"
                + " values ('ex-v5', 'ex-realm-4', 'ex-u5', '2026-04-01', '{}')");
        String insert = "insert into fuhito_org_unit_version_name (version_id, language, realm_id, is_primary, name)"
                + " values ";
        KeycloakEnvironment.sql(insert + "('ex-v5', 'ja', 'ex-realm-4', true, '営業部')");
        KeycloakEnvironment.sql(insert + "('ex-v5', 'en', 'ex-realm-4', false, 'Sales')");

        assertTrue(KeycloakEnvironment.sqlError(insert + "('ex-v5', 'zh-Hant', 'ex-realm-4', true, '營業部')")
                .contains("uk_fuhito_org_unit_version_name_primary"));
        assertTrue(KeycloakEnvironment.sqlError(insert + "('ex-v5', 'en', 'ex-realm-4', false, 'Sales 2')")
                .contains("pk_fuhito_org_unit_version_name"));
    }

    @Test
    void theDatabaseRejectsASecondPrimaryNameOfOnePerson() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_person (id, realm_id, attributes) values ('ex-person', 'ex-realm-5', '{}')");
        String insert = "insert into fuhito_person_name (person_id, language, realm_id, is_primary, name) values ";
        KeycloakEnvironment.sql(insert + "('ex-person', 'ja', 'ex-realm-5', true, '山田 花子')");
        KeycloakEnvironment.sql(insert + "('ex-person', 'en', 'ex-realm-5', false, 'Hanako Yamada')");

        assertTrue(KeycloakEnvironment.sqlError(insert + "('ex-person', 'zh-Hant', 'ex-realm-5', true, '山田花子')")
                .contains("uk_fuhito_person_name_primary"));
        assertTrue(KeycloakEnvironment.sqlError(insert + "('ex-person', 'en', 'ex-realm-5', false, 'Hanako')")
                .contains("pk_fuhito_person_name"));
    }

    @Test
    void theDatabaseRejectsOverlappingNamesOfOneReferenceItem() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_ref_item (id, realm_id, kind, code, attributes)"
                + " values ('ex-r1', 'ex-realm-3', 'JOB_TITLE', 'a', '{}')");
        String insert = "insert into fuhito_ref_item_name (id, realm_id, ref_item_id, valid_from, valid_to, name,"
                + " names_i18n) values ";
        KeycloakEnvironment.sql(insert + "('ex-n1', 'ex-realm-3', 'ex-r1', null, '2027-04-01', 'a', '{}')");

        // Names with no first date hold from the start, so they overlap any earlier names.
        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-n2', 'ex-realm-3', 'ex-r1', '2020-04-01', '2021-04-01', 'b', '{}')")
                .contains("ex_fuhito_ref_item_name_period"));

        KeycloakEnvironment.sql(insert + "('ex-n3', 'ex-realm-3', 'ex-r1', '2027-04-01', null, 'c', '{}')");
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
