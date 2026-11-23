package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;

/**
 * Reads the attributes of a realm from the tables of Keycloak, as the transaction sees them.
 * In a cluster, the cache of Keycloak on one node can still have a realm that another node removed or changed.
 */
public final class RealmAttributeValues {

    private RealmAttributeValues() {
    }

    /**
     * Reads one attribute of a realm.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param name the name of the attribute
     * @return the value, or null when the realm has no such attribute
     */
    public static String of(EntityManager em, String realmId, String name) {
        List<String> values = em.createQuery("""
                        select a.value from RealmAttributeEntity a where a.realm.id = :realmId and a.name = :name""",
                        String.class)
                .setParameter("realmId", realmId)
                .setParameter("name", name)
                .getResultList();
        return values.isEmpty() ? null : values.getFirst();
    }
}
