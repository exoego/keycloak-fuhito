package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static net.exoego.keycloak.fuhito.OldStatistics.freshStatistics;
import static net.exoego.keycloak.fuhito.OldStatistics.plan;
import static net.exoego.keycloak.fuhito.OldStatistics.realmsWithOldStatistics;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plan of the read of the versions of assignments in AssignmentReads, as Hibernate writes it.
class AssignmentVersionsPlanIT {

    // A page of a list has at most 1,000 entries.
    private static final List<Integer> SIZES = List.of(1, 10, 1000);

    // The rows are written once for both statistics, as in OrgUnitVersionPlanIT. Each plan is read before the next
    // analyze, and the checks run at the end, so a failure shows every read with both statistics.
    @Test
    void theVersionsOfAssignmentsAreLookedUpByTheirKeyWithOldAndWithFreshStatistics() throws Exception {
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
        for (int size : SIZES) {
            String ids = IntStream.range(0, size).mapToObj(i -> "ex-new-assignment-" + (1 + i * 3))
                    .collect(Collectors.joining(",", "'{", "}'"));
            String plan = plan(statement, """
                    explain select i.id, (select v.version from fuhito_assignment_version v where v.id = i.id)
                    from (select cast(unnest(cast(%s as varchar[])) as varchar)) i(id)""".formatted(ids));
            checks.add(() -> {
                assertTrue(plan.contains("Index Scan using pk_fuhito_assignment_version"), plan);
                assertFalse(plan.contains("Seq Scan"), plan);
            });
        }
        return checks;
    }
}
