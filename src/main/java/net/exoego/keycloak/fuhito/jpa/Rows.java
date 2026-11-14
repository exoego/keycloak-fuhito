package net.exoego.keycloak.fuhito.jpa;

import java.util.function.Function;

import jakarta.persistence.EntityManager;

/**
 * Loads a row for a write, and refuses a row of another realm.
 */
final class Rows {

    private Rows() {
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
