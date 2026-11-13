package net.exoego.keycloak.fuhito.jpa;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores maps as JSON text, so that the columns work on every database.
 */
public final class JsonConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonConverters() {
    }

    private static String write(Map<String, ?> map) {
        try {
            return MAPPER.writeValueAsString(map == null ? Map.of() : map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static <T> Map<String, T> read(String json, TypeReference<LinkedHashMap<String, T>> type) {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Converts a map of text, such as names by language tag.
     */
    @Converter
    public static class TextMap implements AttributeConverter<Map<String, String>, String> {

        /**
         * Creates the converter.
         */
        public TextMap() {
        }

        @Override
        public String convertToDatabaseColumn(Map<String, String> attribute) {
            return write(attribute);
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<>() { });
        }
    }

    /**
     * Converts a map of free values.
     */
    @Converter
    public static class ValueMap implements AttributeConverter<Map<String, Object>, String> {

        /**
         * Creates the converter.
         */
        public ValueMap() {
        }

        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            return write(attribute);
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<>() { });
        }
    }
}
