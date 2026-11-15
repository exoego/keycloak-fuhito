package net.exoego.keycloak.fuhito.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * The place in a list where the next page starts.
 * A cursor holds the values of the last row of a page that the order of the list uses. A caller gets it from one
 * response and gives it back in the next request. It is not a secret. It only has values that the caller already
 * got, and it cannot widen what the caller may read. It is encoded so that nobody builds one by hand, because its
 * form changes when the order of a list changes.
 */
public final class Cursor {

    // No value of a cursor has a line break. They are IDs, dates, and numbers.
    private static final String SEPARATOR = "\n";

    private Cursor() {
    }

    /**
     * Makes a cursor from the values of a row.
     *
     * @param values the values that the order of the list uses, in that order
     * @return the cursor
     */
    public static String encode(String... values) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join(SEPARATOR, values).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the values of a cursor.
     *
     * @param cursor the cursor of a request
     * @param size the number of values that the list expects
     * @return the values
     * @throws FuhitoException when the cursor is not one that this list gave out
     */
    public static List<String> decode(String cursor, int size) {
        try {
            List<String> values = List.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split(SEPARATOR, -1));
            if (values.size() == size) {
                return values;
            }
        } catch (IllegalArgumentException e) {
            throw FuhitoException.invalid("invalid.cursor", Map.of("field", "after")).causedBy(e);
        }
        throw FuhitoException.invalid("invalid.cursor", Map.of("field", "after"));
    }

    /**
     * Rejects a request that gives a cursor and a number of entries to skip. The two do not go together.
     *
     * @param after the cursor of the request, or null
     * @param first the number of entries to skip of the request, or null
     * @throws FuhitoException when the request has both
     */
    public static void requireAlone(String after, Integer first) {
        if (after != null && first != null) {
            throw FuhitoException.invalid("invalid.cursor_with_first", Map.of());
        }
    }
}
