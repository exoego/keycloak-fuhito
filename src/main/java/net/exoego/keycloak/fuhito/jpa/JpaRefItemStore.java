package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemName;
import net.exoego.keycloak.fuhito.domain.RefItemPeriod;
import net.exoego.keycloak.fuhito.store.RefItemStore;

/**
 * Stores the reference items of one realm in the database of Keycloak.
 */
public class JpaRefItemStore implements RefItemStore {

    // One count for every column that holds the ID of a reference item. The column of external employers has an index
    // of its own, so the realm of that count comes from the item, for the reason in Rows.ofUnit.
    private static final List<String> REFERENCES = List.of(
            "select count(e) from OrgUnitVersionEntity e where e.unitTypeId = :id and e.realmId = :realmId",
            "select count(e) from EmploymentEntity e where e.endReasonId = :id and e.realmId = :realmId",
            "select count(e) from EmploymentEntity e where e.externalEmployerId = :id and "
                    + Rows.ofRecord("e.realmId", "RefItemEntity", "id", "id"),
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

    // Each item with the rows of the names of one period, one row for each language.
    private static final String ITEMS = "select r, n from RefItemEntity r join RefItemNameEntity n"
            + " on n.refItemId = r.id where r.realmId = :realmId";

    private static final String ENTITIES = "select r from RefItemEntity r where r.realmId = :realmId";

    // The names of the last period, which has no end.
    private static final String LAST_NAMES = " and n.validTo is null";

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
        return items(ofKind(ITEMS + OF_KIND + LAST_NAMES + ORDER, Object[].class, kind, includeInactive)
                .getResultList());
    }

    // An item has a row for each language, so the page is of the items alone. Their names come next.
    @Override
    public List<RefItem> list(String kind, boolean includeInactive, LocalDate date, int first, int max) {
        return withNames(em, realmId, ofKind(ENTITIES + OF_KIND + ORDER, RefItemEntity.class, kind, includeInactive)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList(), date);
    }

    private <T> TypedQuery<T> ofKind(String query, Class<T> type, String kind, boolean includeInactive) {
        return em.createQuery(query, type)
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
        return find(id, null);
    }

    @Override
    public Optional<RefItem> find(String id, LocalDate date) {
        return first(onDate(ITEMS + " and r.id = :id", date).setParameter("id", id));
    }

    @Override
    public Optional<RefItem> findByCode(String kind, String code) {
        return findByCode(kind, code, null);
    }

    @Override
    public Optional<RefItem> findByCode(String kind, String code, LocalDate date) {
        return first(onDate(ITEMS + " and r.kind = :kind and r.code = :code", date)
                .setParameter("kind", kind)
                .setParameter("code", code));
    }

    // With no date, the names of the last period.
    private TypedQuery<Object[]> onDate(String query, LocalDate date) {
        String names = date == null ? LAST_NAMES : NAMES_ON_DATE;
        TypedQuery<Object[]> typed = em.createQuery(query + names, Object[].class).setParameter("realmId", realmId);
        return date == null ? typed : typed.setParameter("date", date);
    }

    private static Optional<RefItem> first(TypedQuery<Object[]> query) {
        return items(query.getResultList()).stream().findFirst();
    }

    @Override
    public List<RefItemPeriod> names(String id) {
        return periodsOf(em, realmId, List.of(id));
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
    public void insert(RefItem item, List<RefItemPeriod> names) {
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
        names.forEach(this::insertRows);
    }

    @Override
    public void update(RefItem item) {
        RefItemEntity entity = findEntity(item.id()).orElseThrow();
        copyChangeable(item, entity);
        entity.countUp();
    }

    @Override
    public void insertNames(RefItemPeriod names) {
        Versions.refItem(em, realmId, names.refItemId());
        insertRows(names);
    }

    @Override
    public void updateNames(RefItemPeriod names) {
        Versions.refItem(em, realmId, names.refItemId());
        em.createQuery("""
                        select n from RefItemNameEntity n where n.realmId = :realmId and n.refItemId = :refItemId
                        and n.periodId = :periodId""", RefItemNameEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("refItemId", names.refItemId())
                .setParameter("periodId", names.id())
                .getResultList()
                .forEach(em::remove);
        // A new row can have the key of an old one, and only one row of a period is primary.
        em.flush();
        insertRows(names);
        // A split shortens this period and then inserts the next one. The database must see the shorter one
        // first.
        em.flush();
    }

    private void insertRows(RefItemPeriod period) {
        period.names().byLanguage().forEach((language, name) -> {
            RefItemNameEntity row = new RefItemNameEntity();
            row.periodId = period.id();
            row.language = language;
            row.realmId = realmId;
            row.refItemId = period.refItemId();
            row.validFrom = period.validFrom();
            row.validTo = period.validTo();
            row.primary = language.equals(period.names().primaryLanguage());
            row.name = name.name();
            em.persist(row);
        });
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            em.createQuery("delete from RefItemNameEntity n where n.realmId = :realmId and n.refItemId = :id")
                    .setParameter("realmId", realmId)
                    .setParameter("id", id)
                    .executeUpdate();
            ExternalIds.deleteOf(em, realmId, "REF_ITEM", id);
            em.remove(entity);
        });
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<RefItemEntity> findEntity(String id) {
        RefItemEntity entity = em.find(RefItemEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static void copyChangeable(RefItem item, RefItemEntity entity) {
        entity.displayOrder = item.displayOrder();
        entity.attributes = item.attributes();
        entity.active = item.active();
        entity.legalEntity = item.legalEntity();
        entity.actingHead = item.actingHead();
        entity.lateralUnit = item.lateral();
        entity.rollupUnit = item.rollup();
    }

    /**
     * Gives items the names of a date. It reads the names of all of them in one query.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param items the items, in the order to return them
     * @param date the date of the names, or null for the names of the last period
     * @return the items with their names
     */
    static List<RefItem> withNames(EntityManager em, String realmId, List<RefItemEntity> items, LocalDate date) {
        if (items.isEmpty()) {
            return List.of();
        }
        TypedQuery<RefItemNameEntity> query = rows(em, realmId, items.stream().map(item -> item.id).toList(),
                date == null ? LAST_NAMES : NAMES_ON_DATE);
        if (date != null) {
            query.setParameter("date", date);
        }
        return withPeriods(items, periods(query.getResultList()));
    }

    private static List<RefItem> items(List<Object[]> rows) {
        Map<String, RefItemEntity> items = new LinkedHashMap<>();
        List<RefItemNameEntity> names = new ArrayList<>();
        for (Object[] row : rows) {
            RefItemEntity item = (RefItemEntity) row[0];
            items.putIfAbsent(item.id, item);
            names.add((RefItemNameEntity) row[1]);
        }
        return withPeriods(items.values(), periods(names));
    }

    private static List<RefItem> withPeriods(Collection<RefItemEntity> items, List<RefItemPeriod> periods) {
        Map<String, RefItemPeriod> byItem = new HashMap<>();
        periods.forEach(period -> byItem.put(period.refItemId(), period));
        return items.stream()
                .map(e -> new RefItem(e.id, e.kind, e.code, null, e.displayOrder, e.attributes, e.active,
                        e.legalEntity, e.actingHead, e.lateralUnit, e.rollupUnit, e.version, null, null)
                        .withNames(byItem.get(e.id)))
                .toList();
    }

    /**
     * Reads every period of the names of some items in one query.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param refItemIds the IDs of the items
     * @return the periods, oldest first
     */
    static List<RefItemPeriod> periodsOf(EntityManager em, String realmId, Collection<String> refItemIds) {
        return periods(rows(em, realmId, refItemIds, "").getResultList());
    }

    private static TypedQuery<RefItemNameEntity> rows(EntityManager em, String realmId,
            Collection<String> refItemIds, String filter) {
        return em.createQuery("""
                        select n from RefItemNameEntity n where n.realmId = :realmId and %s%s"""
                        .formatted(ManyIds.has("n.refItemId", "ids"), filter), RefItemNameEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(refItemIds));
    }

    // The rows of one period share its ID. A database puts a missing date first or last by its own rule.
    private static List<RefItemPeriod> periods(List<RefItemNameEntity> rows) {
        Map<String, Map<String, RefItemName>> names = new LinkedHashMap<>();
        Map<String, RefItemNameEntity> primary = new HashMap<>();
        for (RefItemNameEntity row : rows) {
            names.computeIfAbsent(row.periodId, id -> new HashMap<>()).put(row.language, new RefItemName(row.name));
            if (row.primary) {
                primary.put(row.periodId, row);
            }
        }
        return names.entrySet().stream()
                .map(period -> {
                    RefItemNameEntity row = primary.get(period.getKey());
                    return new RefItemPeriod(row.periodId, row.refItemId, row.validFrom, row.validTo,
                            new Localized<>(row.language, period.getValue()));
                })
                .sorted(Comparator.comparing(RefItemPeriod::validFrom,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }
}
