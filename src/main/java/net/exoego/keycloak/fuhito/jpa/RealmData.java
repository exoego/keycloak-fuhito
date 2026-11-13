package net.exoego.keycloak.fuhito.jpa;

import java.util.List;

import jakarta.persistence.EntityManager;

/**
 * All rows of this extension that belong to one realm.
 */
public final class RealmData {

    // Children come before the rows they refer to.
    private static final List<String> ENTITIES = List.of(
            "EmploymentTermsEntity",
            "EmploymentEntity",
            "OrgUnitLinkEntity",
            "OrgUnitVersionEntity",
            "OrgUnitEntity",
            "RefItemEntity",
            "HierarchyEntity");

    private RealmData() {
    }

    /**
     * Deletes every row of a realm.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     */
    public static void delete(EntityManager em, String realmId) {
        for (String entity : ENTITIES) {
            em.createQuery("delete from " + entity + " e where e.realmId = :realmId")
                    .setParameter("realmId", realmId)
                    .executeUpdate();
        }
    }
}
