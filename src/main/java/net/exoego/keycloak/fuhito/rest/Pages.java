package net.exoego.keycloak.fuhito.rest;

import java.util.List;
import java.util.function.Function;

import jakarta.ws.rs.core.Response;

/**
 * Builds the response of a list that can go on from a cursor.
 */
final class Pages {

    /** The header with the number of entries on all pages. */
    static final String TOTAL = "X-Total-Count";

    private Pages() {
    }

    /**
     * Returns the entries, with a link to the next page when the page is full.
     * A full page may be the last one. The next page is then empty.
     *
     * @param <T> the type of the entries
     * @param context the request
     * @param entries the entries of the page
     * @param max the largest number of entries that the page can have
     * @param cursorOf makes the cursor that goes on after an entry
     * @return the response
     */
    static <T> Response of(RequestContext context, List<T> entries, int max, Function<T, String> cursorOf) {
        return of(context, entries, max, cursorOf, null);
    }

    /**
     * Returns the entries, with a link to the next page when the page is full, and the total when it is known.
     *
     * @param <T> the type of the entries
     * @param context the request
     * @param entries the entries of the page
     * @param max the largest number of entries that the page can have
     * @param cursorOf makes the cursor that goes on after an entry
     * @param total the number of entries on all pages, or null when the caller did not ask for it
     * @return the response
     */
    static <T> Response of(RequestContext context, List<T> entries, int max, Function<T, String> cursorOf,
            Long total) {
        Response.ResponseBuilder response = Response.ok(entries);
        if (total != null) {
            response.header(TOTAL, total);
        }
        if (max > 0 && entries.size() == max) {
            response.header("Link", "<" + context.nextPage(cursorOf.apply(entries.getLast())) + ">; rel=\"next\"");
        }
        return response.build();
    }
}
