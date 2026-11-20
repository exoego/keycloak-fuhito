package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.OldStatistics.assertPlanHas;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmOfUnit;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // The check of a staff number names the realm, because two realms can use the same staff number.
    @Test
    void aCheckOfAStaffNumberUsesItsIndexAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            assertTrue(plan(statement, """
                    explain select * from fuhito_employment where realm_id = 'ex-new-realm'
                    and staff_number = 'S7' order by valid_from""")
                    .contains("idx_fuhito_employment_staff_number"));
            connection.rollback();
        }
    }

    // These reads leave the realm out of the query, and drop the rows of other realms after it.
    @Test
    void aReadByTheIdOfARecordUsesItsIndexAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            assertTrue(plan(statement, """
                    explain select * from fuhito_employment where id = any('{ex-new-employment-7}')""")
                    .contains("Index Cond: ((id)::text = ANY"));
            assertTrue(plan(statement, """
                    explain select * from fuhito_employment_terms where employment_id = 'ex-new-employment-7'
                    order by valid_from""")
                    .contains("Index Cond: ((employment_id)::text = 'ex-new-employment-7'::text)"));
            assertTrue(plan(statement, """
                    explain select * from fuhito_assignment where assignment_id = any('{ex-new-assignment-7}')
                    order by assignment_id, valid_from""")
                    .contains("Index Cond: ((assignment_id)::text = ANY"));
            assertTrue(plan(statement, """
                    explain select * from fuhito_assignment where employment_id = any('{ex-new-employment-7}')
                    order by employment_id, valid_from, id""")
                    .contains("Index Cond: ((employment_id)::text = ANY"));
            connection.rollback();
        }
    }

    // These reads leave the realm out of the query too. Each finds its rows by a person, an employer, or org units.
    @Test
    void aReadByTheIdOfAnotherRecordUsesItsIndexAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            assertPlanHas(statement, """
                    explain select * from fuhito_employment where person_id = 'ex-new-person-7'
                    order by valid_from, id""", "Index Cond: ((person_id)::text = 'ex-new-person-7'::text)");
            assertPlanHas(statement, """
                    explain select * from fuhito_employment e join fuhito_person p on p.id = e.person_id
                    where e.employer_id = 'ex-new-unit-2' order by e.valid_from, e.id""",
                    "Index Cond: ((employer_id)::text = 'ex-new-unit-2'::text)");
            assertPlanHas(statement, """
                    explain select * from fuhito_assignment where org_unit_id = any('{ex-new-unit-7}')
                    and valid_from <= '2026-04-01' and (valid_to is null or valid_to > '2026-04-01')""",
                    "Index Cond: (((org_unit_id)::text = ANY");
            assertPlanHas(statement, """
                    explain select realm_id, count(distinct assignment_id) from fuhito_assignment
                    where org_unit_id = 'ex-new-unit-7' and (valid_to is null or valid_to > '2026-04-01')
                    group by realm_id""", "Index Cond: ((org_unit_id)::text = 'ex-new-unit-7'::text)");
            assertPlanHas(statement, """
                    explain select a.realm_id, a.org_unit_id, p.user_id from fuhito_assignment a
                    join fuhito_employment e on e.id = a.employment_id join fuhito_person p on p.id = e.person_id
                    where a.org_unit_id = any('{ex-new-unit-7,ex-new-unit-8}') and a.valid_from <= '2026-04-01'
                    and (a.valid_to is null or a.valid_to > '2026-04-01') and p.user_id is not null""",
                    "Index Cond: (((org_unit_id)::text = ANY", "Index Cond: ((id)::text = (a.employment_id)::text)",
                    "Index Cond: ((id)::text = (e.person_id)::text)");
            connection.rollback();
        }
    }

    // The reads of the employments of users check the realm on the person, whose key has the user too.
    @Test
    void aReadOfTheEmploymentsOfAUserStartsFromThePersonAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            assertPlanHas(statement, """
                    explain select e.* from fuhito_employment e join fuhito_person p on p.id = e.person_id
                    where p.realm_id = 'ex-new-realm' and p.user_id = 'ex-new-user-7' order by e.valid_from, e.id""",
                    "((realm_id)::text = 'ex-new-realm'::text) AND ((user_id)::text = 'ex-new-user-7'::text)",
                    "Index Cond: ((person_id)::text = (p.id)::text)");
            assertPlanHas(statement, """
                    explain select * from fuhito_employment e join fuhito_person p on p.id = e.person_id
                    where p.realm_id = 'ex-new-realm' and p.user_id = any('{ex-new-user-7,ex-new-user-8}')
                    order by e.valid_from, e.id""",
                    "((realm_id)::text = 'ex-new-realm'::text) AND ((user_id)::text = ANY",
                    "Index Cond: ((person_id)::text = (p.id)::text)");
            connection.rollback();
        }
    }

    // These reads page in the database, so they keep the realm in the query. A sub-select finds the realm of the org
    // unit by its key.
    @Test
    void aPagedReadByAnOrgUnitUsesItsIndexAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            String employer = "Index Cond: ((employer_id)::text = 'ex-new-unit-2'::text)";
            String unit = "Index Cond: (((org_unit_id)::text = 'ex-new-unit-7'::text) AND (valid_from <= ";
            assertPlanHas(statement, """
                    explain select * from fuhito_employment e where e.employer_id = 'ex-new-unit-2'
                    and %s order by e.id fetch first 100 rows only""".formatted(realmOfUnit("e", "ex-new-unit-2")),
                    employer, "Index Cond: ((id)::text = 'ex-new-unit-2'::text)");
            assertPlanHas(statement, """
                    explain select count(e.id) from fuhito_employment e where e.employer_id = 'ex-new-unit-2'
                    and %s and e.valid_from <= '2026-04-01' and (e.valid_to is null or e.valid_to > '2026-04-01')"""
                    .formatted(realmOfUnit("e", "ex-new-unit-2")), employer);
            String members = """
                    from fuhito_assignment a where a.org_unit_id = 'ex-new-unit-7' and %s
                    and a.valid_from <= '2026-04-01' and (a.valid_to is null or a.valid_to > '2026-04-01')"""
                    .formatted(realmOfUnit("a", "ex-new-unit-7"));
            assertPlanHas(statement, "explain select * " + members
                    + " order by a.display_order, a.valid_from, a.assignment_id fetch first 100 rows only", unit);
            assertPlanHas(statement, "explain select * " + members
                    + " order by a.assignment_id fetch first 100 rows only", unit);
            assertPlanHas(statement, "explain select count(a.id) " + members, unit);
            assertPlanHas(statement, "explain select * " + members + """
                     and exists (select 1 from fuhito_employment_terms t where t.realm_id = a.realm_id
                    and t.employment_id = a.employment_id and t.status = 'ACTIVE' and t.valid_from <= '2026-04-01'
                    and (t.valid_to is null or t.valid_to > '2026-04-01'))
                    order by a.display_order, a.valid_from, a.assignment_id fetch first 100 rows only""",
                    unit, "Index Cond: (((employment_id)::text = (a.employment_id)::text) AND (valid_from <= ");
            connection.rollback();
        }
    }

    @Test
    void aLookupOfTheTermsOfAnEmploymentUsesTheirUniqueKeyAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            String terms = "from fuhito_employment_terms t where t.valid_from <= '2026-04-01'"
                    + " and (t.valid_to is null or t.valid_to > '2026-04-01') and ";
            String byContractEnd = "from fuhito_employment e where e.realm_id = 'ex-new-realm' and exists (select t.id "
                    + terms + "t.employment_id = e.id and t.contract_end_date >= '2026-01-01')";
            String ofEmployments = "t.employment_id = any('{ex-new-employment-7,ex-new-employment-8}')";
            for (String explain : List.of("""
                    explain select count(a.id) from fuhito_assignment a where a.org_unit_id = 'ex-new-unit-7' and %s
                    and a.valid_from <= '2026-04-01' and (a.valid_to is null or a.valid_to > '2026-04-01')
                    and exists (select 1 %s t.realm_id = a.realm_id and t.employment_id = a.employment_id
                    and t.status = 'ACTIVE')""".formatted(realmOfUnit("a", "ex-new-unit-7"), terms),
                    "explain select * " + byContractEnd + " order by e.id fetch first 100 rows only",
                    "explain select count(e.id) " + byContractEnd, "explain select * " + terms + ofEmployments,
                    "explain select * from fuhito_employment_terms t where " + ofEmployments
                            + " order by t.employment_id, t.valid_from")) {
                assertPlanHas(statement, explain, "uk_fuhito_employment_terms_from");
            }
            assertTrue(assertThrows(java.sql.SQLException.class, () -> statement.execute("""
                    insert into fuhito_employment_terms (id, realm_id, employment_id, valid_from, status, attributes)
                    values ('ex-new-terms-0', 'ex-new-realm', 'ex-new-employment-7', '2026-05-01', 'ACTIVE', '{}')"""))
                    .getMessage().contains("ex_fuhito_employment_terms_period"));
            connection.rollback();
        }
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
