package net.exoego.keycloak.fuhito.domain;

/**
 * The kinds of reference items. Each realm defines its own items for each kind.
 */
public enum RefKind {

    /**
     * The kind of an org unit, for example "hospital" or "department".
     */
    UNIT_TYPE,

    /**
     * A managerial or functional title held in an assignment, for example "director".
     */
    JOB_TITLE,

    /**
     * The profession or kind of work in an assignment, for example "physician" or "clerk".
     */
    OCCUPATION,

    /**
     * The category of an employment contract, for example "full-time" or "temporary".
     */
    EMPLOYMENT_TYPE,

    /**
     * A rank level used for pay or seniority.
     */
    GRADE,

    /**
     * Why an employment ended, for example "resignation".
     */
    END_REASON,

    /**
     * How an assignment is held, for example "regular", "acting", or "support".
     */
    ASSIGNMENT_KIND
}
