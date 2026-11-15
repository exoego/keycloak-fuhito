package net.exoego.keycloak.fuhito.domain;

import java.time.LocalDate;

/**
 * The fact that something about a resource starts, changes, or ends on a date.
 * It comes from the valid periods of the stored rows, not from the day of a write.
 *
 * @param date the date that a period of the resource starts or ends on
 * @param kind the kind of the resource
 * @param resourceId the ID of the resource
 */
public record EffectiveChange(LocalDate date, Kind kind, String resourceId) {

    /**
     * The kinds of resources with valid periods.
     */
    public enum Kind {
        /** The details of an org unit, or its parent. */
        ORG_UNIT,
        /** An employment, or its terms. */
        EMPLOYMENT,
        /** An assignment. */
        ASSIGNMENT,
        /** A reporting line. */
        REPORTING_LINE,
        /** A qualification. */
        QUALIFICATION,
        /** A role over an org unit. */
        UNIT_ROLE
    }
}
