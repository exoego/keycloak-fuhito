package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.store.ExternalIdStore;

/**
 * Stores the IDs that systems have for the resources of one realm in the database of Keycloak.
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

    // Each kind has its own table. The rows of one assignment share its source.
    private static String originQuery(ExternalId.Kind kind) {
        return switch (kind) {
            case ORG_UNIT -> "select x.source from OrgUnitEntity x where x.realmId = :realmId and x.id = :id";
            case PERSON -> "select x.source from PersonEntity x where x.realmId = :realmId and x.id = :id";
            case EMPLOYMENT -> "select x.source from EmploymentEntity x where x.realmId = :realmId and x.id = :id";
            case QUALIFICATION -> """
                    select x.source from QualificationEntity x where x.realmId = :realmId and x.id = :id""";
            case ASSIGNMENT -> """
                    select x.source from AssignmentEntity x where x.realmId = :realmId and x.assignmentId = :id
                    order by x.validFrom""";
        };
    }

    @Override
    public Optional<ExternalId.Origin> originOf(ExternalId.Kind kind, String resourceId) {
        // The source can be null, which a stream of the results cannot hold.
        List<String> sources = em.createQuery(originQuery(kind), String.class)
                .setParameter("realmId", realmId)
                .setParameter("id", resourceId)
                .setMaxResults(1)
                .getResultList();
        return sources.isEmpty() ? Optional.empty() : Optional.of(new ExternalId.Origin(sources.getFirst()));
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
    public List<ExternalId> ofResources(ExternalId.Kind kind, Collection<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and %s""".formatted(ManyIds.has("x.resourceId", "ids")), ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("ids", ManyIds.of(resourceIds))
                .getResultStream()
                .map(JpaExternalIdStore::toDomain)
                .toList();
    }

    @Override
    public List<ExternalId> list(String afterId, int max) {
        return em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.id > :after
                        order by x.id""", ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max)
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
        entity.version = externalId.version();
        em.persist(entity);
        em.flush();
        Versions.of(em, realmId, externalId.kind(), externalId.resourceId());
    }

    @Override
    public void update(ExternalId externalId) {
        ExternalIdEntity entity = Rows.require(em, ExternalIdEntity.class, externalId.id(), realmId,
                found -> found.realmId);
        entity.externalId = externalId.externalId();
        // JPA counts up the version when it writes the row. A read after the write gives the new one.
        em.flush();
        Versions.of(em, realmId, ExternalId.Kind.valueOf(entity.kind), entity.resourceId);
    }

    @Override
    public void delete(String id) {
        ExternalIdEntity entity = em.find(ExternalIdEntity.class, id);
        if (entity != null && entity.realmId.equals(realmId)) {
            em.remove(entity);
            em.flush();
            Versions.of(em, realmId, ExternalId.Kind.valueOf(entity.kind), entity.resourceId);
        }
    }

    static ExternalId toDomain(ExternalIdEntity e) {
        return new ExternalId(e.id, ExternalId.Kind.valueOf(e.kind), e.resourceId, e.source, e.externalId,
                e.version);
    }
}
