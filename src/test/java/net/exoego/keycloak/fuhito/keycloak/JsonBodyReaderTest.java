package net.exoego.keycloak.fuhito.keycloak;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.InputCoercionException;
import jakarta.ws.rs.core.MediaType;
import net.exoego.keycloak.fuhito.service.ImportDocument;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBodyReaderTest {

    record Row(String id, Map<String, Map<String, String>> names) {
    }

    record Body(String primaryLanguage, Map<String, Map<String, String>> names, List<Row> rows) {
    }

    record Values(String code, Integer displayOrder, long version, boolean head, Boolean active,
            BigDecimal workPercent, List<String> ids, Map<String, Row> rows, Map<String, Object> attributes) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Record read(String json) throws Exception {
        return new JsonBodyReader().readFrom((Class) Body.class, Body.class, new Annotation[0],
                MediaType.APPLICATION_JSON_TYPE, null, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsOnlyTheRecordsOfTheExtension() {
        var reader = new JsonBodyReader();

        assertTrue(reader.isReadable(Body.class, Body.class, null, MediaType.APPLICATION_JSON_TYPE));
        assertFalse(reader.isReadable(RealmRepresentation.class, RealmRepresentation.class, null,
                MediaType.APPLICATION_JSON_TYPE));
    }

    @Test
    void readsABodyWithEachKeyOnce() throws Exception {
        assertEquals(new Body("ja", Map.of("ja", Map.of("name", "甲")), List.of(new Row("r", null))),
                read("{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"甲\"}}, "
                        + "\"rows\": [{\"id\": \"r\"}]}"));
        assertNull(read(""));
        assertNull(read(" \n"));
        assertNull(read("null"));
    }

    private static Values values(String json) throws Exception {
        return JsonBodyReader.READER.forType(Values.class).readValue(json);
    }

    // The error itself needs the server of Keycloak to answer, as for a key twice or a key that the body does not
    // have. The integration tests have it.
    private static Map<String, String> wrongType(String json) {
        JsonProcessingException error = assertThrows(JsonProcessingException.class, () -> values(json));
        return JsonBodyReader.wrongType(error).orElseThrow(() -> new AssertionError(error));
    }

    private static Map<String, String> type(String field, String type) {
        return Map.of("field", field, "type", type);
    }

    @Test
    void aValueOfAnotherJsonTypeFails() {
        assertEquals(type("displayOrder", "integer"), wrongType("{\"displayOrder\": 3.7}"));
        assertEquals(type("displayOrder", "integer"), wrongType("{\"displayOrder\": 3.0}"));
        assertEquals(type("displayOrder", "integer"), wrongType("{\"displayOrder\": \"3\"}"));
        assertEquals(type("displayOrder", "integer"), wrongType("{\"displayOrder\": \"\"}"));
        assertEquals(type("displayOrder", "integer"), wrongType("{\"displayOrder\": true}"));
        assertEquals(type("version", "integer"), wrongType("{\"version\": 2.5}"));
        assertEquals(type("version", "integer"), wrongType("{\"version\": null}"));
        assertEquals(type("head", "boolean"), wrongType("{\"head\": 1}"));
        assertEquals(type("head", "boolean"), wrongType("{\"head\": null}"));
        assertEquals(type("active", "boolean"), wrongType("{\"active\": \"false\"}"));
        assertEquals(type("active", "boolean"), wrongType("{\"active\": 0}"));
        assertEquals(type("code", "string"), wrongType("{\"code\": 123}"));
        assertEquals(type("code", "string"), wrongType("{\"code\": 1.5}"));
        assertEquals(type("code", "string"), wrongType("{\"code\": true}"));
        assertEquals(type("code", "string"), wrongType("{\"code\": [\"a\"]}"));
        assertEquals(type("code", "string"), wrongType("{\"code\": {}}"));
        assertEquals(type("workPercent", "number"), wrongType("{\"workPercent\": \"50\"}"));
        assertEquals(type("workPercent", "number"), wrongType("{\"workPercent\": true}"));
        assertEquals(type("ids", "array"), wrongType("{\"ids\": \"a\"}"));
        assertEquals(type("ids[1]", "string"), wrongType("{\"ids\": [\"a\", 2]}"));
        assertEquals(type("rows", "object"), wrongType("{\"rows\": []}"));
        assertEquals(type("rows.r", "object"), wrongType("{\"rows\": {\"r\": \"x\"}}"));
        assertEquals(type("rows.r.id", "string"), wrongType("{\"rows\": {\"r\": {\"id\": 1}}}"));
        assertEquals(type("attributes", "object"), wrongType("{\"attributes\": \"\"}"));
        assertEquals(type("body", "object"), wrongType("[]"));
    }

    private static Map<String, String> outOfRange(Class<?> type, String json) {
        JsonProcessingException error = assertThrows(JsonProcessingException.class,
                () -> JsonBodyReader.READER.forType(type).readValue(json));
        return JsonBodyReader.outOfRange(error).orElseThrow(() -> new AssertionError(error));
    }

    private static Map<String, String> range(String field, long min, long max) {
        return Map.of("field", field, "min", Long.toString(min), "max", Long.toString(max));
    }

    record Numbers(Integer displayOrder, int primitive, Long version, long count, List<Integer> orders,
            Map<String, Numbers> rows, byte small) {
    }

    @Test
    void aWholeNumberOutsideTheRangeOfItsFieldFails() {
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        assertEquals(range("displayOrder", min, max), outOfRange(Numbers.class, "{\"displayOrder\": 2147483648}"));
        assertEquals(range("displayOrder", min, max), outOfRange(Numbers.class, "{\"displayOrder\": -2147483649}"));
        assertEquals(range("displayOrder", min, max),
                outOfRange(Numbers.class, "{\"displayOrder\": 99999999999999999999999}"));
        assertEquals(range("primitive", min, max), outOfRange(Numbers.class, "{\"primitive\": 2147483648}"));
        assertEquals(range("orders[1]", min, max), outOfRange(Numbers.class, "{\"orders\": [1, 2147483648]}"));
        assertEquals(range("rows.r.displayOrder", min, max),
                outOfRange(Numbers.class, "{\"rows\": {\"r\": {\"displayOrder\": -2147483649}}}"));
        assertEquals(range("version", Long.MIN_VALUE, Long.MAX_VALUE),
                outOfRange(Numbers.class, "{\"version\": 9223372036854775808}"));
        assertEquals(range("count", Long.MIN_VALUE, Long.MAX_VALUE),
                outOfRange(Numbers.class, "{\"count\": -9223372036854775809}"));
        assertEquals(range("rows[0].displayOrder", min, max), outOfRange(Rows.class,
                "{\"rows\": [{\"displayOrder\": 2147483648}]}"));
    }

    record Rows(List<Numbers> rows) {
    }

    @Test
    void otherErrorsAreNotOutOfRange() {
        // No body has a byte. Jackson fails it with the same error, and the reader passes it to Keycloak.
        JsonProcessingException small = assertThrows(JsonProcessingException.class,
                () -> JsonBodyReader.READER.forType(Numbers.class).readValue("{\"small\": 300}"));
        assertTrue(small.getCause() instanceof InputCoercionException, small.toString());
        assertTrue(JsonBodyReader.outOfRange(small).isEmpty(), small.toString());
        JsonProcessingException type = assertThrows(JsonProcessingException.class,
                () -> JsonBodyReader.READER.forType(Numbers.class).readValue("{\"version\": \"1\"}"));
        assertTrue(JsonBodyReader.outOfRange(type).isEmpty(), type.toString());
        JsonProcessingException syntax = assertThrows(JsonProcessingException.class,
                () -> JsonBodyReader.READER.forType(Numbers.class).readValue("{\"version\": "));
        assertTrue(JsonBodyReader.outOfRange(syntax).isEmpty(), syntax.toString());
    }

    @Test
    void aWholeNumberAtTheLimitsOfItsFieldIsRead() throws Exception {
        assertEquals(new Numbers(Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, null, null,
                (byte) 0), JsonBodyReader.READER.forType(Numbers.class).readValue("{\"displayOrder\": 2147483647, "
                + "\"primitive\": -2147483648, \"version\": -9223372036854775808, \"count\": 9223372036854775807}"));
    }

    @Test
    void aValueOfTheJsonTypeOfItsFieldIsRead() throws Exception {
        assertEquals(new Values("c", 3, 2, true, false, new BigDecimal("80.0"), List.of("a"),
                        Map.of("r", new Row("1", null)), Map.of("n", "3", "b", true)),
                values("{\"code\": \"c\", \"displayOrder\": 3, \"version\": 2, \"head\": true, \"active\": false, "
                        + "\"workPercent\": 80.0, \"ids\": [\"a\"], \"rows\": {\"r\": {\"id\": \"1\"}}, "
                        + "\"attributes\": {\"n\": \"3\", \"b\": true}}"));
        assertEquals(new BigDecimal("50"), values("{\"workPercent\": 50}").workPercent());
        assertEquals(new BigDecimal("80.00"), values("{\"workPercent\": 80.00}").workPercent());
        assertEquals(new Values(null, null, 0, false, null, null, null, null, null), values("{}"));
        assertEquals(new Values(null, null, 0, false, null, null, null, null, null),
                values("{\"code\": null, \"displayOrder\": null, \"active\": null, \"ids\": null}"));
    }

    // A decimal that a double gives back the same is a Double, as Keycloak reads it. Another one stays as written.
    @Test
    void aNumberOfAValueWithNoTypeIsReadAsWritten() throws Exception {
        Map<String, Object> attributes = values("{\"attributes\": {\"a\": 0.1, \"b\": 50.000000000000001, "
                + "\"c\": 1e400, \"d\": -1e-400, \"e\": 7, \"f\": 9007199254740993, \"g\": [2.5, 1e400], "
                + "\"h\": 1.7976931348623157e308}}").attributes();

        assertEquals(0.1, attributes.get("a"));
        assertEquals(new BigDecimal("50.000000000000001"), attributes.get("b"));
        assertEquals(new BigDecimal("1e400"), attributes.get("c"));
        assertEquals(new BigDecimal("-1e-400"), attributes.get("d"));
        assertEquals(7, attributes.get("e"));
        assertEquals(9007199254740993L, attributes.get("f"));
        assertEquals(List.of(2.5, new BigDecimal("1e400")), attributes.get("g"));
        assertEquals(Double.MAX_VALUE, attributes.get("h"));
    }

    // A row of an import is read as a tree first, which holds a decimal as written too.
    @Test
    void aRowOfAnImportReadsADecimalAsWritten() throws Exception {
        ImportDocument document = JsonBodyReader.READER.forType(ImportDocument.class).readValue("""
                {"orgUnits": [{"externalId": "W", "attributes": {"beds": 0.1, "floor": 1e400}}],
                 "employments": [{"externalId": "E1", "workPercent": 50.000000000000001}],
                 "assignments": [{"externalId": "A1", "allocationPercent": 50.10}]}
                """);

        assertEquals(Map.of("beds", 0.1, "floor", new BigDecimal("1e400")),
                document.orgUnits().getFirst().attributes());
        assertEquals(new BigDecimal("50.000000000000001"), document.employments().getFirst().workPercent());
        assertEquals("50.10", document.assignments().getFirst().allocationPercent().toString());
    }
}
