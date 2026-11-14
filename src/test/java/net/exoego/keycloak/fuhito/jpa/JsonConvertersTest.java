package net.exoego.keycloak.fuhito.jpa;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonConvertersTest {

    @Test
    void aMissingMapIsStoredAndReadAsAnEmptyOne() {
        var texts = new JsonConverters.TextMap();
        var values = new JsonConverters.ValueMap();

        assertEquals("{}", texts.convertToDatabaseColumn(null));
        assertEquals(Map.of(), texts.convertToEntityAttribute(null));
        assertEquals("{}", values.convertToDatabaseColumn(null));
        assertEquals(Map.of(), values.convertToEntityAttribute(null));
    }

    @Test
    void aMapKeepsItsValuesThroughTheColumn() {
        var values = new JsonConverters.ValueMap();
        Map<String, Object> map = Map.of("ward", "east", "beds", 40);

        assertEquals(map, values.convertToEntityAttribute(values.convertToDatabaseColumn(map)));
        assertEquals(Map.of("ja", "東"), new JsonConverters.TextMap().convertToEntityAttribute("{\"ja\":\"東\"}"));
    }
}
