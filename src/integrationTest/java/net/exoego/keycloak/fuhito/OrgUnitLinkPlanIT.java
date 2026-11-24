package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.OldStatistics.emptyTables;
import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.assertIndexFinds;
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.assertIndexOrHashFinds;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the reads that look up the links of org units by the hierarchy and the child, other than the walks of
// TreeWalkPlanIT, and of the read of the links below a unit.
class OrgUnitLinkPlanIT {

    private static final String UNITS = "any('{ex-new-unit-2999,ex-new-unit-777}')";

    private static final String ON_DATE =
            "%1$s.valid_from <= '2026-04-01' and (%1$s.valid_to is null or %1$s.valid_to > '2026-04-01')";

    private static final String OF_CHILD = """
            from fuhito_org_unit_link l where l.realm_id = 'ex-new-realm' and l.hierarchy_id = 'ex-new-hierarchy'
            and l.child_id = v.org_unit_id and %s""".formatted(ON_DATE.formatted("l"));

    // The parent of each unit, as JpaOrgUnitStore.PLACED and the search of org units give it.
    private static final String WITH_PARENT = "select u.*, v.*, (select l.parent_id " + OF_CHILD + ")"
            + " from fuhito_org_unit_version v join fuhito_org_unit u on u.id = v.org_unit_id";

    // placedOn, searchOn, parentLinks, and parentLinksOf of JpaOrgUnitStore.
    private static final List<String> READS = List.of(
            "explain " + WITH_PARENT + " where v.realm_id = 'ex-new-realm' and v.org_unit_id = " + UNITS + " and "
                    + ON_DATE.formatted("v"),
            "explain " + WITH_PARENT + """
                     join fuhito_org_unit_version_name p on p.version_id = v.id and p.is_primary = true
                    where v.realm_id = 'ex-new-realm' and %s
                    order by v.display_order, p.phonetic_name, p.name, v.org_unit_id fetch first 100 rows only"""
                    .formatted(ON_DATE.formatted("v")),
            """
            explain select * from fuhito_org_unit_link l where l.realm_id = 'ex-new-realm'
            and l.hierarchy_id = 'ex-new-hierarchy' and l.child_id = 'ex-new-unit-777' order by l.valid_from""",
            """
            explain select * from fuhito_org_unit_link l where l.hierarchy_id = 'ex-new-hierarchy'
            and l.child_id = %s order by l.valid_from""".formatted(UNITS));

    private static final String PAGE = WITH_PARENT + """
             join fuhito_org_unit_version_name p on p.version_id = v.id and p.is_primary = true
            where v.realm_id = 'ex-new-realm' and %s and %s
            order by v.display_order, p.phonetic_name, p.name, v.org_unit_id fetch first 100 rows only""";

    private static final String NO_PARENT = """
            not exists (select 1 from fuhito_org_unit_link x where x.realm_id = 'ex-new-realm'
            and x.hierarchy_id = (select h.id from fuhito_hierarchy h where h.id = 'ex-new-hierarchy')
            and x.child_id = v.org_unit_id and %s)""".formatted(ON_DATE.formatted("x"));

    // The lookup of the links of a unit in NO_PARENT, by the alias of the links.
    private static final String PARENT_OF_ROOT = "uk_fuhito_org_unit_link_from on fuhito_org_unit_link x";

    // A lookup of the links by the hierarchy alone reads every link of the hierarchy.
    private static final String BY_HIERARCHY_ALONE =
            "Index Cond: \\(+\\(hierarchy_id\\)::text = '[^']*'::text\\)( AND \\(valid_\\w+ [^)]*\\))?\\)?";

    // The roots of searchOn and countSearchOn of JpaOrgUnitStore, of the default hierarchy and of another one.
    private static final List<String> ROOTS = List.of(
            PAGE.formatted(ON_DATE.formatted("v"), NO_PARENT + """
                     and not exists (select 1 from fuhito_ref_item g where g.id = v.unit_type_id
                    and g.rollup_unit = true)"""),
            """
            select count(v.id) from fuhito_org_unit_version v join fuhito_org_unit u on u.id = v.org_unit_id
            where v.realm_id = 'ex-new-realm' and %s and %s""".formatted(ON_DATE.formatted("v"), NO_PARENT),
            PAGE.formatted(ON_DATE.formatted("v"), NO_PARENT + """
                     and exists (select 1 from fuhito_org_unit_link c where c.realm_id = 'ex-new-realm'
                    and c.hierarchy_id = 'ex-new-hierarchy' and c.parent_id = v.org_unit_id and %s)"""
                    .formatted(ON_DATE.formatted("c"))));

    // childLinks of JpaOrgUnitStore.
    private static final String CHILD_LINKS = """
            explain select * from fuhito_org_unit_link l where l.realm_id = 'ex-new-realm'
            and l.hierarchy_id = (select cast('ex-new-hierarchy' as varchar(36))) and l.parent_id = 'ex-new-unit-4'
            order by l.valid_from, l.child_id""";

    private static final String ANALYZE_LINKS = "analyze fuhito_org_unit, fuhito_org_unit_link";

    // The rows are written once for both statistics, as in OrgUnitVersionPlanIT. Each plan is read before the next
    // analyze, and the checks run at the end, so a failure shows every read with both statistics.
    @Test
    void aLookupOfTheLinksOfAUnitInAHierarchyUsesTheirUniqueKeyWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            List<Executable> checks = new ArrayList<>(checks(statement));
            freshStatistics(statement);
            checks.addAll(checks(statement));
            connection.rollback();
            assertAll(checks);
        }
    }

    private static List<Executable> checks(Statement statement) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String explain : READS) {
            String plan = plan(statement, explain);
            checks.add(() -> {
                assertTrue(plan.contains("uk_fuhito_org_unit_link_from"), plan);
                assertFalse(plan.contains("ex_fuhito_org_unit_link_period"), plan);
            });
        }
        return checks;
    }

    // While the statistics were old, PostgreSQL read every link of the hierarchy for each version to find the roots.
    @Test
    void aSearchForTheRootsLooksUpTheParentOfEachUnitByTheChildWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            List<Executable> checks = new ArrayList<>(rootChecks(statement, false));
            freshStatistics(statement);
            checks.addAll(rootChecks(statement, true));
            connection.rollback();
            assertAll(checks);
        }
    }

    // With fresh statistics, PostgreSQL may also join the links with the versions by the child with a hash.
    private static List<Executable> rootChecks(Statement statement, boolean fresh) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String read : ROOTS) {
            String plan = plan(statement, "explain " + read);
            checks.add(() -> {
                assertFalse(plan.lines().map(String::strip).anyMatch(line -> line.matches(BY_HIERARCHY_ALONE)), plan);
                assertFalse(plan.contains("ex_fuhito_org_unit_link_period"), plan);
                if (fresh) {
                    assertIndexOrHashFinds(plan, PARENT_OF_ROOT, "child_id", "\\(v\\.org_unit_id\\)");
                } else {
                    assertIndexFinds(plan, PARENT_OF_ROOT, "child_id", "\\(v\\.org_unit_id\\)");
                }
            });
        }
        return checks;
    }

    // These rows give each realm a second hierarchy. With only the rows of OldStatistics, the query with the hierarchy
    // as a value also took the index of the parent while the statistics were old.
    @Test
    void aReadOfTheLinksBelowAUnitFindsThemByTheParentWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            emptyTables(statement, "fuhito_org_unit, fuhito_org_unit_link");
            links(statement, "ex-old", 20);
            statement.execute(ANALYZE_LINKS);
            links(statement, "ex-new", 3000);
            String old = plan(statement, CHILD_LINKS);
            statement.execute(ANALYZE_LINKS);
            String fresh = plan(statement, CHILD_LINKS);
            connection.rollback();
            assertAll(Stream.of(old, fresh).<Executable>map(plan -> () -> assertIndexFinds(plan,
                    "idx_fuhito_org_unit_link_parent", "parent_id", "'ex-new-unit-4'", "hierarchy_id")));
        }
    }

    private static void links(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_hierarchy (id, realm_id, code, name) values
                ('%1$s-hierarchy', '%1$s-realm', 'default', 'Default'),
                ('%1$s-hierarchy-2', '%1$s-realm', 'other', 'Other')""", """
                insert into fuhito_org_unit (id, realm_id) select '%1$s-unit-' || i, '%1$s-realm'
                from generate_series(1, %2$d) i""", """
                insert into fuhito_org_unit_link (id, realm_id, hierarchy_id, child_id, parent_id, valid_from)
                select '%1$s-link-' || i, '%1$s-realm', '%1$s-hierarchy', '%1$s-unit-' || i, '%1$s-unit-' || (i / 10),
                '2026-04-01' from generate_series(10, %2$d) i""", """
                insert into fuhito_org_unit_link (id, realm_id, hierarchy_id, child_id, parent_id, valid_from)
                select '%1$s-link-2-' || i, '%1$s-realm', '%1$s-hierarchy-2', '%1$s-unit-' || i,
                '%1$s-unit-' || (i / 5), '2026-04-01' from generate_series(5, %2$d) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }
}
