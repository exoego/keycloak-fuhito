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
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.assertIndexFinds;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the reads of JpaOrgUnitStore and JpaResourceRemoval that find the versions of org units by their units,
// or by their unit types.
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

        // The page and the count of the children of a unit, JpaOrgUnitStore.childrenOn and countChildrenOn.
        String children = """
                from fuhito_org_unit_link l join fuhito_org_unit_version v on v.org_unit_id = l.child_id and v.%s
                join fuhito_org_unit u on u.id = v.org_unit_id%%s
                where l.parent_id = 'ex-new-unit-4'
                and l.hierarchy_id = (select h.id from fuhito_hierarchy h where h.id = 'ex-new-hierarchy')
                and l.%s and %s""".formatted(ON_DATE.formatted("v"), ON_DATE.formatted("l"),
                realmOfUnit("l", "ex-new-unit-4"));
        String page = "explain select u.*, v.* "
                + children.formatted(" join fuhito_org_unit_version_name p on p.version_id = v.id and p.is_primary")
                + " order by v.display_order, p.phonetic_name, p.name, v.org_unit_id fetch first 100 rows only";
        String count = "explain select count(v.id) " + children.formatted("");
        assertAll(Stream.of(page, count).<Executable>map(explain -> () -> {
            String plan = plan(statement, explain);
            assertIndexFinds(plan, "idx_fuhito_org_unit_link_parent", "parent_id", "'ex-new-unit-4'", "hierarchy_id");
            assertIndexFinds(plan, "uk_fuhito_org_unit_version_from", "org_unit_id", "\\((l\\.child_id|u\\.id)\\)");
            assertFalse(plan.matches(OTHER_INDEX), plan);
        }));
    }

    // The search of JpaOrgUnitStore for legal entities, as a list of employments with no employer runs it. It reads
    // the unit types that mark one first.
    @Test
    void aSearchForTheLegalEntitiesFindsTheirVersionsByTheUnitTypeWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            statement.execute("""
                    insert into fuhito_ref_item (id, realm_id, kind, code, attributes, legal_entity) values
                    ('ex-new-company', 'ex-new-realm', 'UNIT_TYPE', 'company', '{}', true),
                    ('ex-new-department', 'ex-new-realm', 'UNIT_TYPE', 'department', '{}', false)""");
            statement.execute("""
                    update fuhito_org_unit_version set unit_type_id = case when org_unit_id = 'ex-new-unit-3'
                    then 'ex-new-department' else 'ex-new-company' end
                    where org_unit_id in ('ex-new-unit-1', 'ex-new-unit-2', 'ex-new-unit-3')""");
            assertLegalEntities(statement, "old");
            freshStatistics(statement);
            assertLegalEntities(statement, "fresh");
            connection.rollback();
        }
    }

    private static void assertLegalEntities(Statement statement, String statistics) throws Exception {
        String search = """
                from fuhito_org_unit_version v join fuhito_org_unit u on u.id = v.org_unit_id%s
                where v.unit_type_id = any('{ex-new-company}') and v.%s""";
        String page = "explain select u.*, v.*, (select l.parent_id from fuhito_org_unit_link l"
                + " where l.realm_id = 'ex-new-realm' and l.hierarchy_id = 'ex-new-hierarchy'"
                + " and l.child_id = v.org_unit_id and l." + ON_DATE.formatted("l") + ") "
                + search.formatted(" join fuhito_org_unit_version_name p on p.version_id = v.id and p.is_primary",
                        ON_DATE.formatted("v"))
                + " order by v.display_order, p.phonetic_name, p.name, v.org_unit_id fetch first 2 rows only";
        String count = "explain select count(v.id) " + search.formatted("", ON_DATE.formatted("v"));
        assertAll(Stream.of(page, count).<Executable>map(explain -> () -> {
            String plan = plan(statement, explain);
            assertTrue(plan.contains("idx_fuhito_org_unit_version_type"), statistics + "\n" + plan);
            assertFalse(plan.matches(OTHER_INDEX), statistics + "\n" + plan);
        }));
    }
}
