package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;

/**
 * Reads the hierarchies of a realm.
 */
public final class Hierarchies {

    private Hierarchies() {
    }

    /**
     * Lists the hierarchies of a realm.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @return the hierarchies, ordered by code
     */
    public static List<HierarchyEntity> list(EntityManager em, String realmId) {
        return em.createQuery("select h from HierarchyEntity h where h.realmId = :realmId order by h.code",
                        HierarchyEntity.class)
                .setParameter("realmId", realmId)
                .getResultList();
    }
}
