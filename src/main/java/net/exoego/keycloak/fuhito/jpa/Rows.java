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

    /**
     * The condition in HQL that a row is of the realm, for a write or a paged read that finds its rows by a record,
     * as {@link #ofUnit} does for an org unit. The record can be the row itself. The rows of one assignment share
     * their realm, so the sub-select reads it once.
     *
     * @param realmPath the path of the realm of the row
     * @param entity the entity of the record
     * @param keyPath the path in the entity of the key that finds the record
     * @param keyParameter the name of the parameter with the key
     * @return the condition
     */
    static String ofRecord(String realmPath, String entity, String keyPath, String keyParameter) {
        String realmOfRecord = "(select distinct realmRecord.realmId from %s realmRecord where realmRecord.%s = :%s)"
                .formatted(entity, keyPath, keyParameter);
        return "%1$s = %2$s and %2$s = :realmId".formatted(realmPath, realmOfRecord);
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
