package net.exoego.keycloak.fuhito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Draws the tables that Liquibase created as a Mermaid ER diagram, and compares it with docs/schema.md.
 */
class SchemaDiagramIT {

    private static final String HEADER = """
            # Database schema

            <!-- ./gradlew updateSchemaDiagram writes this file. Do not edit it. -->

            The tables of the extension, as Liquibase creates them in PostgreSQL.

            ## Linked tables

            A line is a foreign key, and its label names the columns of the key.
            A column can point to a row without a foreign key, like `REALM_ID` to a realm of Keycloak.

            """;

    private static final String OTHERS = """

            ## Other tables

            No foreign key starts or ends at these tables.

            """;

    // From left to right, so that the many tables of one level stack up and the page does not get wide.
    private static final String DIAGRAM = """
            ```mermaid
            erDiagram
                direction LR
            """;

    private static final String OWN_TABLES = "t.relnamespace = (select oid from pg_namespace where nspname = current_schema())"
            + " and t.relname like 'fuhito\\_%'";

    private static final String COLUMNS = "select t.relname, c.column_name, c.data_type, c.character_maximum_length,"
            + " c.is_nullable from information_schema.columns c"
            + " join pg_class t on t.relname = c.table_name and t.relkind = 'r'"
            + " where c.table_schema = current_schema() and " + OWN_TABLES
            + " order by t.relname, c.ordinal_position";

    // A partial index or an index of an expression does not make its columns unique.
    private static final String UNIQUE_KEYS = "select t.relname, i.indexrelid::regclass::text,"
            + " case when i.indisprimary then 'PK' else 'UK' end, a.attname"
            + " from pg_index i join pg_class t on t.oid = i.indrelid"
            + " cross join lateral unnest(i.indkey) with ordinality k(attnum, n)"
            + " join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum"
            + " where " + OWN_TABLES + " and i.indisunique and i.indpred is null and i.indexprs is null"
            + " and k.n <= i.indnkeyatts"
            + " order by t.relname, 2, k.n";

    private static final String FOREIGN_KEYS = "select t.relname, c.conname, f.relname, a.attname"
            + " from pg_constraint c join pg_class t on t.oid = c.conrelid join pg_class f on f.oid = c.confrelid"
            + " cross join lateral unnest(c.conkey) with ordinality k(attnum, n)"
            + " join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum"
            + " where " + OWN_TABLES + " and c.contype = 'f'"
            + " order by t.relname, c.conname, k.n";

    private record Column(String name, String type, boolean notNull) {
    }

    private record Key(String table, boolean primary, List<String> columns) {
    }

    private record ForeignKey(String table, String target, List<String> columns) {
    }

    @Test
    void docsHoldTheDiagramOfTheTables() throws Exception {
        Path file = Path.of(System.getProperty("schema.diagram"));
        String diagram = draw();
        if (Boolean.getBoolean("schema.diagram.update")) {
            Files.writeString(file, diagram);
            return;
        }
        assertEquals(Files.readString(file), diagram,
                "docs/schema.md is out of date. Run ./gradlew updateSchemaDiagram and commit the result.");
    }

    private static String draw() throws SQLException {
        Map<String, List<Column>> tables = new LinkedHashMap<>();
        for (List<String> row : rows(COLUMNS)) {
            tables.computeIfAbsent(row.get(0), t -> new ArrayList<>())
                    .add(new Column(row.get(1), type(row.get(2), row.get(3)), "NO".equals(row.get(4))));
        }
        Map<String, Key> keys = new LinkedHashMap<>();
        for (List<String> row : rows(UNIQUE_KEYS)) {
            keys.computeIfAbsent(row.get(1), i -> new Key(row.get(0), "PK".equals(row.get(2)), new ArrayList<>()))
                    .columns().add(row.get(3));
        }
        Map<String, ForeignKey> foreignKeys = new LinkedHashMap<>();
        for (List<String> row : rows(FOREIGN_KEYS)) {
            foreignKeys.computeIfAbsent(row.get(1), c -> new ForeignKey(row.get(0), row.get(2), new ArrayList<>()))
                    .columns().add(row.get(3));
        }

        Set<String> linked = new HashSet<>();
        for (ForeignKey key : foreignKeys.values()) {
            linked.add(key.table());
            linked.add(key.target());
        }

        StringBuilder out = new StringBuilder(HEADER).append(DIAGRAM);
        tables.forEach((table, columns) -> {
            if (linked.contains(table)) {
                entity(out, table, columns, keys.values(), foreignKeys.values());
            }
        });
        for (ForeignKey key : foreignKeys.values()) {
            boolean required = tables.get(key.table()).stream()
                    .filter(c -> key.columns().contains(c.name()))
                    .allMatch(Column::notNull);
            boolean identifying = keys.values().stream()
                    .anyMatch(k -> k.table().equals(key.table()) && k.primary() && k.columns().containsAll(key.columns()));
            boolean single = keys.values().stream()
                    .anyMatch(k -> k.table().equals(key.table()) && key.columns().containsAll(k.columns()));
            out.append("    ").append(upper(key.target()))
                    .append(required ? " ||" : " |o")
                    .append(identifying ? "--" : "..")
                    .append(single ? "o| " : "o{ ")
                    .append(upper(key.table()))
                    .append(" : \"").append(upper(String.join(", ", key.columns()))).append("\"\n");
        }
        out.append("```\n");

        if (!linked.containsAll(tables.keySet())) {
            out.append(OTHERS).append(DIAGRAM);
            tables.forEach((table, columns) -> {
                if (!linked.contains(table)) {
                    entity(out, table, columns, keys.values(), foreignKeys.values());
                }
            });
            out.append("```\n");
        }
        return out.toString();
    }

    private static void entity(StringBuilder out, String table, List<Column> columns, Collection<Key> keys,
            Collection<ForeignKey> foreignKeys) {
        out.append("    ").append(upper(table)).append(" {\n");
        for (Column column : columns) {
            List<String> marks = new ArrayList<>();
            if (keys.stream().anyMatch(k -> k.table().equals(table) && k.primary()
                    && k.columns().contains(column.name()))) {
                marks.add("PK");
            }
            if (foreignKeys.stream().anyMatch(f -> f.table().equals(table) && f.columns().contains(column.name()))) {
                marks.add("FK");
            }
            if (keys.stream().anyMatch(k -> k.table().equals(table) && !k.primary()
                    && k.columns().equals(List.of(column.name())))) {
                marks.add("UK");
            }
            out.append("        ").append(column.type()).append(' ').append(upper(column.name()));
            if (!marks.isEmpty()) {
                out.append(' ').append(String.join(", ", marks));
            }
            out.append('\n');
        }
        out.append("    }\n");
    }

    // A type of Mermaid takes no space and no comma, so the precision and scale of a number are left out.
    private static String type(String dataType, String length) {
        String name = switch (dataType) {
            case "character varying" -> "varchar";
            case "timestamp without time zone" -> "timestamp";
            default -> dataType.replace(' ', '_');
        };
        return length == null ? name : name + "(" + length + ")";
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }

    private static List<List<String>> rows(String sql) throws SQLException {
        try (Connection connection = KeycloakEnvironment.connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            int width = rows.getMetaData().getColumnCount();
            List<List<String>> result = new ArrayList<>();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= width; i++) {
                    row.add(rows.getString(i));
                }
                result.add(row);
            }
            return result;
        }
    }
}
