package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theDatabaseRejectsOverlappingTermsOfOneEmployment() throws Exception {
        KeycloakEnvironment.sql("insert into fuhito_person (id, realm_id, attributes)"
                + " values ('ex-terms-person', 'ex-realm-terms', '{}')");
        KeycloakEnvironment.sql("insert into fuhito_employment (id, realm_id, valid_from, person_id)"
                + " values ('ex-terms-e1', 'ex-realm-terms', '2026-04-01', 'ex-terms-person'),"
                + " ('ex-terms-e2', 'ex-realm-terms', '2026-04-01', 'ex-terms-person')");
        String insert = "insert into fuhito_employment_terms (id, realm_id, employment_id, valid_from, valid_to,"
                + " status, attributes) values ";
        KeycloakEnvironment.sql(insert + "('ex-t1', 'ex-realm-terms', 'ex-terms-e1', '2026-04-01', '2027-04-01',"
                + " 'ACTIVE', '{}')");

        assertTrue(KeycloakEnvironment.sqlError(insert
                + "('ex-t2', 'ex-realm-terms', 'ex-terms-e1', '2027-03-31', null, 'ACTIVE', '{}')")
                .contains("ex_fuhito_employment_terms_period"));

        KeycloakEnvironment.sql(insert + "('ex-t3', 'ex-realm-terms', 'ex-terms-e1', '2027-04-01', null, 'ACTIVE', '{}')");
        KeycloakEnvironment.sql(insert + "('ex-t4', 'ex-realm-terms', 'ex-terms-e2', '2026-04-01', null, 'ACTIVE', '{}')");
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
            assertTrue(plan(statement, """
                    explain select * from fuhito_org_unit_version where org_unit_id = 'ex-new-unit-7'
                    order by valid_from""")
                    .contains("Index Cond: ((org_unit_id)::text = 'ex-new-unit-7'::text)"));
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

    // The index of the exclusion constraint on the terms serves only the constraint.
    @Test
    void aLookupOfTheTermsOfAnEmploymentUsesTheirUniqueKeyAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            String onDate = "t.valid_from <= '2026-04-01' and (t.valid_to is null or t.valid_to > '2026-04-01')";
            assertPlanHas(statement, """
                    explain select count(a.id) from fuhito_assignment a where a.org_unit_id = 'ex-new-unit-7' and %s
                    and a.valid_from <= '2026-04-01' and (a.valid_to is null or a.valid_to > '2026-04-01')
                    and exists (select 1 from fuhito_employment_terms t where t.realm_id = a.realm_id
                    and t.employment_id = a.employment_id and t.status = 'ACTIVE' and %s)"""
                    .formatted(realmOfUnit("a", "ex-new-unit-7"), onDate),
                    "Index Cond: (((employment_id)::text = (a.employment_id)::text) AND (valid_from <= ");
            String byContractEnd = """
                    from fuhito_employment e where e.realm_id = 'ex-new-realm' and exists (select t.id
                    from fuhito_employment_terms t where t.employment_id = e.id and %s
                    and t.contract_end_date >= '2026-01-01' and t.contract_end_date < '2027-01-01')"""
                    .formatted(onDate);
            String ofEmployment = "Index Cond: (((employment_id)::text = (e.id)::text) AND (valid_from <= ";
            assertPlanHas(statement, "explain select * " + byContractEnd + " order by e.id fetch first 100 rows only",
                    ofEmployment);
            assertPlanHas(statement, "explain select count(e.id) " + byContractEnd, ofEmployment);
            String ofEmployments = "from fuhito_employment_terms t"
                    + " where t.employment_id = any('{ex-new-employment-7,ex-new-employment-8}')";
            assertPlanHas(statement, "explain select * " + ofEmployments + " and " + onDate,
                    "uk_fuhito_employment_terms_from");
            assertPlanHas(statement, "explain select * " + ofEmployments + " order by t.employment_id, t.valid_from",
                    "uk_fuhito_employment_terms_from");
            connection.rollback();
        }
    }

    @Test
    void theWalkUpTheTreeLooksUpEachStepByItsUnitAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            // Without analyze, PostgreSQL does not show the lookup in the recursive part.
            String plan = plan(statement, """
                    explain (analyze, costs off) with recursive above (unit_id, ancestor_id, depth, realm_id) as (
                        select v.org_unit_id, v.org_unit_id, 0, v.realm_id from fuhito_org_unit_version v
                        where v.org_unit_id = any('{ex-new-unit-777}') and v.valid_from <= '2026-04-01'
                        and (v.valid_to is null or v.valid_to > '2026-04-01')
                        union all
                        select a.unit_id, (select l.parent_id from fuhito_org_unit_link l
                            where l.child_id = a.ancestor_id and l.realm_id = 'ex-new-realm'
                            and l.hierarchy_id = 'ex-new-hierarchy' and l.valid_from <= '2026-04-01'
                            and (l.valid_to is null or l.valid_to > '2026-04-01')), a.depth + 1, a.realm_id
                        from above a where a.ancestor_id is not null)
                    select a.unit_id,
                        (select w.work_location_id from fuhito_org_unit_version w where w.org_unit_id = a.ancestor_id
                        and w.valid_from <= '2026-04-01' and (w.valid_to is null or w.valid_to > '2026-04-01')),
                        (select w.cost_center_id from fuhito_org_unit_version w where w.org_unit_id = a.ancestor_id
                        and w.valid_from <= '2026-04-01' and (w.valid_to is null or w.valid_to > '2026-04-01'))
                    from above a where a.realm_id = 'ex-new-realm' and a.ancestor_id is not null
                    order by a.unit_id, a.depth""");
            assertFalse(plan.contains("Seq Scan"), plan);
            assertTrue(plan.contains("(org_unit_id)::text = ANY"), plan);
            // Each lookup of a step finds its rows by the unit in the index.
            List<String> steps = plan.lines().map(String::strip)
                    .filter(line -> line.matches(".*\\(a(_\\d+)?\\.ancestor_id\\)::text.*")).toList();
            steps.forEach(line -> assertTrue(line.startsWith("Index Cond:") || line.startsWith("Recheck Cond:"),
                    plan));
            assertEquals(3, steps.stream().filter(line -> line.startsWith("Index Cond:")).count(), plan);
            connection.rollback();
        }
    }

    // An import into a new realm writes its rows in one transaction, so the statistics still say that the realm has
    // no rows. The caller rolls them back. The rows that other tests rolled back stay in the tables until a vacuum, and
    // change the plans. A real import does not find them.
    private static void realmsWithOldStatistics(Statement statement) throws Exception {
        KeycloakEnvironment.sql("""
                vacuum fuhito_org_unit, fuhito_org_unit_version, fuhito_org_unit_link, fuhito_person,
                fuhito_employment, fuhito_employment_terms, fuhito_assignment""");
        rowsOfRealm(statement, "ex-old", 20);
        statement.execute("""
                analyze fuhito_org_unit, fuhito_org_unit_version, fuhito_org_unit_link, fuhito_person,
                fuhito_employment, fuhito_employment_terms, fuhito_assignment""");
        rowsOfRealm(statement, "ex-new", 3000);
    }

    // Units below the first nine have a parent. Each person has a user, one employment with its terms, and one
    // assignment to one of the first 20 units. The second unit employs every 20th person, and the first unit the rest.
    private static void rowsOfRealm(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_hierarchy (id, realm_id, code, name)
                values ('%1$s-hierarchy', '%1$s-realm', 'default', 'Default')""", """
                insert into fuhito_org_unit (id, realm_id) select '%1$s-unit-' || i, '%1$s-realm'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_org_unit_version (id, realm_id, org_unit_id, valid_from, attributes)
                select '%1$s-version-' || i, '%1$s-realm', '%1$s-unit-' || i, '2026-04-01', '{}'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_org_unit_link (id, realm_id, hierarchy_id, child_id, parent_id, valid_from)
                select '%1$s-link-' || i, '%1$s-realm', '%1$s-hierarchy', '%1$s-unit-' || i, '%1$s-unit-' || (i / 10),
                '2026-04-01' from generate_series(10, %2$d) i""", """
                insert into fuhito_person (id, realm_id, user_id, attributes)
                select '%1$s-person-' || i, '%1$s-realm', '%1$s-user-' || i, '{}'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_employment (id, realm_id, valid_from, staff_number, person_id, employer_id)
                select '%1$s-employment-' || i, '%1$s-realm', '2026-04-01', 'S' || i, '%1$s-person-' || i,
                '%1$s-unit-' || (case when i %% 20 = 0 then 2 else 1 end) from generate_series(1, %2$d) i""", """
                insert into fuhito_employment_terms (id, realm_id, employment_id, valid_from, status, attributes)
                select '%1$s-terms-' || i, '%1$s-realm', '%1$s-employment-' || i, '2026-04-01', 'ACTIVE', '{}'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_assignment (id, realm_id, assignment_id, employment_id, org_unit_id, valid_from,
                is_primary, attributes) select '%1$s-row-' || i, '%1$s-realm', '%1$s-assignment-' || i,
                '%1$s-employment-' || i, '%1$s-unit-' || (i %% 20 + 1), '2026-04-01', true, '{}'
                from generate_series(1, %2$d) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }

    private static String realmOfUnit(String alias, String unitId) {
        String realm = "(select u.realm_id from fuhito_org_unit u where u.id = '%s')".formatted(unitId);
        return "%s.realm_id = %s and %s = 'ex-new-realm'".formatted(alias, realm, realm);
    }

    private static void assertPlanHas(Statement statement, String explain, String... parts) throws Exception {
        String plan = plan(statement, explain);
        for (String part : parts) {
            assertTrue(plan.contains(part), plan);
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
