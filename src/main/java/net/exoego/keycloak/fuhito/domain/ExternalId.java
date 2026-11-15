package net.exoego.keycloak.fuhito.domain;

/**
 * The ID that one more system has for a resource, next to the pair of source and external ID that the resource
 * itself has.
 *
 * @param id the ID of the row
 * @param kind the kind of resource
 * @param resourceId the ID of the resource
 * @param source the name of the system
 * @param externalId the ID of the resource in that system
 */
public record ExternalId(String id, Kind kind, String resourceId, String source, String externalId) {

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
     * The pair of source and external ID that a resource itself has.
     *
     * @param source the name of the system, or null
     * @param externalId the ID in that system, or null
     */
    public record Pair(String source, String externalId) {
    }
}
