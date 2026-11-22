package net.exoego.keycloak.fuhito.domain;

import java.time.Instant;

/**
 * One entry of the change log. Entries are only added, never changed.
 * The valid period of a row says when a fact is true. An entry says when the system learned it, and from whom.
 *
 * @param id the ID
 * @param sequence the position of the entry in the change log of its realm, counted from 1. The store sets it.
 * @param recordedAt when the write happened
 * @param kind a write, or a propagated change
 * @param actorUserId the ID of the user who did the write, or null when no user did it
 * @param resourceType the type of the changed resource
 * @param resourceId the ID of the changed resource
 * @param operation {@code CREATE}, {@code UPDATE}, or {@code DELETE}
 * @param resourcePath the path of the request below the realm. For a propagated change, the path of the resource.
 * @param changeOrderId the ID of the change order that the write belongs to, or null
 * @param cause what caused a propagated change, or null for a write
 * @param causeSequence the sequence number of the entry of the write that caused a propagated change, or null
 */
public record ChangeLogEntry(String id, long sequence, Instant recordedAt, Kind kind, String actorUserId,
        ResourceType resourceType, String resourceId, String operation, String resourcePath, String changeOrderId,
        Cause cause, Long causeSequence) {

    /**
     * The kind of an entry.
     */
    public enum Kind {
        /** A write to the resource. */
        WRITE,
        /** A change of the read of the resource that a write to other resources made. Nobody wrote the resource. */
        PROPAGATED
    }

    /**
     * What caused a propagated change.
     */
    public enum Cause {
        /** A write to the API, whose entry the propagated change names. */
        WRITE,
        /** The removal of an organization in Keycloak. */
        KEYCLOAK_ORGANIZATION_REMOVED,
        /** A change of the first name or the last name of a user in Keycloak. */
        KEYCLOAK_USER_NAME_CHANGED,
        /** The removal of a user in Keycloak. */
        KEYCLOAK_USER_REMOVED
    }

    /**
     * Makes the entry of a write.
     *
     * @param id the ID
     * @param recordedAt when the write happened
     * @param actorUserId the ID of the user who did the write, or null when no user did it
     * @param resourceType the type of the written resource
     * @param resourceId the ID of the written resource
     * @param operation {@code CREATE}, {@code UPDATE}, or {@code DELETE}
     * @param resourcePath the path of the request below the realm
     * @param changeOrderId the ID of the change order that the write belongs to, or null
     * @return the entry, with no sequence number yet
     */
    public static ChangeLogEntry write(String id, Instant recordedAt, String actorUserId, ResourceType resourceType,
            String resourceId, String operation, String resourcePath, String changeOrderId) {
        return new ChangeLogEntry(id, 0, recordedAt, Kind.WRITE, actorUserId, resourceType, resourceId, operation,
                resourcePath, changeOrderId, null, null);
    }

    /**
     * Makes the entry of a propagated change. It has no user, and its operation is {@code UPDATE}.
     *
     * @param id the ID
     * @param recordedAt when the cause happened
     * @param value the derived value that changed
     * @param resourceId the ID of the resource whose read changed
     * @param cause what changed the value
     * @param causeSequence the sequence number of the entry of the write that caused it, or null when no write of
     *         the API did
     * @param changeOrderId the ID of the change order of that write, or null
     * @return the entry, with no sequence number yet
     */
    public static ChangeLogEntry propagated(String id, Instant recordedAt, DerivedValue value, String resourceId,
            Cause cause, Long causeSequence, String changeOrderId) {
        return new ChangeLogEntry(id, 0, recordedAt, Kind.PROPAGATED, null, value.resourceType(), resourceId,
                "UPDATE", value.path(resourceId), changeOrderId, cause, causeSequence);
    }
}
