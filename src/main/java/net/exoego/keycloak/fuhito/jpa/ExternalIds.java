package net.exoego.keycloak.fuhito.jpa;

import jakarta.persistence.EntityManager;

/**
 * Removes the IDs that systems have for a resource that goes away.
 */
final class ExternalIds {

    private ExternalIds() {
    }

    /**
     * Removes the IDs of a resource.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     * @param kind the kind of resource, as the table of IDs names it
     * @param resourceId the ID of the resource
     */
    static void deleteOf(EntityManager em, String realmId, String kind, String resourceId) {
        em.createQuery("""
                        delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and x.resourceId = :id""")
                .setParameter("realmId", realmId)
                .setParameter("kind", kind)
                .setParameter("id", resourceId)
                .executeUpdate();
    }

    /**
     * Removes the IDs of an assignment that has no row left.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     * @param assignmentId the ID of the assignment
     */
    static void deleteOfGoneAssignment(EntityManager em, String realmId, String assignmentId) {
        em.createQuery("""
                        delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'ASSIGNMENT'
                        and x.resourceId = :id and not exists (select a.id from AssignmentEntity a
                        where a.realmId = :realmId and a.assignmentId = :id)""")
                .setParameter("realmId", realmId)
                .setParameter("id", assignmentId)
                .executeUpdate();
    }
}
