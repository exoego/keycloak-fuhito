package net.exoego.keycloak.fuhito.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.domain.Unchecked;

/**
 * The ETag of a resource, which is a hash of its whole stored state. A write with {@code If-Match} goes on only
 * when the resource still has that state.
 */
final class ETags {

    // The same state gives the same text, whatever the order of the keys in a map.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ETags() {
    }

    static String of(Object representation) {
        byte[] text = Unchecked.io(() -> JSON.writeValueAsString(representation)).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text);
            return "\"" + HexFormat.of().formatHex(Arrays.copyOf(hash, 16)) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256.", e);
        }
    }

    static void send(RequestContext context, Object representation) {
        context.responseHeader("ETag", of(representation));
    }

    static void require(RequestContext context, Supplier<Object> state) {
        String wanted = context.header("If-Match");
        if (wanted == null) {
            return;
        }
        // Another write with the same ETag waits here, and then sees the state that the first one wrote.
        context.lockTarget();
        String current = of(state.get());
        boolean matches = Arrays.stream(wanted.split(","))
                .map(String::strip)
                .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
                .anyMatch(tag -> tag.equals("*") || tag.equals(current));
        if (!matches) {
            throw FuhitoException.precondition("etag.mismatch", Map.of("etag", current));
        }
    }
}
