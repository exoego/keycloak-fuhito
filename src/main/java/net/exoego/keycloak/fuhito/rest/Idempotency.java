package net.exoego.keycloak.fuhito.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Unchecked;
import net.exoego.keycloak.fuhito.store.IdempotencyStore;

/**
 * The first response to a request with an idempotency key, which a repeated request gets again.
 */
final class Idempotency {

    static final String HEADER = "Idempotency-Key";

    private static final Duration LIFETIME = Duration.ofHours(24);
    private static final int MAX_KEY_LENGTH = 255;
    // A response leaves out every field that has no value, so the stored copy does too.
    private static final ObjectMapper JSON = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    // The same request gives the same text, whatever the order of the keys in a map.
    private static final ObjectMapper SORTED = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private Idempotency() {
    }

    @SuppressWarnings("unchecked")
    static <T> T once(RequestContext context, String key, Object body, Class<T> type, Supplier<T> operation) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw FuhitoException.invalid("idempotency.bad_key", Map.of());
        }
        String path = context.requestPath();
        // The query, for example the change order, and the caller are part of the request, as the body is.
        String hash = hash(new Fingerprint(context.callerUserId(), context.requestQuery(), body));
        Instant now = Instant.now();
        Instant since = now.minus(LIFETIME);
        IdempotencyStore store = context.idempotency();
        Optional<IdempotencyStore.Stored> stored = store.find(key, path, since);
        if (stored.isPresent()) {
            IdempotencyStore.Stored first = stored.get();
            if (!first.requestHash().equals(hash)) {
                throw FuhitoException.unprocessable("idempotency.key_reused", Map.of());
            }
            if (first.status() == 0) {
                throw FuhitoException.conflict("idempotency.in_progress", Map.of());
            }
            return replay(first, type);
        }
        String id = store.claim(key, path, hash, now, since);
        T result = operation.get();
        if (result instanceof Response response) {
            store.complete(id, response.getStatus(), json(response.getEntity()));
        } else {
            store.complete(id, Response.Status.OK.getStatusCode(), json(result));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T replay(IdempotencyStore.Stored first, Class<T> type) {
        if (type == Response.class) {
            Response.ResponseBuilder response = Response.status(first.status());
            if (first.body() != null) {
                response.entity(first.body()).type(MediaType.APPLICATION_JSON_TYPE);
            }
            return (T) response.build();
        }
        // A mask or an erase cleared a body that named a person. The request still writes nothing again.
        if (first.body() == null) {
            return null;
        }
        return Unchecked.io(() -> JSON.readValue(first.body(), type));
    }

    private record Fingerprint(String caller, String query, Object body) {
    }

    private static String hash(Fingerprint fingerprint) {
        byte[] text = Unchecked.io(() -> SORTED.writeValueAsString(fingerprint)).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text);
            // The stored hashes of earlier requests have the quotes too.
            return "\"" + HexFormat.of().formatHex(Arrays.copyOf(hash, 16)) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256.", e);
        }
    }

    private static String json(Object entity) {
        return entity == null ? null : Unchecked.io(() -> JSON.writeValueAsString(entity));
    }
}
