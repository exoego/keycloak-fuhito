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
     * Makes a page from the values of a request.
     *
     * @param first the number of entries to skip, or null for 0. A negative number counts as 0.
     * @param max the largest number of entries to return, or null for 100. It is kept between 0 and 1000.
     * @return the page
     */
    public static Page of(Integer first, Integer max) {
        return new Page(first == null ? 0 : Math.max(0, first),
                max == null ? DEFAULT_MAX : Math.clamp(max, 0, LARGEST_MAX));
    }
}
