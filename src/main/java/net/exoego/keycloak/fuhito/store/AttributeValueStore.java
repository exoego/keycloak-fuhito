package net.exoego.keycloak.fuhito.store;

import java.util.List;

import net.exoego.keycloak.fuhito.domain.AttributeDefinition;

/**
 * The stored values of one name of free attributes in every row of a kind of resource of one realm. A resource with
 * dated versions, such as an org unit, has a row for each version. A row with null for the name has no value.
 */
public interface AttributeValueStore {

    /**
     * Counts the rows with no value of a name.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @return the number of rows
     */
    long countMissing(AttributeDefinition.Target target, String name);

    /**
     * Counts the rows that have a name, also with null.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @return the number of rows
     */
    long countPresent(AttributeDefinition.Target target, String name);

    /**
     * Gives the size of the largest attributes that {@link #fill} would leave in a row.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @param value the value to put in
     * @return the size as JSON, in bytes of UTF-8, or 0 when no row has no value
     */
    long largestAfterFill(AttributeDefinition.Target target, String name, Object value);

    /**
     * Puts a value into every row with no value of a name. A row with a value keeps it. The version of the resource
     * of each such row counts up once in the transaction.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @param value the value
     * @return the IDs of the resources whose rows changed, in order
     */
    List<String> fill(AttributeDefinition.Target target, String name, Object value);

    /**
     * Removes a name from every row that has it. The version of the resource of each such row counts up once in the
     * transaction.
     *
     * @param target the kind of resource
     * @param name the name of the attribute
     * @return the IDs of the resources whose rows changed, in order
     */
    List<String> clear(AttributeDefinition.Target target, String name);
}
