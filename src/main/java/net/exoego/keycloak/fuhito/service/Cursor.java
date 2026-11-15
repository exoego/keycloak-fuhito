package net.exoego.keycloak.fuhito.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * The place in a list where the next page starts.
 * A cursor is the name of its format, and then the values of the last row of a page that the order of the list
 * uses. For example {@code people.v1__<person ID>}. A caller gets it from one response and gives it back in the
 * next request.
 * It is plain text and not a secret. It only has values that the caller already got, and it cannot widen what the
 * caller may read. The name says which list it belongs to, so a cursor of another list is rejected. When the
 * order of a list changes, its format gets a new version, and a cursor of the old version is rejected.
 */
public final class Cursor {

    /**
     * The form of the cursors of one list.
     *
     * @param name the name with its version, for example {@code people.v1}
     * @param size the number of values
     */
    public record Format(String name, int size) {
    }

    /**
     * The people, ordered by ID. The value is the ID of the person.
     */
    public static final Format PEOPLE = new Format("people.v1", 1);

    /**
     * The members of an org unit. The values are the ID of the org unit, the display order, the first date of
     * the row, and the ID of the assignment.
     */
    public static final Format MEMBERS = new Format("members.v1", 4);

    /**
     * The employments, ordered by ID. The value is the ID of the employment.
     */
    public static final Format EMPLOYMENTS = new Format("employments.v1", 1);

    /**
     * The assignments of a date across the realm, ordered by the ID of the assignment. The value is that ID.
     */
    public static final Format ASSIGNMENTS = new Format("assignments.v1", 1);

    /**
     * The qualifications, ordered by ID. The value is the ID of the qualification.
     */
    public static final Format QUALIFICATIONS = new Format("qualifications.v1", 1);

    // No value has this text. The values are IDs that the extension makes, numbers, and dates.
    private static final String SEPARATOR = "__";

    private Cursor() {
    }

    /**
     * Makes a cursor from the values of a row.
     *
     * @param format the form of the cursors of the list
     * @param values the values that the order of the list uses, in that order
     * @return the cursor
     */
    public static String encode(Format format, String... values) {
        return format.name() + SEPARATOR + String.join(SEPARATOR, values);
    }

    /**
     * Reads the values of a cursor.
     *
     * @param format the form of the cursors of the list
     * @param cursor the cursor of a request
     * @return the values
     * @throws FuhitoException when the cursor is not of this format
     */
    public static List<String> decode(Format format, String cursor) {
        List<String> parts = Arrays.asList(cursor.split(SEPARATOR, -1));
        if (parts.size() != format.size() + 1 || !parts.getFirst().equals(format.name())) {
            throw FuhitoException.invalid("invalid.cursor", Map.of("field", "after", "format", format.name()));
        }
        return parts.subList(1, parts.size());
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
