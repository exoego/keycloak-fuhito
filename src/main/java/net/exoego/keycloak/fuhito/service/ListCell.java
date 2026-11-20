package net.exoego.keycloak.fuhito.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * A CSV cell that holds a list, such as {@code 100\|2024|200\|2024}. A vertical bar separates the items. In an item,
 * {@code \|} is a vertical bar and {@code \\} is a backslash.
 */
final class ListCell {

    private ListCell() {
    }

    /**
     * Reads the items of a cell. Spaces around an item are removed.
     *
     * @param column the column of the cell, which an error names
     * @param cell the text of the cell
     * @return null for a blank cell, which keeps the stored list, no items for {@code #CLEAR}, or the items
     * @throws FuhitoException when an item is empty, or when a backslash comes before a character other than a
     *     vertical bar or a backslash
     */
    static List<String> read(String column, String cell) {
        String text = cell.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.equals(CsvImportService.CLEAR)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '|') {
                items.add(item(column, text, item));
                item.setLength(0);
            } else if (c != '\\') {
                item.append(c);
            } else if (i + 1 < text.length() && (text.charAt(i + 1) == '|' || text.charAt(i + 1) == '\\')) {
                item.append(text.charAt(i + 1));
                i++;
            } else {
                throw FuhitoException.invalid("csv.bad_escape", Map.of("field", column, "value", text));
            }
        }
        items.add(item(column, text, item));
        return List.copyOf(items);
    }

    /**
     * Writes items as one cell that {@link #read} reads back.
     *
     * @param items the items
     * @return the cell, blank for no items
     */
    static String write(List<?> items) {
        return items.stream()
                .map(item -> String.valueOf(item).replace("\\", "\\\\").replace("|", "\\|"))
                .collect(Collectors.joining("|"));
    }

    private static String item(String column, String text, StringBuilder item) {
        String stripped = item.toString().strip();
        if (stripped.isEmpty()) {
            throw FuhitoException.invalid("csv.empty_item", Map.of("field", column, "value", text));
        }
        return stripped;
    }
}
