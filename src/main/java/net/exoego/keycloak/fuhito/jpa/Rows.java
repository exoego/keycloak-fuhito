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
