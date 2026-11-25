package net.exoego.keycloak.fuhito.jpa;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.store.HierarchyStore;

/**
 * Stores the hierarchies of one realm in the database of Keycloak.
 */
public class JpaHierarchyStore implements HierarchyStore {

    private static final String ALL = " from HierarchyEntity h where h.realmId = :realmId";

    private static final String LIST = "select h" + ALL + " order by h.code";

    private static final String COUNT = "select count(h)" + ALL;

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaHierarchyStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public String defaultId() {
        return findByCode(Hierarchy.DEFAULT_CODE).map(Hierarchy::id).orElseGet(() -> {
            // The ID of the realm, the same as the migration gives to the realms that it fills in.
            Hierarchy created = new Hierarchy(realmId, Hierarchy.DEFAULT_CODE, "Default", 1);
            insert(created);
            return created.id();
        });
    }

    @Override
    public List<Hierarchy> list(int first, int max) {
        return em.createQuery(LIST, HierarchyEntity.class)
                .setParameter("realmId", realmId)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList().stream()
                .map(JpaHierarchyStore::toDomain)
                .toList();
    }

    @Override
    public long count() {
        return em.createQuery(COUNT, Long.class)
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    @Override
    public Optional<Hierarchy> find(String id) {
        HierarchyEntity entity = em.find(HierarchyEntity.class, id);
        // The realm filter keeps one realm from reaching the rows of another by ID.
        return entity != null && entity.getRealmId().equals(realmId) ? Optional.of(toDomain(entity))
                : Optional.empty();
    }

    @Override
    public Optional<Hierarchy> findByCode(String code) {
        return em.createQuery("select h from HierarchyEntity h where h.realmId = :realmId and h.code = :code",
                        HierarchyEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .getResultList().stream()
                .findFirst()
                .map(JpaHierarchyStore::toDomain);
    }

    @Override
    public void insert(Hierarchy hierarchy) {
        HierarchyEntity entity = new HierarchyEntity();
        entity.setId(hierarchy.id());
        entity.setRealmId(realmId);
        entity.setCode(hierarchy.code());
        entity.setName(hierarchy.name());
        entity.setVersion(hierarchy.version());
        em.persist(entity);
        // Parent links refer to this row, so it goes to the database now.
        em.flush();
    }

    @Override
    public void update(Hierarchy hierarchy) {
        HierarchyEntity entity = Rows.require(em, HierarchyEntity.class, hierarchy.id(), realmId,
                HierarchyEntity::getRealmId);
        entity.setName(hierarchy.name());
        entity.countUp();
    }

    @Override
    public void delete(String id) {
        HierarchyEntity entity = Rows.require(em, HierarchyEntity.class, id, realmId, HierarchyEntity::getRealmId);
        ExternalIds.deleteOf(em, realmId, "HIERARCHY", id);
        em.remove(entity);
        // Hibernate writes inserts before deletes, so a new hierarchy with the same code would come first.
        em.flush();
    }

    @Override
    public boolean hasLinks(String id) {
        return em.createQuery("""
                        select count(l) from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", id)
                .getSingleResult() > 0;
    }

    static Hierarchy toDomain(HierarchyEntity entity) {
        return new Hierarchy(entity.getId(), entity.getCode(), entity.getName(), entity.getVersion());
    }
}
