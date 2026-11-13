package net.exoego.keycloak.fuhito.domain;

/**
 * Whether a person works during a period of an employment.
 */
public enum EmploymentStatus {

    /** The person works. */
    ACTIVE,

    /** The person is on leave, for example parental leave or sick leave. */
    ON_LEAVE,

    /** The person is suspended. */
    SUSPENDED
}
