package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the pages of the lists of an export in JpaRealmExportStore, as Hibernate writes them, while the
// statistics do not know a new realm, and after they do.
class RealmExportPlanIT {

    private static final String MORE_TABLES = """
            fuhito_hierarchy, fuhito_ref_kind, fuhito_ref_item, fuhito_attribute_definition, fuhito_qualification,
            fuhito_reporting_line, fuhito_unit_role, fuhito_external_id, fuhito_change_order, fuhito_erasure""";

    private static final Pattern INDEX = Pattern.compile("(?:using|Bitmap Index Scan on) (\\w+)");

    // The table of each list, and the start of the IDs of its rows.
    private static final List<String[]> LISTS = List.of(new String[] {"ref_kind", "ref-kind"},
            new String[] {"ref_item", "item"}, new String[] {"attribute_definition", "definition"},
            new String[] {"hierarchy", "hierarchy"}, new String[] {"org_unit", "unit"},
            new String[] {"person", "person"}, new String[] {"employment", "employment"},
            new String[] {"assignment_version", "assignment"}, new String[] {"reporting_line", "line"},
            new String[] {"unit_role", "unit-role"}, new String[] {"qualification", "qualification"},
            new String[] {"external_id", "xid"}, new String[] {"change_order", "order"});

    // The rows are written once for both statistics, as in AssignmentVersionsPlanIT. Each plan is read before the next
    // analyze, and the checks run at the end, so a failure shows every page with both statistics.
    @Test
    void eachPageReadsItsRowsInTheOrderOfAnIndexWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement, MORE_TABLES);
            moreRows(statement, "ex-old", 20);
            // OldStatistics wrote the default hierarchy of the new realm already. It is in the old realm during the
            // analyze, so that the statistics know no row of the new realm. With a second realm of one row, they
            // would give some rows to every realm that they do not know.
            String hierarchy = "update fuhito_hierarchy set realm_id = '%s', code = '%s' where id = 'ex-new-hierarchy'";
            statement.execute(hierarchy.formatted("ex-old-realm", "moved"));
            statement.execute("analyze %s".formatted(MORE_TABLES));
            statement.execute(hierarchy.formatted("ex-new-realm", "default"));
            moreRows(statement, "ex-new", 3000);
            List<Executable> checks = new ArrayList<>(checks(statement, false));
            freshStatistics(statement);
            statement.execute("analyze %s".formatted(MORE_TABLES));
            checks.addAll(checks(statement, true));
            connection.rollback();
            assertAll(checks);
        }
    }

    // A page from the start, and a page after a row near the end of the list.
    private static List<Executable> checks(Statement statement, boolean fresh) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String[] list : LISTS) {
            String table = list[0];
            // The page of assignments reads only their IDs.
            String columns = table.equals("assignment_version") ? "x.id" : "x.*";
            for (String after : List.of("", "ex-new-" + list[1] + "-5")) {
                String plan = plan(statement, """
                        explain select %s from fuhito_%s x where (x.realm_id || '') = 'ex-new-realm'
                        and x.id > '%s' order by x.id fetch first 100 rows only""".formatted(columns, table, after));
                // With fresh statistics, the primary key also gives the rows of the page in their order.
                Set<Set<String>> wanted = fresh
                        ? Set.of(Set.of("idx_fuhito_%s_export".formatted(table)), Set.of("pk_fuhito_" + table))
                        : Set.of(Set.of("idx_fuhito_%s_export".formatted(table)));
                checks.add(() -> {
                    assertTrue(wanted.contains(indexes(plan)), plan);
                    assertFalse(plan.contains("Sort"), plan);
                });
            }
        }
        for (String after : List.of("", "ex-new-gone-5")) {
            String plan = plan(statement, """
                    explain select e.* from fuhito_erasure e where e.realm_id = 'ex-new-realm'
                    and e.person_id > '%s' order by e.person_id fetch first 100 rows only""".formatted(after));
            checks.add(() -> {
                assertTrue(indexes(plan).equals(Set.of("uk_fuhito_erasure_person")), plan);
                assertFalse(plan.contains("Sort"), plan);
            });
        }
        return checks;
    }

    // A list of each of the more tables, with the rows of OldStatistics.
    private static void moreRows(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_hierarchy (id, realm_id, code, name)
                select '%1$s-hierarchy-' || i, '%1$s-realm', 'h' || i, 'H' from generate_series(2, %2$d) i""", """
                insert into fuhito_ref_kind (id, realm_id, code)
                select '%1$s-ref-kind-' || i, '%1$s-realm', 'K' || i from generate_series(1, %2$d) i""", """
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes)
                select '%1$s-item-' || i, '%1$s-realm', 'JOB_TITLE', 'J' || i, '{}'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes)
                values ('%1$s-kind', '%1$s-realm', 'QUALIFICATION', 'q', '{}'),
                ('%1$s-role', '%1$s-realm', 'UNIT_ROLE', 'r', '{}')""", """
                insert into fuhito_attribute_definition (id, realm_id, target, name, value_type)
                select '%1$s-definition-' || i, '%1$s-realm', 'PERSON', 'a' || i, 'STRING'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_qualification (id, realm_id, kind_id, valid_from, attributes, person_id)
                select '%1$s-qualification-' || i, '%1$s-realm', '%1$s-kind', '2026-04-01', '{}', '%1$s-person-' || i
                from generate_series(1, %2$d) i""", """
                insert into fuhito_reporting_line (id, realm_id, assignment_id, kind, valid_from, manager_person_id)
                select '%1$s-line-' || i, '%1$s-realm', '%1$s-assignment-' || i, 'DOTTED', '2026-04-01',
                '%1$s-person-' || (i / 10 + 1) from generate_series(1, %2$d) i""", """
                insert into fuhito_unit_role (id, realm_id, employment_id, org_unit_id, role_id, includes_descendants,
                valid_from) select '%1$s-unit-role-' || i, '%1$s-realm', '%1$s-employment-' || i, '%1$s-unit-' || i,
                '%1$s-role', false, '2026-04-01' from generate_series(1, %2$d) i""", """
                insert into fuhito_external_id (id, realm_id, resource_kind, resource_id, source, external_id)
                select '%1$s-xid-' || i, '%1$s-realm', 'ASSIGNMENT', '%1$s-assignment-' || i, 'sheet', 'A' || i
                from generate_series(1, %2$d) i""", """
                insert into fuhito_change_order (id, realm_id, title, effective_date, created_at)
                select '%1$s-order-' || i, '%1$s-realm', 'Order', '2026-04-01', now()
                from generate_series(1, %2$d) i""", """
                insert into fuhito_erasure (id, realm_id, person_id, erased_at)
                select '%1$s-erasure-' || i, '%1$s-realm', '%1$s-gone-' || i, now()
                from generate_series(1, %2$d) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }

    private static Set<String> indexes(String plan) {
        Set<String> found = new TreeSet<>();
        Matcher index = INDEX.matcher(plan);
        while (index.find()) {
            found.add(index.group(1));
        }
        return found;
    }
}
