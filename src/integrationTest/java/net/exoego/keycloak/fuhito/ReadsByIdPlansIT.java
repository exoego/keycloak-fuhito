package net.exoego.keycloak.fuhito;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The reads by an ID outside the stores of employments and assignments, while the statistics do not know a new
// realm. Each check names every index that the plan reads, so an index that starts with the realm fails it.
class ReadsByIdPlansIT {

    private static final String REALM = "'ex-new-realm'";
    private static final String PERSON = "'ex-new-person-20'";
    private static final String ON = "%1$s.valid_from <= '2026-04-01'"
            + " and (%1$s.valid_to is null or %1$s.valid_to > '2026-04-01')";

    // The rows of an assignment take its unique key or the GiST index of their periods. #590 is about the GiST index.
    private static final String BY_ASSIGNMENT = "uk_fuhito_assignment_from or ex_fuhito_assignment_period";

    private static final Pattern INDEX = Pattern.compile("(?:using|Bitmap Index Scan on) (\\w+)");

    @FunctionalInterface
    private interface Check {
        void run(Statement statement) throws Exception;
    }

    @Test
    void theCountsOfTheEmploymentsOfAnEmployerUseItsIndex() throws Exception {
        withOldStatistics(statement -> {
            String employer = "from fuhito_employment e where e.employer_id = 'ex-new-unit-2' and "
                    + ofRecord("e", "fuhito_org_unit", "id", "ex-new-unit-2");
            assertIndexes(statement, "select count(e.id) " + employer
                    + " and e.valid_from >= '2026-01-01' and e.valid_from < '9999-12-31'",
                    "pk_fuhito_org_unit", "idx_fuhito_employment_employer");
            assertIndexes(statement, "select count(e.id) " + employer
                    + " and (e.valid_to is null or e.valid_to > '2026-04-01')",
                    "pk_fuhito_org_unit", "idx_fuhito_employment_employer");
        });
    }

    @Test
    void theChecksAndTheDeletesOfARemovalFindTheRowsByTheirIds() throws Exception {
        withOldStatistics(statement -> {
            String realms = "select distinct x.realm_id from ";
            assertIndexes(statement, realms + "fuhito_assignment x where x.org_unit_id = 'ex-new-unit-7'",
                    "idx_fuhito_assignment_org_unit");
            assertIndexes(statement, realms + "fuhito_employment x where x.employer_id = 'ex-new-unit-2'",
                    "idx_fuhito_employment_employer");
            assertIndexes(statement, realms + "fuhito_org_unit x where x.id = 'ex-new-unit-7'"
                    + " and x.kc_org_id is not null", "pk_fuhito_org_unit");
            assertIndexes(statement, realms + "fuhito_assignment x where x.employment_id = 'ex-new-employment-7'",
                    "idx_fuhito_assignment_employment");
            assertIndexes(statement, realms
                    + "fuhito_employment x where x.continues_employment_id = 'ex-new-employment-7'",
                    "idx_fuhito_employment_continues");
            assertIndexes(statement, realms + "fuhito_reporting_line x where x.assignment_id = 'ex-new-assignment-7'",
                    "idx_fuhito_reporting_line_assignment");
            assertIndexes(statement, realms + "fuhito_org_unit x where x.id = 'ex-new-unit-7'", "pk_fuhito_org_unit");
            assertIndexes(statement, realms + "fuhito_employment x where x.id = 'ex-new-employment-7'",
                    "pk_fuhito_employment");
            assertIndexes(statement, realms + "fuhito_assignment x where x.assignment_id = 'ex-new-assignment-7'",
                    BY_ASSIGNMENT);

            String employment = ofRecord("x", "fuhito_employment", "id", "ex-new-employment-7");
            assertIndexes(statement, "delete from fuhito_employment_terms x"
                    + " where x.employment_id = 'ex-new-employment-7' and " + employment,
                    "pk_fuhito_employment", "uk_fuhito_employment_terms_from");
            assertIndexes(statement, "delete from fuhito_employment x where x.id = 'ex-new-employment-7' and "
                    + employment, "pk_fuhito_employment");
            assertIndexes(statement, "delete from fuhito_org_unit x where x.id = 'ex-new-unit-2999' and "
                    + ofRecord("x", "fuhito_org_unit", "id", "ex-new-unit-2999"), "pk_fuhito_org_unit");
            assertIndexes(statement, "delete from fuhito_assignment x"
                    + " where x.assignment_id = 'ex-new-assignment-7' and "
                    + ofRecord("x", "fuhito_assignment", "assignment_id", "ex-new-assignment-7"),
                    BY_ASSIGNMENT);
        });
    }

    @Test
    void theOriginOfAResourceAndTheIdsOfGoneAssignmentsAreFoundByTheirIds() throws Exception {
        withOldStatistics(statement -> {
            assertIndexes(statement, "select x.realm_id, x.source from fuhito_assignment x"
                    + " where x.assignment_id = 'ex-new-assignment-7' order by x.valid_from", BY_ASSIGNMENT);
            for (String[] table : List.of(new String[] {"org_unit", "unit"}, new String[] {"person", "person"},
                    new String[] {"employment", "employment"}, new String[] {"qualification", "qualification"},
                    new String[] {"reporting_line", "line"})) {
                assertIndexes(statement, "select x.realm_id, x.id from fuhito_%s x where x.id = 'ex-new-%s-7'"
                        .formatted(table[0], table[1]), "pk_fuhito_" + table[0]);
            }
            assertIndexes(statement, """
                    delete from fuhito_external_id x where x.realm_id = 'ex-new-realm'
                    and x.resource_kind = 'ASSIGNMENT' and x.resource_id = any('{ex-new-assignment-7,ex-new-assignment-8}')
                    and not exists (select a.id from fuhito_assignment a where a.assignment_id = x.resource_id)""",
                    "uk_fuhito_external_id_source", BY_ASSIGNMENT);
        });
    }

    // The roles over org units have no index that starts with the realm, so the check of the IDs of other systems
    // leaves them out.
    @Test
    void theRemovalOfAPersonFindsTheRowsByThePerson() throws Exception {
        withOldStatistics(statement -> {
            String employments = "select e.id from fuhito_employment e where e.person_id = " + PERSON;
            String assignments = "select a.assignment_id from fuhito_assignment a where a.employment_id in ("
                    + employments + ")";
            assertIndexes(statement, """
                    delete from fuhito_external_id x where %s and ((x.resource_kind = 'PERSON' and x.resource_id = %s)
                    or (x.resource_kind = 'EMPLOYMENT' and x.resource_id in (%s))
                    or (x.resource_kind = 'ASSIGNMENT' and x.resource_id in (%s))
                    or (x.resource_kind = 'QUALIFICATION' and x.resource_id in
                    (select q.id from fuhito_qualification q where q.person_id = %s))
                    or (x.resource_kind = 'REPORTING_LINE' and (x.resource_id in
                    (select r.id from fuhito_reporting_line r where r.manager_person_id = %s)
                    or x.resource_id in (select r.id from fuhito_reporting_line r where r.assignment_id in (%s)))))"""
                    .formatted(ofPerson("x"), PERSON, employments, assignments, PERSON, PERSON, assignments),
                    "pk_fuhito_person", "idx_fuhito_employment_person", "idx_fuhito_assignment_employment",
                    "idx_fuhito_qualification_person", "idx_fuhito_reporting_line_manager_person",
                    "idx_fuhito_reporting_line_assignment");
            assertIndexes(statement, "delete from fuhito_reporting_line r where r.manager_person_id = %s and %s"
                    .formatted(PERSON, ofPerson("r")), "pk_fuhito_person", "idx_fuhito_reporting_line_manager_person");
            assertIndexes(statement, "delete from fuhito_reporting_line r where r.assignment_id in (%s) and %s"
                    .formatted(assignments, ofPerson("r")), "pk_fuhito_person", "idx_fuhito_employment_person",
                    "idx_fuhito_assignment_employment", "idx_fuhito_reporting_line_assignment");
            assertIndexes(statement, "delete from fuhito_assignment x where x.employment_id in (%s) and %s"
                    .formatted(employments, ofPerson("x")), "pk_fuhito_person", "idx_fuhito_employment_person",
                    "idx_fuhito_assignment_employment");
            assertIndexes(statement, "delete from fuhito_employment_terms x where x.employment_id in (%s) and %s"
                    .formatted(employments, ofPerson("x")), "pk_fuhito_person", "idx_fuhito_employment_person",
                    "uk_fuhito_employment_terms_from");
            assertIndexes(statement, "delete from fuhito_qualification q where q.person_id = %s and %s"
                    .formatted(PERSON, ofPerson("q")), "pk_fuhito_person", "idx_fuhito_qualification_person");
            assertIndexes(statement, "delete from fuhito_employment e where e.person_id = %s and %s"
                    .formatted(PERSON, ofPerson("e")), "pk_fuhito_person", "idx_fuhito_employment_person");
            assertIndexes(statement, "delete from fuhito_person p where p.id = %s and %s"
                    .formatted(PERSON, ofPerson("p")), "pk_fuhito_person");
        });
    }

    // The check of a head looks up the terms of each row by its employment. The rest of the walk of the reports is
    // out of this check.
    @Test
    void theReadsOfReportingLinesFindThemByTheirIds() throws Exception {
        withOldStatistics(statement -> {
            Set<String> head = indexes(statement, """
                    select a.id from fuhito_assignment a join fuhito_employment e on e.id = a.employment_id
                    where a.realm_id = 'ex-new-realm' and a.org_unit_id = 'ex-new-unit-7' and %s and a.is_head
                    and exists (select 1 from fuhito_employment_terms t where t.employment_id = a.employment_id
                    and t.status = 'ACTIVE' and %s)""".formatted(ON.formatted("a"), ON.formatted("t")));
            assertTrue(head.contains("uk_fuhito_employment_terms_from"), head::toString);
            assertFalse(head.contains("idx_fuhito_employment_terms_to"), head::toString);

            assertIndexes(statement, "select * from fuhito_reporting_line l"
                    + " where l.assignment_id = 'ex-new-assignment-7' order by l.valid_from, l.id",
                    "idx_fuhito_reporting_line_assignment");
            assertIndexes(statement, """
                    select * from fuhito_reporting_line l join fuhito_person m on m.id = l.manager_person_id
                    where l.assignment_id = any('{ex-new-assignment-7,ex-new-assignment-8}')
                    order by l.valid_from, l.id""", "idx_fuhito_reporting_line_assignment", "pk_fuhito_person");
            assertIndexes(statement, "select * from fuhito_reporting_line l where l.manager_person_id = " + PERSON
                    + " order by l.valid_from, l.id", "idx_fuhito_reporting_line_manager_person");
        });
    }

    @Test
    void theMaskOfAPersonFindsTheRowsByTheirIds() throws Exception {
        withOldStatistics(statement -> {
            assertIndexes(statement, "select * from fuhito_employment e where e.person_id = " + PERSON,
                    "idx_fuhito_employment_person");
            assertIndexes(statement, "select * from fuhito_employment_terms t"
                    + " where t.employment_id = any('{ex-new-employment-20}')", "uk_fuhito_employment_terms_from");
            assertIndexes(statement, "select * from fuhito_assignment a"
                    + " where a.employment_id = any('{ex-new-employment-20}')", "idx_fuhito_assignment_employment");
            assertIndexes(statement, "select * from fuhito_qualification q where q.person_id = " + PERSON,
                    "idx_fuhito_qualification_person");
            assertIndexes(statement, "select l.realm_id, l.id from fuhito_reporting_line l where l.manager_person_id = "
                    + PERSON, "idx_fuhito_reporting_line_manager_person");
            assertIndexes(statement, "select l.realm_id, l.id from fuhito_reporting_line l"
                    + " where l.assignment_id = any('{ex-new-assignment-20}')", "idx_fuhito_reporting_line_assignment");
        });
    }

    @Test
    void theQualificationsAndThePeopleOfIdsAreFoundByThem() throws Exception {
        withOldStatistics(statement -> {
            assertIndexes(statement, """
                    select q.* from fuhito_qualification q join fuhito_person p on p.id = q.person_id
                    where p.realm_id = 'ex-new-realm' and p.user_id = 'ex-new-user-7' order by q.valid_from, q.id""",
                    "uk_fuhito_person_user", "idx_fuhito_qualification_person");
            assertIndexes(statement, "select * from fuhito_qualification q where q.person_id = 'ex-new-person-7'"
                    + " order by q.valid_from, q.id", "idx_fuhito_qualification_person");
            assertIndexes(statement, "select * from fuhito_person p"
                    + " where p.id = any('{ex-new-person-7,ex-new-person-8}')", "pk_fuhito_person");
        });
    }

    @Test
    void theScopesOfAWriteFindTheRowsByTheirIds() throws Exception {
        withOldStatistics(statement -> {
            String ofPerson = "select e.id from fuhito_employment e where e.person_id = " + PERSON;
            String ofEmployment = "select e.id from fuhito_employment e where e.id = 'ex-new-employment-7'";
            assertIndexes(statement, "select e.realm_id, e.id from fuhito_employment e where e.person_id = " + PERSON,
                    "idx_fuhito_employment_person");
            assertIndexes(statement, "select e.realm_id, e.id from fuhito_employment e"
                    + " where e.employer_id = 'ex-new-unit-2'", "idx_fuhito_employment_employer");
            assertIndexes(statement, "select distinct a.realm_id, a.assignment_id from fuhito_assignment a"
                    + " where a.employment_id in (" + ofPerson + ")",
                    "idx_fuhito_employment_person", "idx_fuhito_assignment_employment");
            assertIndexes(statement, "select distinct a.realm_id, a.assignment_id from fuhito_assignment a"
                    + " where a.employment_id in (" + ofEmployment + ")",
                    "pk_fuhito_employment", "idx_fuhito_assignment_employment");
            assertIndexes(statement, """
                    select l.realm_id, l.id from fuhito_reporting_line l where l.assignment_id in
                    (select a.assignment_id from fuhito_assignment a where a.employment_id in (%s))"""
                    .formatted(ofPerson),
                    "idx_fuhito_employment_person", "idx_fuhito_assignment_employment",
                    "idx_fuhito_reporting_line_assignment");
            assertIndexes(statement, "select q.realm_id, q.id from fuhito_qualification q"
                    + " where q.person_id = " + PERSON, "idx_fuhito_qualification_person");
            assertIndexes(statement, "select distinct a.realm_id, a.assignment_id from fuhito_assignment a"
                    + " where a.employment_id = 'ex-new-employment-7'", "idx_fuhito_assignment_employment");
            assertIndexes(statement, """
                    select distinct a.realm_id, a.assignment_id from fuhito_assignment a where a.employment_id in
                    (select b.employment_id from fuhito_assignment b where b.assignment_id = 'ex-new-assignment-7')""",
                    BY_ASSIGNMENT, "idx_fuhito_assignment_employment");
        });
    }

    // The log of a change order pages in the database, so the realm of the change order comes from a sub-select.
    @Test
    void theRowsOfAnExportAndTheLogOfAChangeOrderAreFoundByTheirIds() throws Exception {
        withOldStatistics(statement -> {
            assertIndexes(statement, """
                    select * from fuhito_assignment a
                    where a.assignment_id = any('{ex-new-assignment-7,ex-new-assignment-8}')
                    order by a.assignment_id, a.valid_from""", BY_ASSIGNMENT);
            String log = "from fuhito_change_log l where l.change_order_id = 'ex-new-order' and "
                    + ofRecord("l", "fuhito_change_order", "id", "ex-new-order");
            for (String query : List.of("select * " + log + " order by l.sequence_number fetch first 100 rows only",
                    "select count(l.id) " + log)) {
                Set<String> found = indexes(statement, query);
                found.remove("pk_fuhito_change_order");
                assertEquals(Set.of("idx_fuhito_change_log_order"), found);
            }
        });
    }

    private static void withOldStatistics(Check check) throws Exception {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            oldStatistics(statement);
            check.run(statement);
            connection.rollback();
        }
    }

    // The rows of OldStatistics, and a qualification for each person, a reporting line and an ID of another system
    // for each assignment, and an entry of the change log for each employment.
    static void oldStatistics(Statement statement) throws Exception {
        String tables = """
                fuhito_ref_item, fuhito_qualification, fuhito_reporting_line, fuhito_external_id, fuhito_change_order,
                fuhito_change_log""";
        KeycloakEnvironment.sql("vacuum " + tables);
        OldStatistics.realmsWithOldStatistics(statement);
        moreRows(statement, "ex-old", 20);
        statement.execute("analyze " + tables);
        moreRows(statement, "ex-new", 3000);
    }

    private static void moreRows(Statement statement, String prefix, int count) throws Exception {
        for (String insert : List.of("""
                insert into fuhito_ref_item (id, realm_id, kind, code, attributes)
                values ('%1$s-kind', '%1$s-realm', 'QUALIFICATION', 'q', '{}')""", """
                insert into fuhito_qualification (id, realm_id, kind_id, valid_from, attributes, person_id)
                select '%1$s-qualification-' || i, '%1$s-realm', '%1$s-kind', '2026-04-01', '{}', '%1$s-person-' || i
                from generate_series(1, %2$d) i""", """
                insert into fuhito_reporting_line (id, realm_id, assignment_id, kind, valid_from, manager_person_id)
                select '%1$s-line-' || i, '%1$s-realm', '%1$s-assignment-' || i, 'DOTTED', '2026-04-01',
                '%1$s-person-' || (i / 10 + 1) from generate_series(1, %2$d) i""", """
                insert into fuhito_external_id (id, realm_id, resource_kind, resource_id, source, external_id)
                select '%1$s-xid-' || i, '%1$s-realm', 'ASSIGNMENT', '%1$s-assignment-' || i, 'sheet', 'A' || i
                from generate_series(1, %2$d) i""", """
                insert into fuhito_change_order (id, realm_id, title, effective_date, created_at)
                values ('%1$s-order', '%1$s-realm', 'Order', '2026-04-01', now())""", """
                insert into fuhito_change_log (id, realm_id, recorded_at, resource_type, resource_id, operation,
                resource_path, change_order_id, sequence_number)
                select '%1$s-log-' || i, '%1$s-realm', now(), 'EMPLOYMENT', '%1$s-employment-' || i, 'CREATE', '/x',
                case when i %% 20 = 0 then '%1$s-order' end, i from generate_series(1, %2$d) i""")) {
            statement.execute(insert.formatted(prefix, count));
        }
    }

    // The condition of Rows.ofRecord.
    private static String ofRecord(String alias, String table, String key, String value) {
        String realm = "(select distinct r.realm_id from %s r where r.%s = '%s')".formatted(table, key, value);
        return "%s.realm_id = %s and %s = %s".formatted(alias, realm, realm, REALM);
    }

    private static String ofPerson(String alias) {
        return ofRecord(alias, "fuhito_person", "id", "ex-new-person-20");
    }

    private static void assertIndexes(Statement statement, String query, String... names) throws Exception {
        Set<String> found = new TreeSet<>();
        Set<String> byAssignment = Set.of("uk_fuhito_assignment_from", "ex_fuhito_assignment_period");
        indexes(statement, query).forEach(name -> found.add(byAssignment.contains(name) ? BY_ASSIGNMENT : name));
        assertEquals(new TreeSet<>(Set.of(names)), found, query);
    }

    private static Set<String> indexes(Statement statement, String query) throws Exception {
        Set<String> found = new TreeSet<>();
        Matcher index = INDEX.matcher(OldStatistics.plan(statement, "explain " + query));
        while (index.find()) {
            found.add(index.group(1));
        }
        return found;
    }
}
