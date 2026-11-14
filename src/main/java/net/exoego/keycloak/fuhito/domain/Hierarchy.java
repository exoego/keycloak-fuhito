package net.exoego.keycloak.fuhito.domain;

/**
 * A named tree of org units. Every realm has the default one.
 *
 * @param id the ID
 * @param code the code. It is unique in the realm and never changes.
 * @param name the name
 */
public record Hierarchy(String id, String code, String name) {

    /**
     * The code of the hierarchy that every realm has.
     */
    public static final String DEFAULT_CODE = "default";

    /**
     * Tells whether this is the hierarchy that every realm has.
     *
     * @return true for the default hierarchy
     */
    public boolean isDefault() {
        return DEFAULT_CODE.equals(code);
    }
}
