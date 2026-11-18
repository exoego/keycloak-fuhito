package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Stores the reference items of one realm in the database of Keycloak.
 */
public class JpaRefItemStore implements RefItemStore {

    // One count for every column that holds the ID of a reference item.
    private static final List<String> REFERENCES = List.of(
            "select count(e) from OrgUnitVersionEntity e where e.unitTypeId = :id and e.realmId = :realmId",
            "select count(e) from EmploymentEntity e where e.endReasonId = :id and e.realmId = :realmId",
            "select count(e) from EmploymentTermsEntity e where e.employmentTypeId = :id and e.realmId = :realmId",
            "select count(e) from EmploymentTermsEntity e where e.gradeId = :id and e.realmId = :realmId",
            "select count(e) from EmploymentTermsEntity e where e.leaveReasonId = :id and e.realmId = :realmId",
            "select count(e) from AssignmentEntity e where e.jobTitleId = :id and e.realmId = :realmId",
            "select count(e) from AssignmentEntity e where e.occupationId = :id and e.realmId = :realmId",
            "select count(e) from AssignmentEntity e where e.kindId = :id and e.realmId = :realmId",
            "select count(e) from AssignmentEntity e where e.workLocationId = :id and e.realmId = :realmId",
            "select count(e) from AssignmentEntity e where e.costCenterId = :id and e.realmId = :realmId",
            "select count(e) from OrgUnitVersionEntity e where e.workLocationId = :id and e.realmId = :realmId",
            "select count(e) from OrgUnitVersionEntity e where e.costCenterId = :id and e.realmId = :realmId",
            "select count(e) from QualificationEntity e where e.kindId = :id and e.realmId = :realmId",
            "select count(e) from UnitRoleEntity e where e.roleId = :id and e.realmId = :realmId");

    // Each item with one row of its names.
    static final String ITEMS = "select r, n from RefItemEntity r join RefItemNameEntity n on n.refItemId = r.id"
            + " where r.realmId = :realmId";

    // The names of the last period, which has no end.
    static final String LAST_NAMES = " and n.validTo is null";

    // The names of the date. A date before the first period gets the first names.
    private static final String NAMES_ON_DATE = " and (n.validTo is null or n.validTo > :date)"
            + " and (n.validFrom is null or n.validFrom <= :date or not exists (select 1 from RefItemNameEntity o"
            + " where o.refItemId = n.refItemId and (o.validFrom is null or o.validFrom < n.validFrom)))";

    private static final String OF_KIND = " and r.kind = :kind and (r.active = true or :includeInactive = true)";
    private static final String ORDER = " order by r.displayOrder, r.code";
    private static final String COUNT = "select count(r) from RefItemEntity r where r.realmId = :realmId" + OF_KIND;

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
    public List<RefItem> list(String kind, boolean includeInactive) {
        return items(ofKind(LAST_NAMES, kind, includeInactive));
    }

    @Override
    public List<RefItem> list(String kind, boolean includeInactive, LocalDate date, int first, int max) {
        return items(ofKind(NAMES_ON_DATE, kind, includeInactive)
                .setParameter("date", date)
                .setFirstResult(first)
                .setMaxResults(max));
    }

    private TypedQuery<Object[]> ofKind(String names, String kind, boolean includeInactive) {
        return em.createQuery(ITEMS + OF_KIND + names + ORDER, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind)
                .setParameter("includeInactive", includeInactive);
    }

    @Override
    public long count(String kind, boolean includeInactive) {
        return em.createQuery(COUNT, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind)
                .setParameter("includeInactive", includeInactive)
                .getSingleResult();
    }

    @Override
    public Optional<RefItem> find(String id) {
        return first(byId(LAST_NAMES, id));
    }

    @Override
    public Optional<RefItem> find(String id, LocalDate date) {
        return first(byId(NAMES_ON_DATE, id).setParameter("date", date));
    }

    private TypedQuery<Object[]> byId(String names, String id) {
        return em.createQuery(ITEMS + " and r.id = :id" + names, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("id", id);
    }

    @Override
    public Optional<RefItem> findByCode(String kind, String code) {
        return first(byCode(LAST_NAMES, kind, code));
    }

    @Override
    public Optional<RefItem> findByCode(String kind, String code, LocalDate date) {
        return first(byCode(NAMES_ON_DATE, kind, code).setParameter("date", date));
    }

    private TypedQuery<Object[]> byCode(String names, String kind, String code) {
        return em.createQuery(ITEMS + " and r.kind = :kind and r.code = :code" + names, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("kind", kind)
                .setParameter("code", code);
    }

    @Override
    public List<RefItemName> names(String id) {
        return em.createQuery("""
                        select n from RefItemNameEntity n where n.realmId = :realmId
                        and n.refItemId = :id""", RefItemNameEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .getResultStream()
                .map(JpaRefItemStore::toDomain)
                // A database puts a missing date first or last by its own rule. The names of one item are few.
                .sorted(Comparator.comparing(RefItemName::validFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public boolean isTypeOfAnEmployer(String id) {
        return em.createQuery("""
                        select count(e) from EmploymentEntity e, OrgUnitVersionEntity v
                        where e.realmId = :realmId and v.realmId = :realmId and v.orgUnitId = e.employerId
                        and v.unitTypeId = :id
                        and v.validFrom <= e.validFrom and (v.validTo is null or v.validTo > e.validFrom)""",
                        Long.class)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .getSingleResult() > 0;
    }

    @Override
    public boolean isInUse(String id) {
        return REFERENCES.stream().anyMatch(reference -> em.createQuery(reference, Long.class)
                .setParameter("realmId", realmId)
                .setParameter("id", id)
                .getSingleResult() > 0);
    }

    @Override
    public void insert(RefItem item, List<RefItemName> names) {
        RefItemEntity entity = new RefItemEntity();
        entity.id = item.id();
        entity.realmId = realmId;
        entity.kind = item.kind();
        entity.code = item.code();
        entity.version = item.version();
        entity.counted = true;
        copyChangeable(item, entity);
        em.persist(entity);
        // Other rows refer to this one. Hibernate groups inserts by entity type, so this row goes to the
        // database now, before a row that needs it.
        em.flush();
        names.forEach(period -> em.persist(entity(period)));
    }

    @Override
    public void update(RefItem item) {
        RefItemEntity entity = findEntity(item.id()).orElseThrow();
        copyChangeable(item, entity);
        entity.countUp();
    }

    @Override
    public void insertNames(RefItemName names) {
        em.persist(entity(names));
        Versions.refItem(em, realmId, names.refItemId());
    }

    @Override
    public void updateNames(RefItemName names) {
        RefItemNameEntity entity = Rows.require(em, RefItemNameEntity.class, names.id(), realmId,
                found -> found.realmId);
        entity.validFrom = names.validFrom();
        entity.validTo = names.validTo();
        entity.name = names.name();
        entity.names = names.names();
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
        Versions.refItem(em, realmId, names.refItemId());
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            em.createQuery("delete from RefItemNameEntity n where n.realmId = :realmId and n.refItemId = :id")
                    .setParameter("realmId", realmId)
                    .setParameter("id", id)
                    .executeUpdate();
            em.remove(entity);
        });
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<RefItemEntity> findEntity(String id) {
        RefItemEntity entity = em.find(RefItemEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private RefItemNameEntity entity(RefItemName names) {
        RefItemNameEntity entity = new RefItemNameEntity();
        entity.id = names.id();
        entity.realmId = realmId;
        entity.refItemId = names.refItemId();
        entity.validFrom = names.validFrom();
        entity.validTo = names.validTo();
        entity.name = names.name();
        entity.names = names.names();
        return entity;
    }

    private static void copyChangeable(RefItem item, RefItemEntity entity) {
        entity.displayOrder = item.displayOrder();
        entity.attributes = item.attributes();
        entity.active = item.active();
        entity.legalEntity = item.legalEntity();
        entity.actingHead = item.actingHead();
        entity.lateralUnit = item.lateral();
    }

    private static List<RefItem> items(TypedQuery<Object[]> query) {
        return query.getResultStream().map(JpaRefItemStore::toDomain).toList();
    }

    private static Optional<RefItem> first(TypedQuery<Object[]> query) {
        return query.getResultStream().findFirst().map(JpaRefItemStore::toDomain);
    }

    static RefItem toDomain(Object[] row) {
        RefItemEntity e = (RefItemEntity) row[0];
        RefItemNameEntity n = (RefItemNameEntity) row[1];
        return new RefItem(e.id, e.kind, e.code, n.name, n.names, e.displayOrder, e.attributes,
                e.active, e.legalEntity, e.actingHead, e.lateralUnit, e.version, n.validFrom, n.validTo);
    }

    static RefItemName toDomain(RefItemNameEntity n) {
        return new RefItemName(n.id, n.refItemId, n.validFrom, n.validTo, n.name, n.names);
    }
}
