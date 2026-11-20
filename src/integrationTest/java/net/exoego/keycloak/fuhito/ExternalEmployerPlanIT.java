package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

// The plans of the reads of employments by an external employer, the list of JpaEmploymentStore and the check of
// JpaRefItemStore whether an item is in use, while the statistics do not know a new realm, and after they do.
class ExternalEmployerPlanIT {

    private static final Pattern INDEX = Pattern.compile("(?:using|Bitmap Index Scan on) (\\w+)");

    private static final Set<String> BY_AGENCY = Set.of("idx_fuhito_employment_external_employer");

    // The rows are written once for both statistics, as in ReportsAndHoldersPlanIT. The checks run at the end, so a
    // failure shows every read with both statistics.
    @Test
    void theReadsByAnExternalEmployerTakeItsIndexWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            rows(statement);
            List<Executable> checks = new ArrayList<>(checks(statement, "old"));
            freshStatistics(statement);
            statement.execute("analyze fuhito_ref_item");
            checks.addAll(checks(statement, "fresh"));
            connection.rollback();
            assertAll(checks);
        }
    }

    // The large agency employs 240 people of the new realm, and the small one 60.
    private static List<Executable> checks(Statement statement, String statistics) throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String agency : List.of("ex-new-agency-1", "ex-new-agency-2")) {
            String byAgency = "from fuhito_employment e where e.external_employer_id = '%s' and %s"
                    .formatted(agency, ofItem(agency));
            String onDate = " and e.valid_from <= '2026-04-01' and (e.valid_to is null or e.valid_to > '2026-04-01')";
            for (String query : List.of(
                    "select * " + byAgency + " order by e.id fetch first 100 rows only",
                    "select * " + byAgency + onDate + " order by e.id fetch first 100 rows only",
                    "select * " + byAgency + " and e.id > 'ex-new-dispatched-50' order by e.id"
                            + " fetch first 100 rows only",
                    "select count(e.id) " + byAgency + onDate,
                    // The count of the list with no date is also the check whether the item is in use.
                    "select count(e.id) " + byAgency)) {
                String plan = plan(statement, "explain " + query);
                // The realm of the item comes by its key, or by a scan of a table of a few items.
                Set<String> found = indexes(plan);
                found.remove("pk_fuhito_ref_item");
                checks.add(() -> assertEquals(BY_AGENCY, found, statistics + " " + query + "\n" + plan));
            }
        }
        return checks;
    }

    // The condition of Rows.ofRecord for a reference item.
    private static String ofItem(String id) {
        String realm = "(select distinct r.realm_id from fuhito_ref_item r where r.id = '%s')".formatted(id);
        return "e.realm_id = %s and %s = 'ex-new-realm'".formatted(realm, realm);
    }

    // The rows of OldStatistics, and two external employers in each realm with people that they employ. The
    // statistics know the people of OldStatistics, and none of the people of the agencies.
    private static void rows(Statement statement) throws Exception {
        KeycloakEnvironment.sql("vacuum full fuhito_ref_item");
        realmsWithOldStatistics(statement);
        moreRows(statement, "ex-old", 20);
        statement.execute("analyze fuhito_ref_item");
        moreRows(statement, "ex-new", 3000);
    }

    private static void moreRows(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes) values
                ('%1$s-agency-1', '%1$s-realm', 'EXTERNAL_EMPLOYER', 'staffing', '{}'),
                ('%1$s-agency-2', '%1$s-realm', 'EXTERNAL_EMPLOYER', 'contracting', '{}')""", """
                insert into fuhito_person (id, realm_id, user_id, attributes)
                select '%1$s-dispatched-person-' || i, '%1$s-realm', '%1$s-dispatched-user-' || i, '{}'
                from generate_series(1, %2$d / 10) i""", """
                insert into fuhito_employment (id, realm_id, valid_from, staff_number, person_id, external_employer_id)
                select '%1$s-dispatched-' || i, '%1$s-realm', '2026-04-01', 'D' || i, '%1$s-dispatched-person-' || i,
                '%1$s-agency-' || (case when i %% 5 = 0 then 2 else 1 end) from generate_series(1, %2$d / 10) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }

    private static Set<String> indexes(String plan) {
        Set<String> found = new TreeSet<>();
        Matcher index = INDEX.matcher(plan);
        while (index.find()) {
            found.add(index.group(1));
        }
        return found;
    }
}
