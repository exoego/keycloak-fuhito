package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmOfUnit;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

// The plans of the walks of JpaOrgUnitStore and TreeQuery, while the statistics do not know a new realm, and after
// they do.
class TreeWalkPlanIT {

    private static final String LINK_OF_CHILD = "uk_fuhito_org_unit_link_from";

    private static final String VERSION_OF_UNIT = "uk_fuhito_org_unit_version_from";

    private static final String IDS = "any('{ex-new-unit-2999,ex-new-unit-777}')";

    // Each step looks up the parent of one unit, with or without a version on the date.
    @Test
    void eachWalkUpTheTreeLooksUpItsStepsByTheUnitAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            String ancestors = walk(statement, ancestors());
            assertIndexFinds(ancestors, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.ancestor_id\\)");
            assertIndexFinds(ancestors, VERSION_OF_UNIT, "org_unit_id", "ANY");

            String defaults = walk(statement, defaults());
            assertIndexFinds(defaults, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.ancestor_id\\)");
            assertIndexFinds(defaults, VERSION_OF_UNIT, "org_unit_id", "\\(a(_\\d+)?\\.ancestor_id\\)");

            String line = walk(statement, line());
            assertIndexFinds(line, "pk_fuhito_org_unit", "'ex-new-unit-2999'");
            assertIndexFinds(line, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.id\\)");

            String paths = walk(statement, paths());
            assertIndexFinds(paths, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.id\\)");
            assertIndexFinds(paths, VERSION_OF_UNIT, "org_unit_id", "\\(l(_\\d+)?\\.parent_id\\)");
            // The unit of the row and its version join by the same ID.
            assertIndexFinds(paths, VERSION_OF_UNIT, "org_unit_id", "\\((a|u)(_\\d+)?\\.id\\)");

            String places = walk(statement, membersOfOneUnitByPlace());
            assertIndexFinds(places, LINK_OF_CHILD, "child_id", "\\(u(_\\d+)?\\.above_id\\)");
            assertIndexFinds(places, VERSION_OF_UNIT, "org_unit_id", "\\(u(_\\d+)?\\.above_id\\)");
            assertIndexFinds(places, "idx_fuhito_assignment_org_unit", "'ex-new-unit-15'");
            connection.rollback();
        }
    }

    // After an analyze, PostgreSQL took the indexes of the exclusion constraints for these lookups too.
    @Test
    void eachWalkLooksUpTheParentAndTheVersionOfAUnitByTheUnitAlsoWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            freshStatistics(statement);

            String ancestors = walk(statement, ancestors());
            assertIndexFinds(ancestors, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.ancestor_id\\)");
            assertIndexFinds(ancestors, VERSION_OF_UNIT, "org_unit_id", "ANY");

            String defaults = walk(statement, defaults());
            assertIndexFinds(defaults, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.ancestor_id\\)");
            assertIndexFinds(defaults, VERSION_OF_UNIT, "org_unit_id", "\\(a(_\\d+)?\\.ancestor_id\\)");

            assertIndexFinds(walk(statement, line()), LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.id\\)");

            String paths = walk(statement, paths());
            assertIndexFinds(paths, LINK_OF_CHILD, "child_id", "\\(a(_\\d+)?\\.id\\)");
            assertIndexFinds(paths, VERSION_OF_UNIT, "org_unit_id", "\\(l(_\\d+)?\\.parent_id\\)");

            String places = walk(statement, membersOfOneUnitByPlace());
            assertIndexFinds(places, LINK_OF_CHILD, "child_id", "\\(u(_\\d+)?\\.above_id\\)");
            assertIndexFinds(places, VERSION_OF_UNIT, "org_unit_id", "\\(u(_\\d+)?\\.above_id\\)");

            String page = walk(statement, treePage());
            assertIndexFinds(page, LINK_OF_CHILD, "child_id", "\\(v(_\\d+)?\\.org_unit_id\\)");
            assertIndexFinds(page, VERSION_OF_UNIT, "org_unit_id", "'ex-new-unit-1'");
            assertIndexFinds(page, VERSION_OF_UNIT, "org_unit_id", "\\(l(_\\d+)?\\.child_id\\)");
            connection.rollback();
        }
    }

    // Each step finds the children of the units of the step before by the parent, the hierarchy, and the realm of
    // their rows. PostgreSQL takes the index of the parent, or a hash join when the table of links looks small next
    // to the walk. The units of the walk join their members in the same way.
    @Test
    void theWalkDownTheTreeFindsTheChildrenOfEachStepByTheParentAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);

            String[] children = {"parent_id", "\\(p(_\\d+)?\\.id\\)", "\\(p(_\\d+)?\\.hierarchy_id\\)"};
            String page = walk(statement, treePage());
            assertIndexFinds(page, LINK_OF_CHILD, "child_id", "\\(v(_\\d+)?\\.org_unit_id\\)");
            assertIndexOrHashFinds(page, "idx_fuhito_org_unit_link_parent", children);
            assertIndexFinds(page, VERSION_OF_UNIT, "org_unit_id", "'ex-new-unit-1'");
            assertIndexFinds(page, VERSION_OF_UNIT, "org_unit_id", "\\(l(_\\d+)?\\.child_id\\)");

            String members = """
                     select a.* from fuhito_assignment a join tree t on t.id = a.org_unit_id where %s and %s%s
                    order by t.position, a.display_order, a.valid_from, a.assignment_id fetch first 100 rows only""";
            String[] unitsOfTheWalk = {"org_unit_id", "\\(t(_\\d+)?\\.id\\)"};
            String plain = walk(statement, "with recursive " + tree()
                    + members.formatted(realmOfUnit("a", "ex-new-unit-1"), onDate("a"), ""));
            assertIndexOrHashFinds(plain, "idx_fuhito_org_unit_link_parent", children);
            assertIndexOrHashFinds(plain, "idx_fuhito_assignment_org_unit", unitsOfTheWalk);

            String withStatus = walk(statement, "with recursive " + tree()
                    + members.formatted(realmOfUnit("a", "ex-new-unit-1"), onDate("a"), """
                     and exists (select 1 from fuhito_employment_terms e where e.realm_id = a.realm_id
                    and e.employment_id = a.employment_id and e.status = 'ACTIVE' and %s)"""
                            .formatted(onDate("e"))));
            assertIndexFinds(withStatus, "uk_fuhito_employment_terms_from", "employment_id",
                    "\\(a(_\\d+)?\\.employment_id\\)");

            // In another hierarchy, each unit of the walk walks up for its work location.
            String withPlaces = walk(statement, "with recursive " + tree() + ", "
                    + places("from tree t join fuhito_org_unit_version v on v.id = t.version_id")
                    + members.formatted(realmOfUnit("a", "ex-new-unit-1"), onDate("a"), """
                     and (a.work_location_id = 'ex-new-place' or (a.work_location_id is null and a.org_unit_id in
                    (select u.unit_id from places u where u.work_location_id = 'ex-new-place')))"""));
            assertIndexOrHashFinds(withPlaces, "idx_fuhito_org_unit_link_parent", children);
            assertIndexFinds(withPlaces, LINK_OF_CHILD, "child_id", "\\(u(_\\d+)?\\.above_id\\)");
            assertIndexFinds(withPlaces, VERSION_OF_UNIT, "org_unit_id", "\\(u(_\\d+)?\\.above_id\\)");

            // The members of one unit by a secondment. Each employer of the realm walks down, so the walk starts
            // from every employment of the realm.
            String seconded = walk(statement, """
                    with recursive owned (employer_id, unit_id, hierarchy_id, realm_id) as (
                        select m.employer_id, m.employer_id, cast('ex-new-hierarchy' as varchar), m.realm_id
                        from fuhito_employment m where m.realm_id = 'ex-new-realm' and m.employer_id is not null
                        group by m.employer_id, m.realm_id
                        union all
                        select o.employer_id, l.child_id, o.hierarchy_id, o.realm_id from owned o
                        join fuhito_org_unit_link l on l.parent_id = o.unit_id and l.hierarchy_id = o.hierarchy_id
                            and l.realm_id = o.realm_id and %s)
                    select a.* from fuhito_assignment a where a.org_unit_id = 'ex-new-unit-7' and %s and %s
                    and exists (select 1 from fuhito_employment m where m.id = a.employment_id
                    and m.employer_id is not null and not exists (select 1 from owned o
                    where o.employer_id = m.employer_id and o.unit_id = a.org_unit_id))
                    order by a.display_order, a.valid_from, a.assignment_id fetch first 100 rows only"""
                    .formatted(onDate("l"), realmOfUnit("a", "ex-new-unit-7"), onDate("a")), "hierarchy");
            assertIndexOrHashFinds(seconded, "idx_fuhito_org_unit_link_parent", "parent_id",
                    "\\(o(_\\d+)?\\.unit_id\\)", "\\(o(_\\d+)?\\.hierarchy_id\\)");
            connection.rollback();
        }
    }

    private static String walk(Statement statement, String query) throws Exception {
        return walk(statement, query, "realm|hierarchy");
    }

    // An index scan with only one of the columns reads every row of the new realm, or every link of its hierarchy.
    // The indexes of the exclusion constraints on the versions and the links are slower than their unique keys.
    private static String walk(Statement statement, String query, String columns) throws Exception {
        // Without analyze, PostgreSQL does not show the lookups in the recursive part.
        String plan = plan(statement, "explain (analyze, costs off) " + query);
        String onlyOne = "Index Cond: \\(+\\((" + columns + ")_id\\)::text = '[^']*'::text\\)"
                + "( AND \\(valid_\\w+ [^)]*\\))?\\)?";
        assertFalse(plan.lines().map(String::strip).anyMatch(line -> line.matches(onlyOne)), plan);
        assertFalse(plan.contains("ex_fuhito_org_unit_version_period"), plan);
        assertFalse(plan.contains("ex_fuhito_org_unit_link_period"), plan);
        return plan;
    }

    private static void assertIndexFinds(String plan, String index, String... parts) {
        if (!indexFinds(plan, index, parts)) {
            fail(plan);
        }
    }

    private static void assertIndexOrHashFinds(String plan, String index, String... parts) {
        if (!indexFinds(plan, index, parts) && plan.lines().map(String::strip)
                .noneMatch(line -> line.startsWith("Hash Cond:") && hasAll(line, parts))) {
            fail(plan);
        }
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

    // The sub-selects of TreeQuery.parentOf and TreeQuery.livingParentOf.
    private static String parentOf(String unit, boolean living) {
        return """
                (select l.parent_id from fuhito_org_unit_link l where l.child_id = %s and l.realm_id = 'ex-new-realm'
                and l.hierarchy_id = 'ex-new-hierarchy' and %s%s)""".formatted(unit, onDate("l"), living ? """
                 and exists (select 1 from fuhito_org_unit_version living where living.org_unit_id = l.parent_id
                and %s)""".formatted(onDate("living")) : "");
    }

    // JpaOrgUnitStore.ANCESTORS.
    private static String ancestors() {
        return """
                with recursive above (unit_id, ancestor_id, realm_id) as (
                    select v.org_unit_id, v.org_unit_id, v.realm_id from fuhito_org_unit_version v
                    where v.org_unit_id = %s and %s
                    union all
                    select a.unit_id, %s, a.realm_id from above a where a.ancestor_id is not null)
                select a.unit_id, a.ancestor_id from above a
                where a.realm_id = 'ex-new-realm' and a.ancestor_id is not null"""
                .formatted(IDS, onDate("v"), parentOf("a.ancestor_id", false));
    }

    // JpaOrgUnitStore.DEFAULTS, with the work location and the cost center of each step.
    private static String defaults() {
        String value = "(select w.%1$s from fuhito_org_unit_version w where w.org_unit_id = a.ancestor_id and %2$s)";
        return """
                with recursive above (unit_id, ancestor_id, depth, realm_id) as (
                    select v.org_unit_id, v.org_unit_id, 0, v.realm_id from fuhito_org_unit_version v
                    where v.org_unit_id = %s and %s
                    union all
                    select a.unit_id, %s, a.depth + 1, a.realm_id from above a where a.ancestor_id is not null)
                select a.unit_id, %s, %s from above a where a.realm_id = 'ex-new-realm' and a.ancestor_id is not null
                order by a.unit_id, a.depth""".formatted(IDS, onDate("v"), parentOf("a.ancestor_id", false),
                value.formatted("work_location_id", onDate("w")), value.formatted("cost_center_id", onDate("w")));
    }

    // JpaOrgUnitStore.LINE.
    private static String line() {
        return """
                with recursive above (id, depth, realm_id) as (
                    select u.id, 0, u.realm_id from fuhito_org_unit u where u.id = 'ex-new-unit-2999'
                    union all
                    select %s, a.depth + 1, a.realm_id from above a where a.id is not null)
                select a.id from above a where a.realm_id = 'ex-new-realm' and a.id is not null
                order by a.depth""".formatted(parentOf("a.id", false));
    }

    // JpaOrgUnitStore.PATHS.
    private static String paths() {
        return """
                with recursive above (start_id, id, depth, realm_id) as (
                    select v.org_unit_id, v.org_unit_id, 0, v.realm_id from fuhito_org_unit_version v
                    where v.org_unit_id = %s and %s
                    union all
                    select a.start_id, %s, a.depth + 1, a.realm_id from above a where a.id is not null)
                select u.*, v.*, %s, a.start_id from above a
                join fuhito_org_unit_version v on v.org_unit_id = a.id and %s
                join fuhito_org_unit u on u.id = a.id
                where a.realm_id = 'ex-new-realm' and a.id is not null order by a.start_id, a.depth desc"""
                .formatted(IDS, onDate("v"), parentOf("a.id", true), parentOf("v.org_unit_id", false), onDate("v"));
    }

    // The read of the members of one unit by a work location. Only the first 20 units have members.
    private static String membersOfOneUnitByPlace() {
        return "with recursive " + places("""
                from fuhito_org_unit_version v where v.org_unit_id = 'ex-new-unit-15' and %s and %s"""
                .formatted(realmOfUnit("v", "ex-new-unit-15"), onDate("v"))) + """
                select a.* from fuhito_assignment a where a.org_unit_id = 'ex-new-unit-15' and %s and %s
                and (a.work_location_id = 'ex-new-place' or (a.work_location_id is null and a.org_unit_id in
                (select u.unit_id from places u where u.work_location_id = 'ex-new-place')))
                order by a.display_order, a.valid_from, a.assignment_id fetch first 100 rows only"""
                .formatted(realmOfUnit("a", "ex-new-unit-15"), onDate("a"));
    }

    // The first page of the tree from the first unit.
    private static String treePage() {
        return "with recursive " + tree() + """
                select u.*, v.*, t.parent_id from tree t join fuhito_org_unit u on u.id = t.id
                join fuhito_org_unit_version v on v.id = t.version_id
                order by t.position offset 0 rows fetch first 100 rows only""";
    }

    // TreeQuery.TREE from the first unit.
    private static String tree() {
        return """
                tree (id, version_id, parent_id, display_order, phonetic_name, name, hierarchy_id, realm_id) as (
                    select v.org_unit_id, v.id, %s, v.display_order, n.phonetic_name, n.name,
                        cast('ex-new-hierarchy' as varchar), v.realm_id
                    from fuhito_org_unit_version v
                    join fuhito_org_unit_version_name n on n.version_id = v.id and n.is_primary = true
                    where v.org_unit_id = 'ex-new-unit-1' and %s and %s
                    union all
                    select v.org_unit_id, v.id, l.parent_id, v.display_order, n.phonetic_name, n.name, p.hierarchy_id,
                        p.realm_id
                    from tree p
                    join fuhito_org_unit_link l on l.parent_id = p.id and l.hierarchy_id = p.hierarchy_id
                        and l.realm_id = p.realm_id and %s
                    join fuhito_org_unit_version v on v.id = (select x.id from fuhito_org_unit_version x
                            where x.org_unit_id = l.child_id and x.valid_from <= '2026-04-01'
                            order by x.valid_from desc fetch first 1 rows only)
                        and (v.valid_to is null or v.valid_to > '2026-04-01')
                    join fuhito_org_unit_version_name n on n.version_id = v.id and n.is_primary = true
                ) search depth first by display_order, phonetic_name, name, id set position
                """.formatted(parentOf("v.org_unit_id", false), realmOfUnit("v", "ex-new-unit-1"), onDate("v"),
                onDate("l"));
    }

    // TreeQuery.places from the units of the alias v.
    private static String places(String units) {
        String value = "(select w.%1$s from fuhito_org_unit_version w where w.org_unit_id = u.above_id and %2$s)";
        return """
                places (unit_id, above_id, work_location_id, cost_center_id) as (
                    select v.org_unit_id, %s, v.work_location_id, v.cost_center_id
                    %s
                    union all
                    select u.unit_id, %s, coalesce(u.work_location_id, %s), coalesce(u.cost_center_id, %s)
                    from places u
                    where u.above_id is not null and (u.work_location_id is null or u.cost_center_id is null)
                )
                """.formatted(parentOf("v.org_unit_id", true), units, parentOf("u.above_id", true),
                value.formatted("work_location_id", onDate("w")), value.formatted("cost_center_id", onDate("w")));
    }
}
