package net.exoego.keycloak.fuhito.keycloak;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
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

    // A key twice, a key that the body does not have, and a body that is no JSON need the server of Keycloak to
    // answer. The integration tests have them.
    @Test
    void passesTheErrorsOfAValueToKeycloakAsBefore() {
        assertThrows(MismatchedInputException.class, () -> read("{\"rows\": 1}"));
    }
}
