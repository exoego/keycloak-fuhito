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

// The plan of the delete of the IDs of other systems of a person and of the records of the person, which the erase of
// a person runs, and a restore for each person that it erases again. While the statistics do not know a new realm,
// and after they do.
class PersonExternalIdsPlanIT {

    private static final Pattern INDEX = Pattern.compile("(?:using|Bitmap Index Scan on) (\\w+)");

    private static final String TABLES = """
            fuhito_ref_item, fuhito_qualification, fuhito_reporting_line, fuhito_unit_role, fuhito_external_id""";

    private static final String PERSON = "'ex-new-person-20'";

    // Each kind and record takes the unique key of the IDs. The rows of each person and of each record take their
    // own indexes.
    private static final Set<String> INDEXES = Set.of("uk_fuhito_external_id_source", "pk_fuhito_person",
            "idx_fuhito_employment_person", "idx_fuhito_assignment_employment", "idx_fuhito_qualification_person",
            "idx_fuhito_reporting_line_manager_person", "idx_fuhito_reporting_line_assignment",
            "ex_fuhito_unit_role_period");

    // The rows are written once for both statistics, as in ExternalEmployerPlanIT.
    @Test
    void theIdsOfAPersonAndOfTheRecordsTakeTheirKeyWithOldAndWithFreshStatistics() throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            rows(statement);
            List<Executable> checks = new ArrayList<>();
            checks.add(check(statement, "old"));
            freshStatistics(statement);
            statement.execute("analyze %s".formatted(TABLES));
            checks.add(check(statement, "fresh"));
            connection.rollback();
            assertAll(checks);
        }
    }

    // The delete of RealmData.deletePerson.
    private static Executable check(Statement statement, String statistics) throws Exception {
        String employments = "select e.id from fuhito_employment e where e.person_id = " + PERSON;
        String plan = plan(statement, """
                explain delete from fuhito_external_id x where %1$s and (x.resource_kind, x.resource_id) in (
                select 'PERSON', p.id from fuhito_person p where p.id = %2$s
                union all select 'EMPLOYMENT', e.id from fuhito_employment e where e.person_id = %2$s
                union all select 'ASSIGNMENT', a.assignment_id from fuhito_assignment a where a.employment_id in (%3$s)
                union all select 'QUALIFICATION', q.id from fuhito_qualification q where q.person_id = %2$s
                union all select 'REPORTING_LINE', r.id from fuhito_reporting_line r where r.manager_person_id = %2$s
                union all select 'REPORTING_LINE', r.id from fuhito_reporting_line r where r.assignment_id in
                (select a.assignment_id from fuhito_assignment a where a.employment_id in (%3$s))
                union all select 'UNIT_ROLE', r.id from fuhito_unit_role r where r.employment_id in (%3$s))"""
                .formatted(ofPerson(), PERSON, employments));
        Set<String> found = new TreeSet<>();
        Matcher index = INDEX.matcher(plan);
        while (index.find()) {
            found.add(index.group(1));
        }
        return () -> assertEquals(new TreeSet<>(INDEXES), found, statistics + "\n" + plan);
    }

    // The condition of Rows.ofRecord for the person.
    private static String ofPerson() {
        String realm = "(select distinct r.realm_id from fuhito_person r where r.id = %s)".formatted(PERSON);
        return "x.realm_id = %s and %s = 'ex-new-realm'".formatted(realm, realm);
    }

    // The rows of OldStatistics, and a qualification for each person, a reporting line for each assignment with a
    // manager for each ten people, and a role over an org unit for each employment. Each person and each record has
    // an ID of another system. With fewer IDs, PostgreSQL reads the table whole, because that costs less than the
    // lookups. The statistics know the rows of the old realm.
    private static void rows(Statement statement) throws Exception {
        realmsWithOldStatistics(statement, TABLES);
        moreRows(statement, "ex-old", 20);
        statement.execute("analyze %s".formatted(TABLES));
        moreRows(statement, "ex-new", 3000);
    }

    private static void moreRows(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes) values
                ('%1$s-license', '%1$s-realm', 'QUALIFICATION', 'license', '{}'),
                ('%1$s-approver', '%1$s-realm', 'UNIT_ROLE', 'approver', '{}')""", """
                insert into fuhito_qualification (id, realm_id, kind_id, valid_from, attributes, person_id)
                select '%1$s-qualification-' || i, '%1$s-realm', '%1$s-license', '2026-04-01', '{}',
                '%1$s-person-' || i from generate_series(1, %2$d) i""", """
                insert into fuhito_reporting_line (id, realm_id, assignment_id, kind, valid_from, manager_person_id)
                select '%1$s-line-' || i, '%1$s-realm', '%1$s-assignment-' || i, 'DOTTED', '2026-04-01',
                '%1$s-person-' || (i / 10 + 1) from generate_series(1, %2$d) i""", """
                insert into fuhito_unit_role (id, realm_id, employment_id, org_unit_id, role_id, valid_from)
                select '%1$s-role-' || i, '%1$s-realm', '%1$s-employment-' || i, '%1$s-unit-' || (i %% 20 + 1),
                '%1$s-approver', '2026-04-01' from generate_series(1, %2$d) i""", """
                insert into fuhito_external_id (id, realm_id, resource_kind, resource_id, source, external_id)
                select '%1$s-xid-' || r.name || '-' || i, '%1$s-realm', r.kind, '%1$s-' || r.name || '-' || i,
                'sheet', r.kind || i from generate_series(1, %2$d) i, (values ('PERSON', 'person'),
                ('EMPLOYMENT', 'employment'), ('ASSIGNMENT', 'assignment'), ('QUALIFICATION', 'qualification'),
                ('REPORTING_LINE', 'line'), ('UNIT_ROLE', 'role')) r (kind, name)""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }
}
