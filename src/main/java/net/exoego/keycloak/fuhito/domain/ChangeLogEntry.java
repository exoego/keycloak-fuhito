package net.exoego.keycloak.fuhito.domain;

import java.time.Instant;

/**
 * One successful write. Entries are only added, never changed.
 * The valid period of a row says when a fact is true. An entry says when the system learned it, and from whom.
 *
 * @param id the ID
 * @param sequence the position of the entry in the change log of its realm, counted from 1. The store sets it.
 * @param recordedAt when the write happened
 * @param actorUserId the ID of the user who did the write
 * @param resourceType the type of the changed resource
 * @param resourceId the ID of the changed resource
 * @param operation {@code CREATE}, {@code UPDATE}, or {@code DELETE}
 * @param resourcePath the path of the request below the realm
 * @param changeOrderId the ID of the change order that the write belongs to, or null
 */
public record ChangeLogEntry(String id, long sequence, Instant recordedAt, String actorUserId,
        ResourceType resourceType, String resourceId, String operation, String resourcePath, String changeOrderId) {
}
