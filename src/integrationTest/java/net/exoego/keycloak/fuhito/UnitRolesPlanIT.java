package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The plans of the reads of unit roles by the employment or by the org unit. ReportsAndHoldersPlanIT checks the walk
// up for the holders, and PersonExternalIdsPlanIT the delete of the IDs of other systems of a person.
class UnitRolesPlanIT {

    private static final Pattern INDEX = Pattern.compile("(?:using|Bitmap Index Scan on) (\\w+)");

    private static final String MORE_TABLES = "fuhito_ref_item, fuhito_unit_role";

    private static final String PERSON = "'ex-new-person-20'";
    private static final String ON = "r.valid_from <= '2026-04-01'"
            + " and (r.valid_to is null or r.valid_to > '2026-04-01')";
    private static final String FROM = """
            from fuhito_unit_role r join fuhito_employment e on e.id = r.employment_id
            join fuhito_person p on p.id = e.person_id where r.realm_id = 'ex-new-realm' and""";
    private static final String WITH_PERSON = "select r.*, e.person_id, p.user_id " + FROM;
    private static final String EMPLOYMENTS = "select e.id from fuhito_employment e where e.person_id = " + PERSON;
    private static final String OF_PERSON_ON = " e.person_id = %s and %s".formatted(PERSON, ON);
    private static final String PAGE = " offset 0 rows fetch first 100 rows only";
    private static final String COUNT_OF_UNIT = """
            select count(r.id) from fuhito_unit_role r where r.realm_id = 'ex-new-realm'
            and r.org_unit_id = 'ex-new-unit-7' and""";

    private static final Set<String> OF_PERSON_ON_DATE = Set.of("idx_fuhito_employment_person",
            "idx_fuhito_unit_role_employment", "pk_fuhito_person");
    private static final Set<String> BY_UNIT = Set.of("idx_fuhito_unit_role_org_unit");

    // Each read with the indexes of its plan.
    private static final Map<String, Set<String>> READS = new LinkedHashMap<>();

    static {
        // findByEmployment, ofPersonOn and countOfPersonOn of JpaUnitRoleStore.
        READS.put(WITH_PERSON + " r.employment_id = 'ex-new-employment-7' order by r.valid_from, r.id",
                Set.of("idx_fuhito_unit_role_employment", "pk_fuhito_employment", "pk_fuhito_person"));
        READS.put(WITH_PERSON + OF_PERSON_ON + " order by r.valid_from, r.id" + PAGE, OF_PERSON_ON_DATE);
        READS.put("select count(r.id) " + FROM + OF_PERSON_ON, OF_PERSON_ON_DATE);
        // The delete of RealmData.deletePerson, the scopes of a write of a person and of an employment, and the check
        // of the removal of an employment.
        READS.put("""
                delete from fuhito_unit_role r where r.employment_id in (%s)
                and r.realm_id = (select distinct x.realm_id from fuhito_person x where x.id = %s)
                and (select distinct x.realm_id from fuhito_person x where x.id = %2$s) = 'ex-new-realm'"""
                .formatted(EMPLOYMENTS, PERSON),
                Set.of("pk_fuhito_person", "idx_fuhito_employment_person", "idx_fuhito_unit_role_employment"));
        READS.put("select r.realm_id, r.id from fuhito_unit_role r where r.employment_id in (" + EMPLOYMENTS + ")",
                Set.of("idx_fuhito_employment_person", "idx_fuhito_unit_role_employment"));
        READS.put("""
                select r.realm_id, r.id from fuhito_unit_role r where r.employment_id in
                (select e.id from fuhito_employment e where e.id = 'ex-new-employment-7')""",
                Set.of("pk_fuhito_employment", "idx_fuhito_unit_role_employment"));
        READS.put("select distinct x.realm_id from fuhito_unit_role x where x.employment_id = 'ex-new-employment-7'",
                Set.of("idx_fuhito_unit_role_employment"));
        // The check of the removal of an org unit, JpaOrgUnitStore.unitRolesHoldingFrom, and searchOn and
        // countSearchOn of JpaUnitRoleStore.
        READS.put("select distinct x.realm_id from fuhito_unit_role x where x.org_unit_id = 'ex-new-unit-7'", BY_UNIT);
        READS.put(COUNT_OF_UNIT + " (r.valid_to is null or r.valid_to > '2026-04-01')", BY_UNIT);
        READS.put(WITH_PERSON + " " + ON + " and r.org_unit_id = 'ex-new-unit-7' order by r.id" + PAGE,
                Set.of("idx_fuhito_unit_role_org_unit", "pk_fuhito_employment", "pk_fuhito_person"));
        READS.put(COUNT_OF_UNIT + " " + ON, BY_UNIT);
    }

    // The rows are written once for both statistics, as in AssignmentRowsPlanIT. Each plan is read before the next
    // analyze, and the checks run at the end, so a failure shows every read with both statistics.
    @Test
    void aReadOfUnitRolesByTheEmploymentOrTheOrgUnitUsesItsIndexWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            realmsWithOldStatistics(statement, MORE_TABLES);
            moreRows(statement, "ex-old", 20);
            statement.execute("analyze %s".formatted(MORE_TABLES));
            moreRows(statement, "ex-new", 3000);
            List<Executable> checks = new ArrayList<>(checks(statement, "old"));
            freshStatistics(statement);
            statement.execute("analyze %s".formatted(MORE_TABLES));
            checks.addAll(checks(statement, "fresh"));
            String overlap = assertThrows(SQLException.class, () -> statement.execute("""
                    insert into fuhito_unit_role (id, realm_id, employment_id, org_unit_id, role_id, valid_from)
                    values ('ex-new-unit-role-0', 'ex-new-realm', 'ex-new-employment-7', 'ex-new-unit-7',
                    'ex-new-role-2', '2026-05-01')""")).getMessage();
            connection.rollback();
            assertAll(checks);
            assertTrue(overlap.contains("ex_fuhito_unit_role_period"), overlap);
        }
    }

    private static List<Executable> checks(Statement statement, String statistics) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, Set<String>> read : READS.entrySet()) {
            String plan = plan(statement, "explain " + read.getKey());
            Set<String> found = new TreeSet<>();
            Matcher index = INDEX.matcher(plan);
            while (index.find()) {
                found.add(index.group(1));
            }
            checks.add(() -> assertEquals(new TreeSet<>(read.getValue()), found, statistics + "\n" + plan));
        }
        return checks;
    }

    // Three roles, and a role over its own unit for each employment.
    private static void moreRows(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes)
                select '%1$s-role-' || i, '%1$s-realm', 'UNIT_ROLE', 'R' || i, '{}' from generate_series(1, 3) i""", """
                insert into fuhito_unit_role (id, realm_id, employment_id, org_unit_id, role_id, includes_descendants,
                valid_from) select '%1$s-unit-role-' || i, '%1$s-realm', '%1$s-employment-' || i, '%1$s-unit-' || i,
                '%1$s-role-' || (i %% 3 + 1), i %% 2 = 0, '2026-04-01' from generate_series(1, %2$d) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }
}
