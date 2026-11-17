package net.exoego.keycloak.fuhito.store;

import java.time.Instant;
import java.util.Optional;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * Storage of the responses to requests with an idempotency key, in one realm.
 */
public interface IdempotencyStore {

    /**
     * The response to the first request with a key.
     *
     * @param id the ID of the row
     * @param requestHash the hash of the body of the first request
     * @param status the status of the response, or 0 while the first request is still running
     * @param body the body of the response as JSON, or null
     */
    record Stored(String id, String requestHash, int status, String body) {
    }

    /**
     * Finds the response to a key that was used since a time.
     *
     * @param key the key
     * @param path the path of the request
     * @param since the oldest time that still counts
     * @return the response, or empty when the key is new
     */
    Optional<Stored> find(String key, String path, Instant since);

    /**
     * Takes a key for a request that is about to run, and removes the rows that are too old to count.
     *
     * @param key the key
     * @param path the path of the request
     * @param requestHash the hash of the body of the request
     * @param now the time of the request
     * @param since the oldest time that still counts
     * @return the ID of the new row
     * @throws FuhitoException when another request took the key at the same time
     */
    String claim(String key, String path, String requestHash, Instant now, Instant since);

    /**
     * Stores the response to a request that took a key.
     *
     * @param id the ID of the row
     * @param status the status of the response
     * @param body the body of the response as JSON, or null
     */
    void complete(String id, int status, String body);
}
