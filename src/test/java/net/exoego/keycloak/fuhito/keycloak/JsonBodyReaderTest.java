package net.exoego.keycloak.fuhito.keycloak;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.core.MediaType;
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
}
