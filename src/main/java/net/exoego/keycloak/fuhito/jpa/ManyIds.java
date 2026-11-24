package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;

/**
 * A condition on many IDs that binds them as one array.
 * A list after {@code in} binds one value for each ID, and PostgreSQL takes at most 65,535 values in a statement.
 */
final class ManyIds {

    private ManyIds() {
    }

    /**
     * The condition in HQL that a path is one of the IDs of a parameter.
     * The function {@code array_contains} of Hibernate becomes the operator {@code @>}, which no plain index
     * serves. So the condition is written as SQL.
     *
     * @param path the path of the ID
     * @param parameter the name of the parameter, which takes the value of {@link #of}
     * @return the condition
     */
    static String has(String path, String parameter) {
        return "cast(sql('? = any(?)', %s, :%s) as boolean)".formatted(path, parameter);
    }

    /**
     * A derived table in HQL with one row for each ID of a parameter, in the column {@code id}.
     * The function {@code unnest} of Hibernate needs the type of the array before the parameter is bound, so the
     * table is written as SQL.
     *
     * @param parameter the name of the parameter, which takes the value of {@link #of}
     * @return the derived table, to put after {@code from} with an alias
     */
    static String rows(String parameter) {
        return "(select cast(sql('unnest(?)', :%s) as String) as id)".formatted(parameter);
    }

    /**
     * The value to bind.
     *
     * @param ids the IDs
     * @return the IDs as an array
     */
    static String[] of(Collection<String> ids) {
        return ids.toArray(String[]::new);
    }
}
