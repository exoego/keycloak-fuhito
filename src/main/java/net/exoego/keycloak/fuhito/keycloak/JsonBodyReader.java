package net.exoego.keycloak.fuhito.keycloak;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.services.util.ObjectMapperResolver;
import org.keycloak.utils.KeycloakSessionUtil;

/**
 * Reads the JSON body of a request to the extension as Keycloak reads a body, but a key that one object gives twice
 * fails with 400 and {@code invalid.duplicate_key}. Keycloak would keep the last value. A key that the body does not
 * have fails with 400 and {@code invalid.unknown_key}. Keycloak would name a Java type and a line in place of the
 * path of the key. Every body of the extension is a record of the extension, and only such a record is read here. So
 * the endpoints of Keycloak read their bodies as before.
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class JsonBodyReader implements MessageBodyReader<Record> {

    private static final String DUPLICATE_KEY = "invalid.duplicate_key";
    private static final String UNKNOWN_KEY = "invalid.unknown_key";
    private static final String EXTENSION = "net.exoego.keycloak.fuhito.";

    private static final ObjectReader READER = new ObjectMapperResolver().getContext(Record.class).reader()
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * Creates the reader.
     */
    public JsonBodyReader() {
    }

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type.getName().startsWith(EXTENSION);
    }

    @Override
    public Record readFrom(Class<Record> type, Type genericType, Annotation[] annotations, MediaType mediaType,
            MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
        ObjectReader reader = READER.forType(genericType);
        try (JsonParser parser = reader.createParser(entityStream)) {
            try {
                return parser.nextToken() == null ? null : reader.readValue(parser);
            } catch (JsonProcessingException e) {
                Optional<String> key = duplicateKey(parser, e);
                if (key.isPresent()) {
                    throw error(DUPLICATE_KEY, key.get());
                }
                if (e instanceof UnrecognizedPropertyException unknown) {
                    throw error(UNKNOWN_KEY, path(unknown.getPath()));
                }
                // As Keycloak reads a body, so that its error handlers answer as before.
                if (e instanceof MismatchedInputException || e instanceof InvalidDefinitionException) {
                    throw e;
                }
                throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
            }
        }
    }

    private static ErrorResponseException error(String code, String field) {
        return new ErrorResponseException(KeycloakRequestContext.error(Response.Status.BAD_REQUEST, code,
                Map.of("field", field), KeycloakRequestContext.locale(KeycloakSessionUtil.getKeycloakSession())));
    }

    // The path of the key that the parser read twice in one object, such as orgUnits[1].names.ja.
    private static Optional<String> duplicateKey(JsonParser parser, Throwable error) {
        JsonStreamContext context = parser.getParsingContext();
        // Jackson tells this error apart only by its text.
        String duplicate = "Duplicate field '" + context.getCurrentName() + "'";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonParseException parse && duplicate.equals(parse.getOriginalMessage())) {
                return Optional.of(path(context));
            }
        }
        return Optional.empty();
    }

    private static String path(JsonStreamContext context) {
        String path = "";
        for (JsonStreamContext part = context; !part.inRoot(); part = part.getParent()) {
            String step = part.inArray() ? "[" + part.getCurrentIndex() + "]" : part.getCurrentName();
            path = path.isEmpty() || path.startsWith("[") ? step + path : step + "." + path;
        }
        return path;
    }

    private static String path(List<JsonMappingException.Reference> references) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() == null) {
                path.append('[').append(reference.getIndex()).append(']');
            } else {
                path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
            }
        }
        return path.toString();
    }
}
