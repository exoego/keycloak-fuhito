package net.exoego.keycloak.fuhito.domain;

/**
 * The definition of one free attribute of one kind of resource in a realm.
 *
 * @param id the ID
 * @param target the kind of resource that has the attribute
 * @param name the name of the attribute in {@code attributes}
 * @param type the type of the value
 * @param refKind the code of the kind of reference data for the type {@code REF_ITEM}, fixed or of the realm, or
 *        null
 * @param required true when every write of the resource must give a value
 */
public record AttributeDefinition(String id, Target target, String name, Type type, String refKind,
        boolean required) {

    /**
     * The kinds of resources that have free attributes.
     */
    public enum Target {
        /** The details of an org unit. */
        ORG_UNIT,
        /** A person. */
        PERSON,
        /** The terms of an employment. */
        EMPLOYMENT_TERMS,
        /** The details of an assignment. */
        ASSIGNMENT,
        /** A qualification. */
        QUALIFICATION,
        /** A reference item. */
        REF_ITEM
    }

    /**
     * The types of values.
     */
    public enum Type {
        /** A text. */
        STRING,
        /** A number. */
        NUMBER,
        /** True or false. */
        BOOLEAN,
        /** A date as {@code YYYY-MM-DD}. */
        DATE,
        /** The code of a reference item of one kind. */
        REF_ITEM
    }
}
