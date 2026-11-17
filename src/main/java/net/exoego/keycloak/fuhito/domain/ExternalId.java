package net.exoego.keycloak.fuhito.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The ID that one system has for a resource. Every ID of every system is one of these, also the ID of the system
 * that made the resource.
 *
 * @param id the ID of the row
 * @param kind the kind of resource
 * @param resourceId the ID of the resource
 * @param source the name of the system
 * @param externalId the ID of the resource in that system
 * @param version the number that each write of the resource counts up. A new resource has 1.
 */
public record ExternalId(String id, Kind kind, String resourceId, String source, String externalId, long version) {

    /**
     * The kinds of resources that can have IDs of other systems.
     */
    public enum Kind {
        /** An org unit. */
        ORG_UNIT,
        /** A person. */
        PERSON,
        /** An employment. */
        EMPLOYMENT,
        /** An assignment. */
        ASSIGNMENT,
        /** A qualification. */
        QUALIFICATION
    }

    /**
     * The ID that a write gives for a resource in one system.
     *
     * @param source the name of the system
     * @param externalId the ID in that system
     */
    public record Pair(String source, String externalId) {
    }

    /**
     * The system that made a resource.
     *
     * @param source the name of the system, or null when the resource names none
     */
    public record Origin(String source) {
    }

    /**
     * Orders the IDs of one resource for a read. The ID of the system that made the resource comes first, then the
     * others by system.
     *
     * @param ids the IDs of the resource
     * @param origin the name of the system that made the resource, or null
     * @return the IDs in order
     */
    public static List<ExternalId> ordered(Collection<ExternalId> ids, String origin) {
        return ids.stream()
                .sorted(Comparator.comparing((ExternalId id) -> !Objects.equals(id.source(), origin))
                        .thenComparing(ExternalId::source))
                .toList();
    }
}
