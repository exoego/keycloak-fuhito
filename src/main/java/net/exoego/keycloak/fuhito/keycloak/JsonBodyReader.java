package net.exoego.keycloak.fuhito.keycloak;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.ClassUtil;
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
 * path of the key. A value of another JSON type than its field, such as {@code "3"} or {@code 3.7} for a whole
 * number, or {@code null} for a field that is never null, fails with 400 and {@code invalid.type}. Keycloak would
 * turn it into the type of the field. Every body of the extension is a record of the extension, and only such a
 * record is read here. So the endpoints of Keycloak read their bodies as before.
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
public class JsonBodyReader implements MessageBodyReader<Record> {

    private static final String DUPLICATE_KEY = "invalid.duplicate_key";
    private static final String UNKNOWN_KEY = "invalid.unknown_key";
    private static final String TYPE = "invalid.type";
    private static final String EXTENSION = "net.exoego.keycloak.fuhito.";

    static final ObjectReader READER = strict(new ObjectMapperResolver().getContext(Record.class))
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

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
                    throw error(DUPLICATE_KEY, Map.of("field", key.get()));
                }
                if (e instanceof UnrecognizedPropertyException unknown) {
                    throw error(UNKNOWN_KEY, Map.of("field", path(unknown.getPath())));
                }
                Optional<Map<String, String>> wrongType = wrongType(e);
                if (wrongType.isPresent()) {
                    throw error(TYPE, wrongType.get());
                }
                // As Keycloak reads a body, so that its error handlers answer as before.
                if (e instanceof MismatchedInputException || e instanceof InvalidDefinitionException) {
                    throw e;
                }
                throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
            }
        }
    }

    private static ErrorResponseException error(String code, Map<String, String> params) {
        return new ErrorResponseException(KeycloakRequestContext.error(Response.Status.BAD_REQUEST, code,
                params, KeycloakRequestContext.locale(KeycloakSessionUtil.getKeycloakSession())));
    }

    // A copy, so that the mapper of Keycloak keeps its settings.
    private static ObjectReader strict(ObjectMapper keycloak) {
        ObjectMapper mapper = keycloak.copy();
        mapper.setConfig(mapper.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        mapper.registerModule(new SimpleModule().setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription description,
                    JsonDeserializer<?> deserializer) {
                Class<?> type = deserializer.handledType();
                return type != null && type.isPrimitive() ? new NotNull(deserializer) : deserializer;
            }
        }));
        return mapper.reader();
    }

    // FAIL_ON_NULL_FOR_PRIMITIVES would also reject a primitive that a body leaves out, such as dryRun of an import.
    private static final class NotNull extends DelegatingDeserializer {

        private static final long serialVersionUID = 1L;

        NotNull(JsonDeserializer<?> deserializer) {
            super(deserializer);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> deserializer) {
            return new NotNull(deserializer);
        }

        @Override
        public Object getNullValue(DeserializationContext ctxt) throws JsonMappingException {
            return ctxt.reportInputMismatch(this, "Cannot map `null` into type %s", handledType().getName());
        }
    }

    /**
     * The field and the JSON type of a value of another JSON type than its field.
     *
     * @param error an error of Jackson
     * @return the params of {@code invalid.type}, or empty for another error
     */
    static Optional<Map<String, String>> wrongType(JsonProcessingException error) {
        if (!(error instanceof MismatchedInputException mismatch) || error instanceof UnrecognizedPropertyException) {
            return Optional.empty();
        }
        String field = mismatch.getPath().isEmpty() ? "body" : path(mismatch.getPath());
        return jsonType(mismatch.getTargetType()).map(type -> Map.of("field", field, "type", type));
    }

    // The type names of the OpenAPI document.
    private static Optional<String> jsonType(Class<?> target) {
        if (target == null) {
            return Optional.empty();
        }
        Class<?> type = target.isPrimitive() ? ClassUtil.wrapperType(target) : target;
        if (type == Boolean.class) {
            return Optional.of("boolean");
        }
        if (type == Integer.class || type == Long.class) {
            return Optional.of("integer");
        }
        if (Number.class.isAssignableFrom(type)) {
            return Optional.of("number");
        }
        if (type == String.class) {
            return Optional.of("string");
        }
        if (Collection.class.isAssignableFrom(type)) {
            return Optional.of("array");
        }
        if (Map.class.isAssignableFrom(type) || type.isRecord()) {
            return Optional.of("object");
        }
        return Optional.empty();
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
