package net.exoego.keycloak.fuhito;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.ResultSet;
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
                    insert into fuhito_idempotency_key (id, realm_id, key_value, request_path, request_hash, status,
                    body, created_at) values ('key-1', 'realm-1', 'key', '/fuhito/people', 'hash', 201,
                    '{"name":"山田"}', '2026-04-01 00:00:00')""");

            update(jar, database, null);

            // An item keeps its names for every date.
            assertEquals(List.of("item-1 item-1 null null 主任 {\"en\":\"Chief\"}",
                    "item-2 item-2 null null 病棟 {}"), rows(statement, """
                    select id, ref_item_id, valid_from, valid_to, name, names_i18n
                    from fuhito_ref_item_name order by id"""));
            assertEquals(List.of("0"), rows(statement, """
                    select count(*) from information_schema.columns
                    where table_name = 'fuhito_ref_item' and column_name in ('name', 'names_i18n')"""));
            // An entry of the change log keeps all but the state.
            assertEquals(List.of("entry-1 1 FUHITO_PERSON person-1 CREATE"), rows(statement, """
                    select id, sequence_number, resource_type, resource_id, operation from fuhito_change_log"""));
            assertEquals(List.of("0"), rows(statement, """
                    select count(*) from information_schema.columns
                    where table_name = 'fuhito_change_log' and column_name = 'representation'"""));
            // No table keeps the responses to requests that were sent again.
            assertEquals(List.of("0"), rows(statement, """
                    select count(*) from information_schema.tables where table_name = 'fuhito_idempotency_key'"""));
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
