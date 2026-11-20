package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the reads that look up the links of org units by the hierarchy and the child, other than the walks of
// TreeWalkPlanIT.
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
}
