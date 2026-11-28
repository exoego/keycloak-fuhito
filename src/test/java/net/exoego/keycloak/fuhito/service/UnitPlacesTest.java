package net.exoego.keycloak.fuhito.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnitPlacesTest {

    private final MemoryRefItemStore refItems = new MemoryRefItemStore();

    UnitPlacesTest() {
        refItems.insert(new RefItem("tokyo", RefKind.WORK_LOCATION, "TYO", ItemNames.of("Tokyo"), 0, Map.of(), true));
        refItems.insert(new RefItem("sales", RefKind.COST_CENTER, "C100", ItemNames.of("Sales"), 0, Map.of(), true));
    }

    @Test
    void givesTheCodesOfTheItemsThatItRead() {
        Function<String, String> code = UnitPlaces.codes(refItems, Stream.of("tokyo", null, "sales", "tokyo"));

        assertEquals("TYO", code.apply("tokyo"));
        assertEquals("C100", code.apply("sales"));
        assertNull(code.apply(null));
    }

    // A list that forgets an ID of its rows fails, and does not show the row without the code.
    @Test
    void failsForAnItemThatItDidNotRead() {
        Function<String, String> code = UnitPlaces.codes(refItems, Stream.of("tokyo"));

        assertThrows(IllegalArgumentException.class, () -> code.apply("sales"));
    }
}
