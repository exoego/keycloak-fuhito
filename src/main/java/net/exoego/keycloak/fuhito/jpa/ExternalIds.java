package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;

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
     * Removes the IDs of several resources of one kind, with one query.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     * @param kind the kind of resource, as the table of IDs names it
     * @param resourceIds the IDs of the resources
     */
    static void deleteOfAll(EntityManager em, String realmId, String kind, Collection<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        em.createQuery("""
                        delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and %s""".formatted(ManyIds.has("x.resourceId", "ids")))
                .setParameter("realmId", realmId)
                .setParameter("kind", kind)
                .setParameter("ids", ManyIds.of(resourceIds))
                .executeUpdate();
    }

    /**
     * Removes the IDs of the assignments that have no row left, with one query. The rows of an assignment are found
     * by its ID alone, for the reason in {@link Rows#inRealm}.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     * @param assignmentIds the IDs of the assignments
     */
    static void deleteOfGoneAssignments(EntityManager em, String realmId, Collection<String> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return;
        }
        em.createQuery("""
                        delete from ExternalIdEntity x where x.realmId = :realmId and x.kind = 'ASSIGNMENT'
                        and %s and not exists (select a.id from AssignmentEntity a
                        where a.assignmentId = x.resourceId)"""
                        .formatted(ManyIds.has("x.resourceId", "ids")))
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(assignmentIds))
                .executeUpdate();
    }
}
