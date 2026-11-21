package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the walk down for the reports of a manager in JpaReportingLineStore, and of the walk up for the holders
// of unit roles in JpaUnitRoleStore, while the statistics do not know a new realm, and after they do.
class ReportsAndHoldersPlanIT {

    private static final String MANAGER = "'ex-new-person-20'";

    // The rows of an assignment and the roles over a unit can take the GiST index of their periods. #590 is about it.
    private static final String BY_ASSIGNMENT = "(uk_fuhito_assignment_from|ex_fuhito_assignment_period)";
    private static final String ROLES_OF_UNIT = "(idx_fuhito_unit_role_org_unit|ex_fuhito_unit_role_period)";

    private static final Set<String> REPORTS_INDEXES = Set.of("pk_fuhito_person", "idx_fuhito_employment_person",
            "pk_fuhito_employment", "idx_fuhito_assignment_employment", "idx_fuhito_assignment_org_unit",
            "uk_fuhito_assignment_from", "ex_fuhito_assignment_period", "uk_fuhito_employment_terms_from",
            "uk_fuhito_org_unit_version_from", "idx_fuhito_org_unit_link_parent", "pk_fuhito_ref_item",
            "idx_fuhito_reporting_line_manager_person", "idx_fuhito_reporting_line_assignment");

    private static final Set<String> HOLDERS_INDEXES = Set.of("pk_fuhito_org_unit", "uk_fuhito_org_unit_link_from",
            "idx_fuhito_unit_role_org_unit", "ex_fuhito_unit_role_period", "pk_fuhito_employment", "pk_fuhito_person",
            "pk_fuhito_ref_item");

    private static final Pattern INDEX = Pattern.compile("(?:using|Bitmap Index Scan on) (\\w+)");

    private static final String MORE_TABLES = "fuhito_ref_item, fuhito_reporting_line, fuhito_unit_role";

    // An index scan with only the realm reads every row of the new realm, and one with only the hierarchy every link.
    private static final String ONLY_ONE = "Index Cond: \\(+\\((realm|hierarchy)_id\\)::text = '[^']*'::text\\)"
            + "( AND \\(valid_\\w+ [^)]*\\))?\\)?";

    // The rows are written once for both statistics, as in OrgUnitVersionPlanIT. Each plan is read before the next
    // analyze, and the checks run at the end, so a failure shows every walk with both statistics.
    @Test
    void eachStepOfTheWalksTakesTheIndexOfItsKeyWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            rows(statement);
            List<Executable> checks = new ArrayList<>(checks(statement));
            freshStatistics(statement);
            statement.execute("analyze %s".formatted(MORE_TABLES));
            checks.addAll(checks(statement));
            connection.rollback();
            assertAll(checks);
        }
    }

    private static List<Executable> checks(Statement statement) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String reports : List.of("""
                select * from reports r order by r.derived, r.depth, r.org_unit_id, r.assignment_id, r.line_id
                offset 0 rows fetch first 100 rows only""", "select count(*) from reports r")) {
            String plan = walk(statement, reports(reports));
            checks.add(() -> assertSteps(plan, REPORTS_INDEXES));
            // The head rows of the manager, and the stored lines of the manager.
            checks.add(() -> assertIndexFinds(plan, "idx_fuhito_employment_person", "person_id", MANAGER));
            checks.add(() -> assertIndexFinds(plan, "idx_fuhito_assignment_employment", "employment_id",
                    "\\(e(_\\d+)?\\.id\\)"));
            checks.add(() -> assertIndexFinds(plan, "idx_fuhito_reporting_line_manager_person", "manager_person_id",
                    MANAGER));
            checks.add(() -> assertIndexFinds(plan, BY_ASSIGNMENT, "assignment_id",
                    "\\(l(_\\d+)?\\.assignment_id\\)"));
            // Each step of the walk down, and the members of each unit.
            checks.add(() -> assertIndexOrHashFinds(plan, "idx_fuhito_org_unit_link_parent", "parent_id",
                    "\\(d(_\\d+)?\\.id\\)", "\\(d(_\\d+)?\\.hierarchy_id\\)"));
            checks.add(() -> assertIndexFinds(plan, "uk_fuhito_org_unit_version_from", "org_unit_id",
                    "\\(l(_\\d+)?\\.child_id\\)"));
            checks.add(() -> assertIndexFinds(plan, "idx_fuhito_assignment_org_unit", "org_unit_id",
                    "\\(l(_\\d+)?\\.child_id\\)"));
            checks.add(() -> assertIndexOrHashFinds(plan, "idx_fuhito_assignment_org_unit", "org_unit_id",
                    "\\(d(_\\d+)?\\.id\\)"));
            checks.add(() -> assertIndexFinds(plan, "idx_fuhito_reporting_line_assignment", "assignment_id",
                    "\\(a(_\\d+)?\\.assignment_id\\)"));
        }
        // The check of a nearer holder of the same role reads the roles of the role. #608 is about it.
        for (String nearest : List.of("", NEAREST)) {
            for (String holders : List.of("""
                    select r.*, e.person_id, p.user_id %s order by a.depth, k.code, r.id
                    offset 0 rows fetch first 100 rows only""", "select count(r.id) %s")) {
                String plan = walk(statement, holders(holders, nearest));
                checks.add(() -> assertSteps(plan, HOLDERS_INDEXES));
                checks.add(() -> assertIndexFinds(plan, "pk_fuhito_org_unit", "'ex-new-unit-2999'"));
                checks.add(() -> assertIndexFinds(plan, "uk_fuhito_org_unit_link_from", "child_id",
                        "\\(a(_\\d+)?\\.id\\)"));
                checks.add(() -> assertIndexOrHashFinds(plan, ROLES_OF_UNIT, "org_unit_id", "\\(a(_\\d+)?\\.id\\)"));
            }
        }
        return checks;
    }

    // The rows of OldStatistics, three unit roles, a reporting line for each assignment, and a role over each unit.
    // The manager heads the first unit, which has ten child units. One of them has its own head.
    private static void rows(Statement statement) throws Exception {
        realmsWithOldStatistics(statement, MORE_TABLES);
        moreRows(statement, "ex-old", 20);
        statement.execute("analyze %s".formatted(MORE_TABLES));
        moreRows(statement, "ex-new", 3000);
        statement.execute("""
                update fuhito_assignment set is_head = true
                where id in ('ex-new-row-20', 'ex-new-row-10', 'ex-new-row-6')""");
    }

    private static void moreRows(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes)
                select '%1$s-role-' || i, '%1$s-realm', 'UNIT_ROLE', 'R' || i, '{}' from generate_series(1, 3) i""", """
                insert into fuhito_reporting_line (id, realm_id, assignment_id, kind, valid_from, manager_person_id)
                select '%1$s-line-' || i, '%1$s-realm', '%1$s-assignment-' || i, 'DOTTED', '2026-04-01',
                '%1$s-person-' || (i / 10 + 1) from generate_series(1, %2$d) i""", """
                insert into fuhito_unit_role (id, realm_id, employment_id, org_unit_id, role_id, includes_descendants,
                valid_from) select '%1$s-unit-role-' || i, '%1$s-realm', '%1$s-employment-' || i, '%1$s-unit-' || i,
                '%1$s-role-' || (i %% 3 + 1), i %% 2 = 0, '2026-04-01' from generate_series(1, %2$d) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }

    private static String walk(Statement statement, String query) throws Exception {
        return plan(statement, "explain (analyze, costs off, timing off) " + query);
    }

    // No index scan finds its rows by only the realm or the hierarchy, and each index is one that the walk names.
    private static void assertSteps(String plan, Set<String> indexes) {
        assertFalse(plan.lines().map(String::strip).anyMatch(line -> line.matches(ONLY_ONE)), plan);
        Set<String> found = new TreeSet<>();
        Matcher index = INDEX.matcher(plan);
        while (index.find()) {
            found.add(index.group(1));
        }
        found.removeAll(indexes);
        assertTrue(found.isEmpty(), found + "\n" + plan);
    }

    private static void assertIndexFinds(String plan, String index, String... parts) {
        assertTrue(indexFinds(plan, index, parts), () -> index + " " + List.of(parts) + "\n" + plan);
    }

    private static void assertIndexOrHashFinds(String plan, String index, String... parts) {
        assertTrue(indexFinds(plan, index, parts) || plan.lines().map(String::strip)
                .anyMatch(line -> line.startsWith("Hash Cond:") && hasAll(line, parts)),
                () -> index + " or a hash " + List.of(parts) + "\n" + plan);
    }

    // Whether a scan of the index has all the parts in its condition.
    private static boolean indexFinds(String plan, String index, String... parts) {
        List<String> lines = plan.lines().map(String::strip).toList();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).matches(".* (using|on) " + index + " .*")) {
                continue;
            }
            for (int j = i + 1; j < lines.size() && !lines.get(j).startsWith("->"); j++) {
                if (lines.get(j).startsWith("Index Cond:") && hasAll(lines.get(j), parts)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAll(String line, String... parts) {
        return Stream.of(parts).allMatch(part -> line.matches(".*" + part + ".*"));
    }

    private static String onDate(String alias) {
        return "%1$s.valid_from <= '2026-04-01' and (%1$s.valid_to is null or %1$s.valid_to > '2026-04-01')"
                .formatted(alias);
    }

    // JpaReportingLineStore.MANAGER_IN_REALM.
    private static final String MANAGER_IN_REALM =
            "(select m.realm_id from fuhito_person m where m.id = %s) = 'ex-new-realm'".formatted(MANAGER);

    // JpaReportingLineStore.leads for the row of the alias.
    private static String leads(String row) {
        String present = "exists (select 1 from fuhito_employment_terms %2$s"
                + " where %2$s.employment_id = %1$s.employment_id and %2$s.status = 'ACTIVE' and %3$s)";
        String acting = "exists (select 1 from fuhito_ref_item %2$s where %2$s.id = %1$s.kind_id"
                + " and %2$s.acting_head = true)";
        String r = row + "r";
        return """
                (%1$s.is_head = true and %2$s and (%3$s or not exists (select 1 from fuhito_assignment %4$s
                where %4$s.org_unit_id = %1$s.org_unit_id and %4$s.is_head = true and %5$s and %6$s and %7$s)))"""
                .formatted(row, present.formatted(row, row + "t", onDate(row + "t")),
                        acting.formatted(row, row + "k"), r, onDate(r),
                        present.formatted(r, r + "t", onDate(r + "t")), acting.formatted(r, r + "k"));
    }

    // JpaReportingLineStore.reports for the manager, with the rows of both parts in the table reports.
    private static String reports(String select) {
        return """
                with recursive down (id, depth, open, hierarchy_id, realm_id) as (
                    select a.org_unit_id, 0, 1, cast('ex-new-hierarchy' as varchar), e.realm_id
                    from fuhito_employment e join fuhito_assignment a on a.employment_id = e.id
                    where e.person_id = %1$s and %2$s and %3$s and %4$s
                    and not exists (select 1 from fuhito_org_unit_version gv, fuhito_ref_item g
                        where gv.org_unit_id = a.org_unit_id and %5$s and g.id = gv.unit_type_id
                        and g.lateral_unit = true)
                    union all
                    select l.child_id, d.depth + 1, case when exists (select 1 from fuhito_assignment h
                            join fuhito_employment he on he.id = h.employment_id
                            where h.org_unit_id = l.child_id and he.person_id <> %1$s and %6$s and %7$s)
                        then 0 else 1 end, d.hierarchy_id, d.realm_id
                    from down d
                    join fuhito_org_unit_link l on l.parent_id = d.id and l.hierarchy_id = d.hierarchy_id
                        and l.realm_id = d.realm_id and %8$s
                    join fuhito_org_unit_version v on v.org_unit_id = l.child_id and %9$s
                    where d.open = 1 and not exists (select 1 from fuhito_ref_item g where g.id = v.unit_type_id
                        and g.lateral_unit = true)
                ),
                units (id, depth, open) as (select d.id, min(d.depth), max(d.open) from down d group by d.id),
                reports (derived, depth, org_unit_id, assignment_id, line_id, kind, person_id, user_id) as (
                    select 0, 0, a.org_unit_id, a.assignment_id, l.id, l.kind, e.person_id, p.user_id
                    from fuhito_reporting_line l
                    join fuhito_assignment a on a.assignment_id = l.assignment_id and %3$s
                    join fuhito_employment e on e.id = a.employment_id
                    join fuhito_person p on p.id = e.person_id
                    where l.manager_person_id = %1$s and %2$s and %8$s
                    union all
                    select 1, d.depth, a.org_unit_id, a.assignment_id, cast(null as varchar), 'SOLID', e.person_id,
                        p.user_id
                    from units d
                    join fuhito_assignment a on a.org_unit_id = d.id and %3$s
                    join fuhito_employment e on e.id = a.employment_id
                    join fuhito_person p on p.id = e.person_id
                    where e.person_id <> %1$s and ((d.depth = 0 and not a.is_head) or (d.depth > 0 and d.open = 1)
                        or (d.depth > 0 and d.open = 0 and a.is_head))
                    and not exists (select 1 from fuhito_reporting_line s where s.assignment_id = a.assignment_id
                        and s.kind = 'SOLID' and %10$s)
                )
                %11$s""".formatted(MANAGER, MANAGER_IN_REALM, onDate("a"), leads("a"), onDate("gv"), onDate("h"),
                leads("h"), onDate("l"), onDate("v"), onDate("s"), select);
    }

    // JpaUnitRoleStore.NEAREST.
    private static final String NEAREST = """
             and not exists (select 1 from above b join fuhito_unit_role o on o.org_unit_id = b.id
            and o.realm_id = 'ex-new-realm' and %s where o.role_id = r.role_id and b.depth < a.depth
            and (b.depth = 0 or o.includes_descendants = true))""".formatted(onDate("o"));

    // JpaUnitRoleStore.HOLDERS from a unit four levels down.
    private static String holders(String select, String nearest) {
        String from = """
                from above a
                join fuhito_unit_role r on r.org_unit_id = a.id and r.realm_id = 'ex-new-realm' and %s
                join fuhito_employment e on e.id = r.employment_id
                join fuhito_person p on p.id = e.person_id
                join fuhito_ref_item k on k.id = r.role_id
                where a.realm_id = 'ex-new-realm' and (a.depth = 0 or r.includes_descendants = true)%s"""
                .formatted(onDate("r"), nearest);
        return """
                with recursive above (id, depth, realm_id) as (
                    select u.id, 0, u.realm_id from fuhito_org_unit u where u.id = 'ex-new-unit-2999'
                    union all
                    select (select l.parent_id from fuhito_org_unit_link l where l.child_id = a.id
                        and l.realm_id = 'ex-new-realm' and l.hierarchy_id = 'ex-new-hierarchy' and %s),
                        a.depth + 1, a.realm_id
                    from above a where a.id is not null)
                %s""".formatted(onDate("l"), select.formatted(from));
    }
}
