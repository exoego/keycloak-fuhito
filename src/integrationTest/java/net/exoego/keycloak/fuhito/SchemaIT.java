package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

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

    // An import into a new realm writes its rows in one transaction, so the statistics still say that the realm has
    // no rows. Nothing is committed here.
    @Test
    void aLookupOfAnExternalIdUsesTheKeyWithItAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    insert into fuhito_external_id (id, realm_id, resource_kind, resource_id, source, external_id)
                    select 'ex-old-' || i, 'ex-old-realm', 'ORG_UNIT', 'ex-old-unit-' || i, 'sheet', 'U' || i
                    from generate_series(1, 20) i""");
            statement.execute("analyze fuhito_external_id");
            statement.execute("""
                    insert into fuhito_external_id (id, realm_id, resource_kind, resource_id, source, external_id)
                    select 'ex-new-' || i, 'ex-new-realm', 'ORG_UNIT', 'ex-new-unit-' || i, 'sheet', 'U' || i
                    from generate_series(1, 3000) i""");

            assertTrue(plan(statement, """
                    explain select * from fuhito_external_id where realm_id = 'ex-new-realm'
                    and resource_kind = 'ORG_UNIT' and source = 'sheet' and external_id = 'U7'""")
                    .contains("uk_fuhito_external_id_value"));
            assertTrue(plan(statement, """
                    explain select * from fuhito_external_id where realm_id = 'ex-new-realm'
                    and resource_kind = 'ORG_UNIT' and resource_id = 'ex-new-unit-7'""")
                    .contains("uk_fuhito_external_id_source"));
            connection.rollback();
        }
    }

    private static String plan(Statement statement, String explain) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rows = statement.executeQuery(explain)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
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
        String insert = "insert into fuhito_ref_item_name (period_id, language, realm_id, ref_item_id, valid_from,"
                + " valid_to, is_primary, name) values ";
        KeycloakEnvironment.sql(insert + "('ex-n1', 'ja', 'ex-realm-3', 'ex-r1', null, '2027-04-01', true, '主任')");
        // The other languages of a period overlap its primary row.
        KeycloakEnvironment.sql(insert + "('ex-n1', 'en', 'ex-realm-3', 'ex-r1', null, '2027-04-01', false, 'Chief')");

        // Names with no first date hold from the start, so they overlap any earlier names.
        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-n2', 'ja', 'ex-realm-3', 'ex-r1', '2020-04-01', '2021-04-01', true, 'b')")
                .contains("ex_fuhito_ref_item_name_period"));

        KeycloakEnvironment.sql(insert + "('ex-n3', 'ja', 'ex-realm-3', 'ex-r1', '2027-04-01', null, true, 'c')");
    }

    @Test
    void theDatabaseRejectsASecondPrimaryNameOfOnePeriodOfAReferenceItem() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_ref_item (id, realm_id, kind, code, attributes)"
                + " values ('ex-r2', 'ex-realm-6', 'JOB_TITLE', 'a', '{}')");
        String insert = "insert into fuhito_ref_item_name (period_id, language, realm_id, ref_item_id, valid_from,"
                + " valid_to, is_primary, name) values ";
        KeycloakEnvironment.sql(insert + "('ex-n4', 'ja', 'ex-realm-6', 'ex-r2', null, null, true, '部長')");
        KeycloakEnvironment.sql(insert + "('ex-n4', 'en', 'ex-realm-6', 'ex-r2', null, null, false, 'Manager')");

        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-n4', 'zh-Hant', 'ex-realm-6', 'ex-r2', null, null, true, '部長')")
                .contains("uk_fuhito_ref_item_name_primary"));
        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-n4', 'en', 'ex-realm-6', 'ex-r2', null, null, false, 'Director')")
                .contains("pk_fuhito_ref_item_name"));
    }

    @Test
    void theDatabaseRejectsASecondPrimaryNameOfOneKind() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_ref_kind (id, realm_id, code) values ('ex-kind', 'ex-realm-7', 'site')");
        String insert = "insert into fuhito_ref_kind_name (kind_id, language, realm_id, is_primary, name) values ";
        KeycloakEnvironment.sql(insert + "('ex-kind', 'ja', 'ex-realm-7', true, '拠点')");
        KeycloakEnvironment.sql(insert + "('ex-kind', 'en', 'ex-realm-7', false, 'Site')");

        assertTrue(KeycloakEnvironment.sqlError(insert + "('ex-kind', 'zh-Hant', 'ex-realm-7', true, '據點')")
                .contains("uk_fuhito_ref_kind_name_primary"));
        assertTrue(KeycloakEnvironment.sqlError(insert + "('ex-kind', 'en', 'ex-realm-7', false, 'Location')")
                .contains("pk_fuhito_ref_kind_name"));
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
