package net.exoego.keycloak.fuhito;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// The setup and the plans of the checks of reads while the statistics do not know a new realm, and after they do.
final class OldStatistics {

    private static final String ANALYZE = """
            analyze fuhito_org_unit, fuhito_org_unit_version, fuhito_org_unit_version_name, fuhito_org_unit_link,
            fuhito_person, fuhito_employment, fuhito_employment_terms, fuhito_assignment""";

    private OldStatistics() {
    }

    // An import into a new realm writes its rows in one transaction, so the statistics still say that the realm has
    // no rows. The caller rolls them back. The rows that other tests rolled back leave space in the tables and their
    // indexes, which a real import does not find, and that space changes the plans. A plain vacuum keeps the space when
    // rows that other tests committed come after it, so the setup rewrites the tables.
    static void realmsWithOldStatistics(Statement statement) throws Exception {
        KeycloakEnvironment.sql("""
                vacuum full fuhito_org_unit, fuhito_org_unit_version, fuhito_org_unit_version_name,
                fuhito_org_unit_link, fuhito_person, fuhito_employment, fuhito_employment_terms, fuhito_assignment""");
        rowsOfRealm(statement, "ex-old", 20);
        statement.execute(ANALYZE);
        rowsOfRealm(statement, "ex-new", 3000);
    }

    // The statistics after the import, as autovacuum makes them later. They know the rows of this transaction.
    static void freshStatistics(Statement statement) throws Exception {
        statement.execute(ANALYZE);
    }

    // Units below the first nine have a parent, and each unit has one version with a name. Each person has a user, one
    // employment with its terms, and one assignment to one of the first 20 units. The second unit employs every 20th
    // person, and the first unit the rest.
    private static void rowsOfRealm(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_hierarchy (id, realm_id, code, name)
                values ('%1$s-hierarchy', '%1$s-realm', 'default', 'Default')""", """
                insert into fuhito_org_unit (id, realm_id) select '%1$s-unit-' || i, '%1$s-realm'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_org_unit_version (id, realm_id, org_unit_id, valid_from, attributes)
                select '%1$s-version-' || i, '%1$s-realm', '%1$s-unit-' || i, '2026-04-01', '{}'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_org_unit_version_name (version_id, language, realm_id, is_primary, name)
                select '%1$s-version-' || i, 'ja', '%1$s-realm', true, 'Unit ' || i
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

    static String realmOfUnit(String alias, String unitId) {
        String realm = "(select u.realm_id from fuhito_org_unit u where u.id = '%s')".formatted(unitId);
        return "%s.realm_id = %s and %s = 'ex-new-realm'".formatted(alias, realm, realm);
    }

    static void assertPlanHas(Statement statement, String explain, String... parts) throws Exception {
        String plan = plan(statement, explain);
        for (String part : parts) {
            assertTrue(plan.contains(part), plan);
        }
    }

    static String plan(Statement statement, String explain) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rows = statement.executeQuery(explain)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }
}
