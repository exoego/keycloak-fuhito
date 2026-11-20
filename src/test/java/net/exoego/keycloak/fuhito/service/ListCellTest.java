package net.exoego.keycloak.fuhito.service;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListCellTest {

    private static FuhitoException failure(String cell) {
        return assertThrows(FuhitoException.class, () -> ListCell.read("successorExternalIds", cell));
    }

    @Test
    void readsTheItemsBetweenVerticalBars() {
        assertEquals(List.of("W1", "W2", "W3"), ListCell.read("successorIds", "W1|W2|W3"));
        assertEquals(List.of("W1"), ListCell.read("successorIds", "W1"));
        assertEquals(List.of("#CLEAR", "W1"), ListCell.read("successorIds", "#CLEAR|W1"));
    }

    @Test
    void removesTheSpacesAroundAnItem() {
        assertEquals(List.of("W1", "night shift"), ListCell.read("successorIds", "  W1 |\tnight shift  "));
        assertEquals(List.of("a |"), ListCell.read("successorIds", " a \\| "));
    }

    @Test
    void readsAnEscapedVerticalBarAndBackslashInAnItem() {
        assertEquals(List.of("100|2024", "200|2024"), ListCell.read("successorExternalIds", "100\\|2024|200\\|2024"));
        assertEquals(List.of("C:\\ward", "b"), ListCell.read("successorExternalIds", "C:\\\\ward|b"));
        assertEquals(List.of("a\\", "b"), ListCell.read("successorExternalIds", "a\\\\|b"));
        assertEquals(List.of("a\\|b"), ListCell.read("successorExternalIds", "a\\\\\\|b"));
    }

    @Test
    void aBackslashBeforeAnyOtherCharacterFails() {
        FuhitoException e = failure("W1|C:\\ward");

        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        assertEquals("csv.bad_escape", e.code());
        assertEquals(Map.of("field", "successorExternalIds", "value", "W1|C:\\ward"), e.params());
        assertEquals("csv.bad_escape", failure("W1\\").code());
        assertEquals("csv.bad_escape", failure("W1\\ |W2").code());
    }

    @Test
    void anEmptyItemFails() {
        FuhitoException e = failure("W1||W2");

        assertEquals(FuhitoException.Reason.INVALID, e.reason());
        assertEquals("csv.empty_item", e.code());
        assertEquals(Map.of("field", "successorExternalIds", "value", "W1||W2"), e.params());
        assertEquals("csv.empty_item", failure("|W1").code());
        assertEquals("csv.empty_item", failure("W1|").code());
        assertEquals("csv.empty_item", failure("W1| |W2").code());
        assertEquals("csv.empty_item", failure("|").code());
    }

    @Test
    void aBlankCellKeepsTheStoredListAndClearRemovesIt() {
        assertNull(ListCell.read("successorIds", ""));
        assertNull(ListCell.read("successorIds", "  "));
        assertEquals(List.of(), ListCell.read("successorIds", "#CLEAR"));
        assertEquals(List.of(), ListCell.read("successorIds", " #CLEAR "));
    }

    @Test
    void writesItemsThatReadBackTheSame() {
        List<String> items = List.of("100|2024", "C:\\ward", "a\\|b", "plain");

        String cell = ListCell.write(items);

        assertEquals("100\\|2024|C:\\\\ward|a\\\\\\|b|plain", cell);
        assertEquals(items, ListCell.read("attributes.tags", cell));
        assertEquals("W1", ListCell.write(List.of("W1")));
        assertEquals("30|2.5|true", ListCell.write(List.of(30, 2.5, true)));
        assertEquals("", ListCell.write(List.of()));
    }
}
