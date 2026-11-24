package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.SQLException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the reads that look up the rows of assignments by their IDs.
class AssignmentRowsPlanIT {

    private static final String ONE = "'ex-new-assignment-7'";
    private static final String SOME = "any('{ex-new-assignment-7,ex-new-assignment-27}')";
    private static final String REALM_OF_ONE =
            "(select distinct r.realm_id from fuhito_assignment r where r.assignment_id = %s)".formatted(ONE);

    // rows, rowsOf, deleteAll, endedOnStart and firstRowsOfPeople of JpaAssignmentStore, the removal of an assignment,
    // JpaWriteScopes.assignmentsBeside, and ExternalIds.deleteOfGoneAssignments.
    private static final List<String> READS = List.of(
            "select * from fuhito_assignment a where a.assignment_id = %s order by a.valid_from".formatted(ONE),
            "select * from fuhito_assignment a where a.assignment_id = %s order by a.assignment_id, a.valid_from"
                    .formatted(SOME),
            "select distinct a.assignment_id from fuhito_assignment a where a.assignment_id = " + SOME,
            """
            select x.assignment_id, x.realm_id, y.* from fuhito_assignment x
            join fuhito_employment ex on ex.id = x.employment_id
            join fuhito_employment ey on ey.person_id = ex.person_id
            join fuhito_assignment y on y.employment_id = ey.id and y.valid_to = x.valid_from
            and y.is_primary = x.is_primary
            where x.assignment_id = %s
            and not exists (select 1 from fuhito_assignment o where o.assignment_id = x.assignment_id
            and o.valid_from < x.valid_from)
            and y.valid_from = (select max(n.valid_from) from fuhito_assignment n
            where n.assignment_id = y.assignment_id)
            order by x.assignment_id, y.assignment_id""".formatted(SOME),
            """
            select e.id, x.* from fuhito_assignment x
            join fuhito_employment ex on ex.id = x.employment_id
            join fuhito_employment e on e.person_id = ex.person_id
            where e.id = any('{ex-new-employment-7,ex-new-employment-27}')
            and not exists (select 1 from fuhito_assignment o where o.assignment_id = x.assignment_id
            and o.valid_from < x.valid_from)
            order by e.id, x.assignment_id""",
            "select distinct x.realm_id from fuhito_assignment x where x.assignment_id = " + ONE,
            "delete from fuhito_assignment x where x.assignment_id = %s and x.realm_id = %s and %2$s = 'ex-new-realm'"
                    .formatted(ONE, REALM_OF_ONE),
            """
            select distinct a.realm_id, a.assignment_id from fuhito_assignment a where a.employment_id in
            (select p.id from fuhito_employment p where p.person_id in
            (select e.person_id from fuhito_employment e where e.id in
            (select b.employment_id from fuhito_assignment b where b.assignment_id = %s)))""".formatted(ONE),
            """
            delete from fuhito_external_id x where x.realm_id = 'ex-new-realm' and x.resource_kind = 'ASSIGNMENT'
            and x.resource_id = %s
            and not exists (select a.id from fuhito_assignment a where a.assignment_id = x.resource_id)"""
                    .formatted(SOME));

    // The rows are written once for both statistics, as in OrgUnitVersionPlanIT. Each plan is read before the next
    // analyze, and the checks run at the end, so a failure shows every read with both statistics.
    @Test
    void aLookupOfTheRowsOfAnAssignmentUsesTheirUniqueKeyWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement, "fuhito_external_id");
            externalIds(statement, "ex-old", 20);
            statement.execute("analyze fuhito_external_id");
            externalIds(statement, "ex-new", 3000);
            List<Executable> checks = new ArrayList<>(checks(statement));
            freshStatistics(statement);
            statement.execute("analyze fuhito_external_id");
            checks.addAll(checks(statement));
            String overlap = assertThrows(SQLException.class, () -> statement.execute("""
                    insert into fuhito_assignment (id, realm_id, assignment_id, employment_id, org_unit_id,
                    valid_from, is_primary, attributes) values ('ex-new-row-0', 'ex-new-realm', 'ex-new-assignment-7',
                    'ex-new-employment-7', 'ex-new-unit-8', '2026-05-01', false, '{}')""")).getMessage();
            connection.rollback();
            assertAll(checks);
            assertTrue(overlap.contains("ex_fuhito_assignment_period"), overlap);
        }
    }

    private static List<Executable> checks(Statement statement) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String read : READS) {
            String plan = plan(statement, "explain " + read);
            checks.add(() -> {
                assertTrue(plan.contains("uk_fuhito_assignment_from"), plan);
                assertFalse(plan.contains("ex_fuhito_assignment_period"), plan);
            });
        }
        return checks;
    }

    private static void externalIds(Statement statement, String prefix, int count) throws Exception {
        statement.execute("""
                insert into fuhito_external_id (id, realm_id, resource_kind, resource_id, source, external_id)
                select '%1$s-xid-' || i, '%1$s-realm', 'ASSIGNMENT', '%1$s-assignment-' || i, 'sheet', 'A' || i
                from generate_series(1, %2$d) i""".formatted(prefix, count));
    }
}
