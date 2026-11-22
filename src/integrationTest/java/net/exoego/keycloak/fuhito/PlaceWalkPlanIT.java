package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.realmOfUnit;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.VERSION_OF_UNIT;
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.assertIndexFinds;
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.assertIndexOrHashFinds;
import static net.exoego.keycloak.fuhito.TreeWalkPlanIT.walk;

// The plans of JpaPlaceStore. It walks down from a unit over a period, and then reads the members of the units that it
// found. The checks are the ones of TreeWalkPlanIT.
class PlaceWalkPlanIT {

    @Test
    void theWalkDownForWhatAppliesBelowAUnitFindsEachStepByTheParentAlsoWhileTheStatisticsAreOld() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement);
            for (boolean fresh : List.of(false, true)) {
                if (fresh) {
                    freshStatistics(statement);
                }
                String spans = walk(statement, """
                        with recursive spans (unit_id, span_from, span_to, work_location_id, cost_center_id,
                            hierarchy_id, realm_id) as (
                            select v.org_unit_id, greatest(v.valid_from, date '2026-04-01'), v.valid_to,
                                v.work_location_id, v.cost_center_id, cast('ex-new-hierarchy' as varchar), v.realm_id
                            from fuhito_org_unit_version v
                            where v.org_unit_id = 'ex-new-unit-1' and %s
                                and (v.valid_to is null or v.valid_to > '2026-04-01')
                            union all
                            select l.child_id, greatest(s.span_from, l.valid_from, v.valid_from),
                                least(s.span_to, l.valid_to, v.valid_to),
                                coalesce(v.work_location_id, s.work_location_id),
                                coalesce(v.cost_center_id, s.cost_center_id), s.hierarchy_id, s.realm_id
                            from spans s
                            join fuhito_org_unit_link l on l.parent_id = s.unit_id and l.hierarchy_id = s.hierarchy_id
                                and l.realm_id = s.realm_id and (s.span_to is null or l.valid_from < s.span_to)
                                and (l.valid_to is null or l.valid_to > s.span_from)
                            join fuhito_org_unit_version v on v.org_unit_id = l.child_id
                                and (v.valid_to is null or v.valid_to > greatest(s.span_from, l.valid_from))
                                and (least(s.span_to, l.valid_to) is null
                                    or v.valid_from < least(s.span_to, l.valid_to)))
                        select s.unit_id, s.span_from, s.span_to, s.work_location_id, s.cost_center_id from spans s"""
                        .formatted(realmOfUnit("v", "ex-new-unit-1")));
                assertIndexFinds(spans, VERSION_OF_UNIT, "org_unit_id", "'ex-new-unit-1'");
                assertIndexOrHashFinds(spans, "idx_fuhito_org_unit_link_parent", "parent_id",
                        "\\(s(_\\d+)?\\.unit_id\\)", "\\(s(_\\d+)?\\.hierarchy_id\\)");
                assertIndexOrHashFinds(spans, VERSION_OF_UNIT, "org_unit_id", "\\(l(_\\d+)?\\.child_id\\)");

                String rows = walk(statement, """
                        select a.realm_id, a.assignment_id, a.org_unit_id, a.valid_from, a.valid_to
                        from fuhito_assignment a where a.org_unit_id = any('{ex-new-unit-15,ex-new-unit-16}')
                        and a.work_location_id is null and (a.valid_to is null or a.valid_to > '2026-04-01')""");
                assertIndexFinds(rows, "idx_fuhito_assignment_org_unit", "org_unit_id", "ANY");
            }
            connection.rollback();
        }
    }
}
