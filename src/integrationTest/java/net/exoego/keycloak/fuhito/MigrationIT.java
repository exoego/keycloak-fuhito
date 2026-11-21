package net.exoego.keycloak.fuhito;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import liquibase.Scope;
import liquibase.analytics.configuration.AnalyticsArgs;
import liquibase.command.CommandScope;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.UpdateCountCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the changelog of the provider jar on a database with rows of an older schema, as an upgrade does.
 */
class MigrationIT {

    private static final String CHANGELOG = "META-INF/fuhito/changelog.xml";

    // The tables, the extension for the exclusion constraints, and the constraints.
    private static final int FIRST_SCHEMA = 3;

    @Test
    void rowsOfTheOlderSchemaMoveToTheNewOne() throws Exception {
        KeycloakEnvironment.sql("create database fuhito_migration");
        try (Connection connection = KeycloakEnvironment.connection("fuhito_migration");
                Statement statement = connection.createStatement();
                URLClassLoader jar = new URLClassLoader(
                        new URL[] {new File(System.getProperty("provider.jar")).toURI().toURL()},
                        MigrationIT.class.getClassLoader())) {
            // The first change set gives each realm of Keycloak its default hierarchy.
            statement.execute("create table REALM (ID varchar(36) primary key)");
            statement.execute("insert into REALM (ID) values ('realm-1')");
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            update(jar, database, FIRST_SCHEMA);
            statement.execute("""
                    insert into fuhito_ref_item (id, realm_id, kind, code, name, names_i18n, attributes) values
                    ('item-1', 'realm-1', 'JOB_TITLE', 'chief', '主任', '{"en":"Chief"}', '{}'),
                    ('item-2', 'realm-1', 'UNIT_TYPE', 'ward', '病棟', '{}', '{}')""");
            statement.execute("""
                    insert into fuhito_change_log (id, realm_id, sequence_number, recorded_at, resource_type,
                    resource_id, operation, resource_path, representation) values ('entry-1', 'realm-1', 1,
                    '2026-04-01 00:00:00', 'FUHITO_PERSON', 'person-1', 'CREATE', 'fuhito/people/person-1',
                    '{"name":"山田"}')""");
            statement.execute("""
                    insert into fuhito_org_unit (id, realm_id, kc_org_id) values ('unit-1', 'realm-1', 'org-1')""");
            statement.execute("""
                    insert into fuhito_idempotency_key (id, realm_id, key_value, request_path, request_hash, status,
                    body, created_at) values ('key-1', 'realm-1', 'key', '/fuhito/people', 'hash', 201,
                    '{"name":"山田"}', '2026-04-01 00:00:00')""");

            update(jar, database, null);

            // The items stay. Nothing was released, so their names do not move to the rows of each language.
            assertEquals(List.of("item-1 chief", "item-2 ward"), rows(statement,
                    "select id, code from fuhito_ref_item order by id"));
            assertEquals(List.of("is_primary language name period_id"), rows(statement, """
                    select string_agg(column_name, ' ' order by column_name) from information_schema.columns
                    where table_name = 'fuhito_ref_item_name' and column_name in ('period_id', 'language',
                    'is_primary', 'name', 'names_i18n')"""));
            assertEquals(List.of("0"), rows(statement, """
                    select count(*) from information_schema.columns
                    where table_name = 'fuhito_ref_item' and column_name in ('name', 'names_i18n')"""));
            // An entry of the change log keeps all but the state. It was a write.
            assertEquals(List.of("entry-1 1 FUHITO_PERSON person-1 CREATE WRITE null null"), rows(statement, """
                    select id, sequence_number, resource_type, resource_id, operation, kind, cause, cause_sequence
                    from fuhito_change_log"""));
            assertEquals(List.of("0"), rows(statement, """
                    select count(*) from information_schema.columns
                    where table_name = 'fuhito_change_log' and column_name = 'representation'"""));
            // No table keeps the responses to requests that were sent again.
            assertEquals(List.of("0"), rows(statement, """
                    select count(*) from information_schema.tables where table_name = 'fuhito_idempotency_key'"""));
            // A link to an organization takes every ID that Keycloak takes, and keeps its key.
            assertEquals(List.of("fuhito_org_unit 255 YES", "fuhito_projected_member 255 NO"), rows(statement, """
                    select table_name, character_maximum_length, is_nullable from information_schema.columns
                    where column_name = 'kc_org_id' order by table_name"""));
            assertEquals(List.of("unit-1 org-1"), rows(statement, "select id, kc_org_id from fuhito_org_unit"));
            statement.execute("""
                    insert into fuhito_org_unit (id, realm_id, kc_org_id)
                    values ('unit-2', 'realm-1', repeat('o', 255))""");
            assertTrue(assertThrows(SQLException.class, () -> statement.execute("""
                    insert into fuhito_org_unit (id, realm_id, kc_org_id)
                    values ('unit-3', 'realm-1', repeat('o', 255))"""))
                    .getMessage().contains("uk_fuhito_org_unit_kc_org"));
        }
    }

    private static void update(ClassLoader jar, Database database, Integer count) throws Exception {
        Scope.child(Map.of(Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(jar),
                AnalyticsArgs.ENABLED.getKey(), false), () -> {
                    CommandScope command = count == null ? new CommandScope(UpdateCommandStep.COMMAND_NAME)
                            .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                            : new CommandScope(UpdateCountCommandStep.COMMAND_NAME)
                                    .addArgumentValue(UpdateCountCommandStep.CHANGELOG_FILE_ARG, CHANGELOG)
                                    .addArgumentValue(UpdateCountCommandStep.COUNT_ARG, count);
                    command.addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database).execute();
                });
    }

    private static List<String> rows(Statement statement, String query) throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery(query)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    cells.add(String.valueOf(result.getObject(i)));
                }
                rows.add(String.join(" ", cells));
            }
        }
        return rows;
    }
}
