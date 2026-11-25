package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    // Each kind has its own table. The rows of one assignment share its source. The other kinds keep no source, so
    // the query reads the ID only to find the resource. The rows come by the ID alone, for the reason in
    // Rows.inRealm.
    private static String originQuery(ExternalId.Kind kind) {
        return switch (kind) {
            case ORG_UNIT -> "select x.realmId, x.source from OrgUnitEntity x where x.id = :id";
            case PERSON -> "select x.realmId, x.source from PersonEntity x where x.id = :id";
            case EMPLOYMENT -> "select x.realmId, x.source from EmploymentEntity x where x.id = :id";
            case QUALIFICATION -> "select x.realmId, x.source from QualificationEntity x where x.id = :id";
            case ASSIGNMENT -> """
                    select x.realmId, x.source from AssignmentEntity x where x.assignmentId = :id
                    order by x.validFrom""";
            case REPORTING_LINE -> "select x.realmId, x.id from ReportingLineEntity x where x.id = :id";
            case UNIT_ROLE -> "select x.realmId, x.id from UnitRoleEntity x where x.id = :id";
            case CHANGE_ORDER -> "select x.realmId, x.id from ChangeOrderEntity x where x.id = :id";
            case REF_ITEM -> "select x.realmId, x.id from RefItemEntity x where x.id = :id";
            case REF_KIND -> "select x.realmId, x.id from RealmKindEntity x where x.id = :id";
            case HIERARCHY -> "select x.realmId, x.id from HierarchyEntity x where x.id = :id";
            case ATTRIBUTE_DEFINITION -> "select x.realmId, x.id from AttributeDefinitionEntity x where x.id = :id";
        };
    }

    private static boolean keepsSource(ExternalId.Kind kind) {
        return switch (kind) {
            case ORG_UNIT, PERSON, EMPLOYMENT, QUALIFICATION, ASSIGNMENT -> true;
            case REPORTING_LINE, UNIT_ROLE, CHANGE_ORDER, REF_ITEM, REF_KIND, HIERARCHY, ATTRIBUTE_DEFINITION -> false;
        };
    }

    @Override
    public Optional<ExternalId.Origin> originOf(ExternalId.Kind kind, String resourceId) {
        return em.createQuery(originQuery(kind), Object[].class)
                .setParameter("id", resourceId)
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .findFirst()
                .map(row -> new ExternalId.Origin(keepsSource(kind) ? (String) row[1] : null));
    }

    @Override
    public List<ExternalId> ofResource(ExternalId.Kind kind, String resourceId) {
        return em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and x.resourceId = :resourceId order by x.source""", ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("resourceId", resourceId)
                .getResultList().stream()
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
                .getResultList().stream()
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
                .getResultList().stream()
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
                .getResultList().stream()
                .findFirst()
                .map(JpaExternalIdStore::toDomain);
    }

    @Override
    public List<ExternalId> findAll(ExternalId.Kind kind, String source, Collection<String> externalIds) {
        if (externalIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind = :kind
                        and x.source = :source and %s""".formatted(ManyIds.has("x.externalId", "values")),
                        ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("source", source)
                .setParameter("values", ManyIds.of(externalIds))
                .getResultList().stream()
                .map(JpaExternalIdStore::toDomain)
                .toList();
    }

    @Override
    public void insert(ExternalId externalId) {
        insertAll(List.of(externalId));
    }

    @Override
    public void insertAll(List<ExternalId> externalIds) {
        if (externalIds.isEmpty()) {
            return;
        }
        for (ExternalId externalId : externalIds) {
            ExternalIdEntity entity = new ExternalIdEntity();
            entity.id = externalId.id();
            entity.realmId = realmId;
            entity.kind = externalId.kind().name();
            entity.resourceId = externalId.resourceId();
            entity.source = externalId.source();
            entity.externalId = externalId.externalId();
            entity.version = externalId.version();
            em.persist(entity);
        }
        em.flush();
        Map<ExternalId.Kind, Set<String>> resources = new EnumMap<>(ExternalId.Kind.class);
        externalIds.forEach(row -> resources.computeIfAbsent(row.kind(), kind -> new LinkedHashSet<>())
                .add(row.resourceId()));
        resources.forEach((kind, ids) -> Versions.of(em, realmId, kind, ids));
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
    public void moveAll(Map<ExternalId.Kind, Map<String, String>> newResourceIds) {
        Set<String> ids = new HashSet<>();
        newResourceIds.values().forEach(of -> ids.addAll(of.keySet()));
        if (ids.isEmpty()) {
            return;
        }
        // An ID of one kind can also be an ID of another kind, so each row looks in the map of its own kind.
        em.createQuery("""
                        select x from ExternalIdEntity x where x.realmId = :realmId and x.kind in :kinds
                        and %s""".formatted(ManyIds.has("x.resourceId", "ids")), ExternalIdEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kinds", newResourceIds.keySet().stream().map(Enum::name).toList())
                .setParameter("ids", ManyIds.of(ids))
                .getResultList()
                .forEach(entity -> {
                    String moved = newResourceIds.get(ExternalId.Kind.valueOf(entity.kind)).get(entity.resourceId);
                    if (moved != null) {
                        entity.resourceId = moved;
                    }
                });
        // JPA counts up the version of each row that it writes.
        em.flush();
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
