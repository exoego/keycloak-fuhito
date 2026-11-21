package net.exoego.keycloak.fuhito.service;

/**
 * The part of a list that a read returns.
 *
 * @param first the number of entries to skip
 * @param max the largest number of entries to return
 */
public record Page(int first, int max) {

    /**
     * The number of entries that a read returns when the request names none.
     */
    public static final int DEFAULT_MAX = 100;

    /**
     * The largest number of entries that one read returns.
     */
    public static final int LARGEST_MAX = 1000;

    /**
     * Makes a page from the values of a request. The check of the query has rejected a number outside the limits.
     *
     * @param first the number of entries to skip, from 0, or null for 0
     * @param max the largest number of entries to return, from 0 to 1000, or null for 100
     * @return the page
     * @throws IllegalArgumentException when a number is outside its limits
     */
    public static Page of(Integer first, Integer max) {
        return new Page(first == null ? 0 : first, max == null ? DEFAULT_MAX : max);
    }

    // A page outside the limits is a bug. The extension never fixes a number that a caller sent.
    public Page {
        if (first < 0 || max < 0 || max > LARGEST_MAX) {
            throw new IllegalArgumentException("first " + first + " max " + max);
        }
    }
}
