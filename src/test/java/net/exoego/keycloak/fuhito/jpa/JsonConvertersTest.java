package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonConvertersTest {

    @Test
    void aMissingMapIsStoredAndReadAsAnEmptyOne() {
        var values = new JsonConverters.ValueMap();

        assertEquals("{}", values.convertToDatabaseColumn(null));
        assertEquals(Map.of(), values.convertToEntityAttribute(null));
    }

    @Test
    void aMapKeepsItsValuesThroughTheColumn() {
        var values = new JsonConverters.ValueMap();
        Map<String, Object> map = Map.of("ward", "east", "beds", 40);

        assertEquals(map, values.convertToEntityAttribute(values.convertToDatabaseColumn(map)));
    }
}
