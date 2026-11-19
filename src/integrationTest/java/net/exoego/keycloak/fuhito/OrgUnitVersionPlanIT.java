package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmOfUnit;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the reads of JpaOrgUnitStore and JpaResourceRemoval that find the versions of org units by their units.
class OrgUnitVersionPlanIT {

    // The index of the exclusion constraint, or an index that starts with the realm.
    private static final String OTHER_INDEX =
            "(?s).*(ex_fuhito_org_unit_version_period|idx_fuhito_org_unit_version_(realm|code)).*";

    private static final String OF_UNITS = "from fuhito_org_unit_version v"
            + " where v.org_unit_id = any('{ex-new-unit-7,ex-new-unit-8}')";

    private static final String OF_UNIT = "v.org_unit_id = 'ex-new-unit-7' and " + realmOfUnit("v", "ex-new-unit-7");

    private static final String ON_DATE =
            "valid_from <= '2026-04-01' and (%1$s.valid_to is null or %1$s.valid_to > '2026-04-01')";

    // The rows are written once for both statistics. Each write that rolls back can change the plans of the plan tests
    // that run later in the same database.
    @Test
    void aReadOfTheVersionsOfSomeUnitsUsesTheirUniqueKeyWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            assertReads(statement);
            freshStatistics(statement);
            assertReads(statement);
            connection.rollback();
        }
    }

    private static void assertReads(Statement statement) throws Exception {
        assertAll(Stream.of(
                "explain select * from fuhito_org_unit_version v where v.org_unit_id = 'ex-new-unit-7'"
                        + " order by v.valid_from",
                "explain select * " + OF_UNITS + " order by v.valid_from",
                "explain select v.realm_id, v.org_unit_id, v.valid_from, v.valid_to " + OF_UNITS
                        + " order by v.valid_from",
                """
                explain delete from fuhito_org_unit_version_name x where x.realm_id = 'ex-new-realm'
                and x.version_id in (select v.id from fuhito_org_unit_version v where %s)""".formatted(OF_UNIT),
                "explain delete from fuhito_org_unit_version v where " + OF_UNIT).<Executable>map(explain -> () -> {
                    String plan = plan(statement, explain);
                    assertTrue(plan.contains("uk_fuhito_org_unit_version_from"), plan);
                    assertFalse(plan.matches(OTHER_INDEX), plan);
                }));

        // The children of a unit name the realm, so while the statistics are old they can take an index of the realm
        // instead.
        String children = plan(statement, """
                explain select * from fuhito_org_unit_version v join fuhito_org_unit u on u.id = v.org_unit_id
                join fuhito_org_unit_version_name p on p.version_id = v.id and p.is_primary = true
                where v.realm_id = 'ex-new-realm' and v.%1$s and exists (select 1 from fuhito_org_unit_link l
                where l.realm_id = 'ex-new-realm' and l.hierarchy_id = 'ex-new-hierarchy'
                and l.child_id = v.org_unit_id and l.parent_id = 'ex-new-unit-4' and l.%2$s)
                order by v.display_order, p.phonetic_name, p.name, v.org_unit_id fetch first 100 rows only"""
                .formatted(ON_DATE.formatted("v"), ON_DATE.formatted("l")));
        assertFalse(children.contains("ex_fuhito_org_unit_version_period"), children);
    }
}
