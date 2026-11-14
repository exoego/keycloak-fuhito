package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefKind;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Stores the reference items of one realm in the database of Keycloak.
 */
public class JpaRefItemStore implements RefItemStore {

    // Every column that holds the ID of a reference item.
    private static final List<String> REFERENCES = List.of(
            "OrgUnitVersionEntity e where e.unitTypeId",
            "EmploymentEntity e where e.endReasonId",
            "EmploymentTermsEntity e where e.employmentTypeId",
            "EmploymentTermsEntity e where e.gradeId",
            "AssignmentEntity e where e.jobTitleId",
            "AssignmentEntity e where e.occupationId",
            "AssignmentEntity e where e.kindId",
            "QualificationEntity e where e.kindId");

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaRefItemStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public List<RefItem> list(RefKind kind, boolean includeInactive) {
        return em.createQuery("""
                        select r from RefItemEntity r where r.realmId = :realmId and r.kind = :kind
                        and (r.active = true or :includeInactive = true) order by r.displayOrder, r.code""",
                        RefItemEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("includeInactive", includeInactive)
                .getResultStream()
                .map(JpaRefItemStore::toDomain)
                .toList();
    }

    @Override
    public Optional<RefItem> find(String id) {
        return findEntity(id).map(JpaRefItemStore::toDomain);
    }

    @Override
    public Optional<RefItem> findByCode(RefKind kind, String code) {
        return em.createQuery("""
                        select r from RefItemEntity r where r.realmId = :realmId and r.kind = :kind
                        and r.code = :code""", RefItemEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind.name())
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .map(JpaRefItemStore::toDomain);
    }

    @Override
    public boolean isInUse(String id) {
        return REFERENCES.stream().anyMatch(reference -> em.createQuery("select count(e) from " + reference
                        + " = :id and e.realmId = :realmId", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .getSingleResult() > 0);
    }

    @Override
    public void insert(RefItem item) {
        RefItemEntity entity = new RefItemEntity();
        entity.id = item.id();
        entity.realmId = realmId;
        entity.kind = item.kind().name();
        entity.code = item.code();
        copyChangeable(item, entity);
        em.persist(entity);
        // Other rows refer to this one. Hibernate groups inserts by entity type, so this row goes to the
        // database now, before a row that needs it.
        em.flush();
    }

    @Override
    public void update(RefItem item) {
        copyChangeable(item, findEntity(item.id()).orElseThrow());
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(em::remove);
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<RefItemEntity> findEntity(String id) {
        RefItemEntity entity = em.find(RefItemEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static void copyChangeable(RefItem item, RefItemEntity entity) {
        entity.name = item.name();
        entity.names = item.names();
        entity.displayOrder = item.displayOrder();
        entity.attributes = item.attributes();
        entity.active = item.active();
    }

    private static RefItem toDomain(RefItemEntity e) {
        return new RefItem(e.id, RefKind.valueOf(e.kind), e.code, e.name, e.names, e.displayOrder, e.attributes,
                e.active);
    }
}
