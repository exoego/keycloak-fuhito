package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

/**
 * Stores the IDs that other systems have for the resources of one realm in the database of Keycloak.
 */
public class JpaExternalIdStore implements ExternalIdStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaExternalIdStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    // Each kind has its own table. An assignment keeps its pair on its rows, so the first row with one counts.
    private static String pairQuery(ExternalId.Kind kind) {
        return switch (kind) {
            case ORG_UNIT -> """
                    select x.source, x.externalId from OrgUnitEntity x where x.realmId = :realmId and x.id = :id""";
            case PERSON -> """
                    select x.source, x.externalId from PersonEntity x where x.realmId = :realmId and x.id = :id""";
            case EMPLOYMENT -> """
                    select x.source, x.externalId from EmploymentEntity x where x.realmId = :realmId and x.id = :id""";
            case QUALIFICATION -> """
                    select x.source, x.externalId from QualificationEntity x
                    where x.realmId = :realmId and x.id = :id""";
            case ASSIGNMENT -> """
                    select x.source, x.externalId from AssignmentEntity x
                    where x.realmId = :realmId and x.assignmentId = :id
                    order by case when x.externalId is null then 1 else 0 end, x.validFrom""";
        };
    }

    private static String withPairQuery(ExternalId.Kind kind) {
        return switch (kind) {
            case ORG_UNIT -> """
                    select x.id from OrgUnitEntity x where x.realmId = :realmId
                    and x.source = :source and x.externalId = :externalId""";
            case PERSON -> """
                    select x.id from PersonEntity x where x.realmId = :realmId
                    and x.source = :source and x.externalId = :externalId""";
            case EMPLOYMENT -> """
                    select x.id from EmploymentEntity x where x.realmId = :realmId
                    and x.source = :source and x.externalId = :externalId""";
            case QUALIFICATION -> """
                    select x.id from QualificationEntity x where x.realmId = :realmId
                    and x.source = :source and x.externalId = :externalId""";
            case ASSIGNMENT -> """
                    select distinct x.assignmentId from AssignmentEntity x where x.realmId = :realmId
                    and x.source = :source and x.externalId = :externalId""";
        };
    }

    @Override
    public Optional<ExternalId.Pair> pairOf(ExternalId.Kind kind, String resourceId) {
        return em.createQuery(pairQuery(kind), Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("id", resourceId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(row -> new ExternalId.Pair((String) row[0], (String) row[1]));
    }

    @Override
    public List<String> withPair(ExternalId.Kind kind, String source, String externalId) {
        return em.createQuery(withPairQuery(kind), String.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultList();
    }

    @Override
    public List<ExternalId> ofResource(ExternalId.Kind kind, String resourceId) {
        return em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and x.resourceId = :resourceId order by x.source""", ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("resourceId", resourceId)
                .getResultStream()
                .map(JpaExternalIdStore::toDomain)
                .toList();
    }

    @Override
    public Optional<ExternalId> find(ExternalId.Kind kind, String source, String externalId) {
        return em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and x.source = :source and x.externalId = :externalId""", ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst()
                .map(JpaExternalIdStore::toDomain);
    }

    @Override
    public void insert(ExternalId externalId) {
        ExternalIdEntity entity = new ExternalIdEntity();
        entity.id = externalId.id();
        entity.realmId = realmId;
        entity.kind = externalId.kind().name();
        entity.resourceId = externalId.resourceId();
        entity.source = externalId.source();
        entity.externalId = externalId.externalId();
        em.persist(entity);
        em.flush();
    }

    @Override
    public void update(ExternalId externalId) {
        Rows.require(em, ExternalIdEntity.class, externalId.id(), realmId, found -> found.realmId).externalId =
                externalId.externalId();
        em.flush();
    }

    @Override
    public void delete(String id) {
        ExternalIdEntity entity = em.find(ExternalIdEntity.class, id);
        if (entity != null && entity.realmId.equals(realmId)) {
            em.remove(entity);
            em.flush();
        }
    }

    private static ExternalId toDomain(ExternalIdEntity e) {
        return new ExternalId(e.id, ExternalId.Kind.valueOf(e.kind), e.resourceId, e.source, e.externalId);
    }
}
