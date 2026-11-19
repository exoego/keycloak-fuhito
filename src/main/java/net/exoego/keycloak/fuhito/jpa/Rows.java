package net.exoego.keycloak.fuhito.jpa;

import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.persistence.EntityManager;

/**
 * Keeps the rows of other realms out of a write or a read.
 */
final class Rows {

    private Rows() {
    }

    /**
     * Tells whether a row is of the realm, for a read that finds rows by the ID of a record. Such a read leaves the
     * realm out of its query. While the statistics of a table do not know a new realm, PostgreSQL can take an index
     * that starts with the realm for a query with the realm, and read every row of the realm. An ID belongs to one
     * realm, so a row of another realm comes only for an ID of another realm.
     *
     * @param realmId the ID of the realm
     * @param realmOf the realm of a row
     * @param <T> the type of the rows
     * @return whether a row is of the realm
     */
    static <T> Predicate<T> inRealm(String realmId, Function<T, String> realmOf) {
        return row -> realmOf.apply(row).equals(realmId);
    }

    /**
     * The condition in HQL that a row is of the realm, for a paged read that finds its rows by an org unit. Such a
     * read pages in the database, so it cannot drop rows after the query as {@link #inRealm} does. The realm of the
     * row must be the realm of the unit, and the unit must be of the realm of parameter {@code realmId}. A sub-select
     * finds the realm of the unit by its key. PostgreSQL does not know that value when it plans, so a realm that the
     * statistics do not know yet does not make an index of the realm look cheap.
     *
     * @param realmPath the path of the realm of the row
     * @param unitParameter the name of the parameter with the ID of the unit
     * @return the condition
     */
    static String ofUnit(String realmPath, String unitParameter) {
        String realmOfUnit = "(select realmUnit.realmId from OrgUnitEntity realmUnit where realmUnit.id = :%s)"
                .formatted(unitParameter);
        return "%1$s = %2$s and %2$s = :realmId".formatted(realmPath, realmOfUnit);
    }

    // The services find a row through its realm before they change it, so a miss here is a bug.
    // A write to a row of another realm must still never happen.
    static <T> T require(EntityManager em, Class<T> type, String id, String realmId, Function<T, String> realmOf) {
        T row = em.find(type, id);
        if (row == null || !realmOf.apply(row).equals(realmId)) {
            throw new IllegalStateException("No such " + type.getSimpleName() + " in the realm: " + id);
        }
        return row;
    }
}
