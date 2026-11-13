package net.exoego.keycloak.fuhito.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads comma-separated text as RFC 4180 describes it.
 * A cell can be in double quotes. A quoted cell can hold commas, line breaks, and doubled double quotes.
 */
public final class Csv {

    /**
     * One row of cells with the line where it starts.
     *
     * @param line the line number of the first line of the row, counted from 1
     * @param cells the cells
     */
    public record Row(int line, List<String> cells) {
    }

    private Csv() {
    }

    /**
     * Reads all rows. A byte order mark at the start and rows with no content are left out.
     *
     * @param text the text
     * @return the rows
     * @throws FuhitoException when a quoted cell has no end
     */
    public static List<Row> parse(String text) {
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int rowLine = 1;
        int i = text.startsWith("﻿") ? 1 : 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                addRow(rows, rowLine, cells);
                cells = new ArrayList<>();
                line++;
                rowLine = line;
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw FuhitoException.invalid("csv.open_quote", Map.of("line", Integer.toString(rowLine)));
        }
        cells.add(cell.toString());
        addRow(rows, rowLine, cells);
        return rows;
    }

    private static void addRow(List<Row> rows, int line, List<String> cells) {
        if (cells.stream().anyMatch(cell -> !cell.isBlank())) {
            rows.add(new Row(line, List.copyOf(cells)));
        }
    }
}
