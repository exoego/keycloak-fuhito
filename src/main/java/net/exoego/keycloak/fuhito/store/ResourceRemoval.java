package net.exoego.keycloak.fuhito.store;

import java.util.List;

/**
 * Removes an org unit, an employment, or an assignment that was made by mistake, in one realm.
 */
public interface ResourceRemoval {

    /**
     * The name that {@link #dependents} gives to the employments that continue the resource.
     */
    String CONTINUATION = "continuation";

    /**
     * The kinds of resources that can be removed.
     */
    enum Kind {
        /** An org unit. */
        ORG_UNIT,
        /** An employment. */
        EMPLOYMENT,
        /** An assignment. */
        ASSIGNMENT
    }

    /**
     * Tells whether the realm has the resource.
     *
     * @param kind the kind of resource
     * @param id the ID
     * @return true when it exists
     */
    boolean exists(Kind kind, String id);

    /**
     * Names what still refers to the resource, for example {@code children} or {@code assignments}.
     *
     * @param kind the kind of resource
     * @param id the ID
     * @return the names, empty when nothing refers to it
     */
    List<String> dependents(Kind kind, String id);

    /**
     * Removes the resource with the rows that belong to it alone, such as its periods.
     *
     * @param kind the kind of resource
     * @param id the ID
     */
    void remove(Kind kind, String id);
}
