package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.ProjectedMemberStore;

/**
 * Stores the users that the projection added to Keycloak organizations, in the database of Keycloak.
 */
public class JpaProjectedMemberStore implements ProjectedMemberStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the session
     * @param realmId the ID of the realm
     */
    public JpaProjectedMemberStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public void record(String organizationId, String userId) {
        ProjectedMemberEntity entity = new ProjectedMemberEntity();
        entity.id = UUID.randomUUID().toString();
        entity.realmId = realmId;
        entity.organizationId = organizationId;
        entity.userId = userId;
        em.persist(entity);
    }

    @Override
    public void forget(String organizationId, String userId) {
        em.createQuery("""
                        delete from ProjectedMemberEntity m where m.realmId = :realmId
                        and m.organizationId = :organizationId and m.userId = :userId""")
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    public void forgetOrganization(String organizationId) {
        em.createQuery("""
                        delete from ProjectedMemberEntity m where m.realmId = :realmId
                        and m.organizationId = :organizationId""")
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .executeUpdate();
    }

    /**
     * Removes every record of a user, in every organization.
     *
     * @param userId the ID of the user
     */
    public void forgetUser(String userId) {
        em.createQuery("delete from ProjectedMemberEntity m where m.realmId = :realmId and m.userId = :userId")
                .setParameter("realmId", realmId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    public Set<String> recorded(String organizationId, Collection<String> userIds) {
        return new HashSet<>(em.createQuery("""
                        select m.userId from ProjectedMemberEntity m where m.realmId = :realmId
                        and m.organizationId = :organizationId and %s""".formatted(ManyIds.has("m.userId", "userIds")),
                        String.class)
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .setParameter("userIds", ManyIds.of(userIds))
                .getResultList());
    }

    @Override
    public List<String> recordedAfter(String organizationId, String afterUserId, int max) {
        return em.createQuery("""
                        select m.userId from ProjectedMemberEntity m where m.realmId = :realmId
                        and m.organizationId = :organizationId and m.userId > :after order by m.userId""",
                        String.class)
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .setParameter("after", afterUserId == null ? "" : afterUserId)
                .setMaxResults(max)
                .getResultList();
    }

    @Override
    public List<String> organizations() {
        return em.createQuery("""
                        select distinct m.organizationId from ProjectedMemberEntity m where m.realmId = :realmId
                        order by m.organizationId""", String.class)
                .setParameter("realmId", realmId)
                .getResultList();
    }
}
