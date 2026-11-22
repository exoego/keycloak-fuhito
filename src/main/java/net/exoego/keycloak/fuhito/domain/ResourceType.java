package net.exoego.keycloak.fuhito.domain;

/**
 * The type of a written resource in the change log and in the admin event log. The name of a constant is the text
 * that both logs keep.
 */
public enum ResourceType {
    /** An org unit. */
    FUHITO_ORG_UNIT,
    /** A person. */
    FUHITO_PERSON,
    /** The data of a person that a mask hides or a removal deletes. */
    FUHITO_PERSON_DATA,
    /** An employment. */
    FUHITO_EMPLOYMENT,
    /** An assignment. */
    FUHITO_ASSIGNMENT,
    /** A qualification. */
    FUHITO_QUALIFICATION,
    /** A reporting line. */
    FUHITO_REPORTING_LINE,
    /** A role over an org unit. */
    FUHITO_UNIT_ROLE,
    /** A change order. */
    FUHITO_CHANGE_ORDER,
    /** A reference item. */
    FUHITO_REF_ITEM,
    /** A kind of reference data that a realm added. */
    FUHITO_REF_KIND,
    /** A hierarchy. */
    FUHITO_HIERARCHY,
    /** The definition of a free attribute. */
    FUHITO_ATTRIBUTE_DEFINITION,
    /** The restore of a realm from an export. */
    FUHITO_RESTORE
}
