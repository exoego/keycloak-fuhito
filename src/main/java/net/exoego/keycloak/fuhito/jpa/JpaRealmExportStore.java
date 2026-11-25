package net.exoego.keycloak.fuhito.jpa;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import net.exoego.keycloak.fuhito.domain.Assignment;
import net.exoego.keycloak.fuhito.domain.AttributeDefinition;
import net.exoego.keycloak.fuhito.domain.ChangeOrder;
import net.exoego.keycloak.fuhito.domain.Employment;
import net.exoego.keycloak.fuhito.domain.EmploymentTerms;
import net.exoego.keycloak.fuhito.domain.ExternalId;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.Person;
import net.exoego.keycloak.fuhito.domain.Qualification;
import net.exoego.keycloak.fuhito.domain.RealmKind;
import net.exoego.keycloak.fuhito.domain.RefItem;
import net.exoego.keycloak.fuhito.domain.RefItemPeriod;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.RealmExportStore;

/**
 * Reads every stored row of one realm page by page from the database of Keycloak, for an export.
 */
public class JpaRealmExportStore implements RealmExportStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaRealmExportStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    // The criteria API names the entity by its class, so no query text is put together. Only the pages compare
    // REALM_ID || '', so only they take the index of the export of each table, for the reason in the change set
    // 0.1.0-export-page-indexes.
    private <E, T> Page<T> page(Class<E> entity, Function<E, T> toDomain) {
        return (afterId, max) -> {
            CriteriaBuilder builder = em.getCriteriaBuilder();
            CriteriaQuery<E> query = builder.createQuery(entity);
            Root<E> row = query.from(entity);
            query.where(builder.equal(builder.concat(row.get("realmId"), builder.literal("")), realmId),
                    builder.greaterThan(row.get("id"), afterId == null ? "" : afterId));
            query.orderBy(builder.asc(row.get("id")));
            return em.createQuery(query).setMaxResults(max).getResultList().stream().map(toDomain).toList();
        };
    }

    @Override
    public Page<RealmKind> refKinds() {
        Page<RealmKindEntity> rows = page(RealmKindEntity.class, Function.identity());
        return (afterId, max) -> JpaRealmKindStore.withNames(em, realmId, rows.read(afterId, max));
    }

    @Override
    public Page<RefItem> refItems() {
        Page<RefItemEntity> rows = page(RefItemEntity.class, Function.identity());
        return (afterId, max) -> JpaRefItemStore.withNames(em, realmId, rows.read(afterId, max), null);
    }

    @Override
    public List<RefItemPeriod> namesOf(Collection<String> refItemIds) {
        return JpaRefItemStore.periodsOf(em, realmId, refItemIds);
    }

    @Override
    public Page<AttributeDefinition> attributeDefinitions() {
        return page(AttributeDefinitionEntity.class, JpaAttributeDefinitionStore::toDomain);
    }

    @Override
    public Page<Hierarchy> hierarchies() {
        return page(HierarchyEntity.class, JpaHierarchyStore::toDomain);
    }

    @Override
    public Page<OrgUnit> orgUnits() {
        return page(OrgUnitEntity.class, JpaOrgUnitStore::toDomain);
    }

    // The versions are found by their units alone, for the reason in Rows.inRealm.
    @Override
    public List<OrgUnitVersion> versionsOf(Collection<String> orgUnitIds) {
        return JpaOrgUnitStore.withNames(em, realmId, em.createQuery("""
                        select v from OrgUnitVersionEntity v where %s
                        order by v.orgUnitId, v.validFrom""".formatted(ManyIds.has("v.orgUnitId", "ids")),
                        OrgUnitVersionEntity.class)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, v -> v.realmId))
                .toList());
    }

    @Override
    public List<OrgUnitLink> parentLinksOf(Collection<String> orgUnitIds) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId and %s
                        order by l.childId, l.hierarchyId, l.validFrom""".formatted(ManyIds.has("l.childId", "ids")),
                        OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .getResultList().stream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<OrgUnitSuccession> successionsOf(Collection<String> orgUnitIds) {
        return em.createQuery("""
                        select s from OrgUnitSuccessionEntity s where s.realmId = :realmId and %s
                        order by s.predecessorId, s.successorId""".formatted(ManyIds.has("s.predecessorId", "ids")),
                        OrgUnitSuccessionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .getResultList().stream()
                .map(e -> new OrgUnitSuccession(e.id, e.predecessorId, e.successorId, e.effectiveDate))
                .toList();
    }

    @Override
    public Page<Person> people() {
        Page<PersonEntity> rows = page(PersonEntity.class, Function.identity());
        return (afterId, max) -> JpaPersonStore.withNames(em, realmId, rows.read(afterId, max));
    }

    @Override
    public Map<String, Instant> maskedAt(Collection<String> personIds) {
        Map<String, Instant> result = new HashMap<>();
        em.createQuery("""
                        select m from UserMaskEntity m where m.realmId = :realmId and %s"""
                        .formatted(ManyIds.has("m.personId", "ids")), UserMaskEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(personIds))
                .getResultList().stream()
                .forEach(mask -> result.put(mask.personId, mask.maskedAt));
        return result;
    }

    @Override
    public Page<Employment> employments() {
        return page(EmploymentEntity.class, JpaEmploymentStore::toDomain);
    }

    @Override
    public List<EmploymentTerms> termsOf(Collection<String> employmentIds) {
        return new JpaEmploymentStore(em, realmId).termsOf(employmentIds);
    }

    // An assignment has one row of its version as long as it has rows. The page takes the index of the export of
    // that table, for the reason in page.
    @Override
    public Page<String> assignmentIds() {
        return (afterId, max) -> em.createQuery("""
                        select v.id from AssignmentVersionEntity v where (v.realmId || '') = :realmId
                        and v.id > :after order by v.id""", String.class)
                .setParameter("realmId", realmId)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max)
                .getResultList();
    }

    // The rows are found by their assignments alone, for the reason in Rows.inRealm.
    @Override
    public List<Assignment> rowsOf(Collection<String> assignmentIds) {
        return AssignmentReads.of(em, em.createQuery("""
                        select a from AssignmentEntity a where %s
                        order by a.assignmentId, a.validFrom""".formatted(ManyIds.has("a.assignmentId", "ids")),
                        AssignmentEntity.class)
                .setParameter("ids", ManyIds.of(assignmentIds))
                .getResultList().stream()
                .filter(Rows.inRealm(realmId, a -> a.realmId))
                .toList());
    }

    @Override
    public Page<ReportingLine> reportingLines() {
        return page(ReportingLineEntity.class, JpaReportingLineStore::toDomain);
    }

    @Override
    public Page<UnitRole> unitRoles() {
        return page(UnitRoleEntity.class, e -> new UnitRole(e.id, e.employmentId, null, null, e.orgUnitId,
                e.roleId, e.includesDescendants, new ValidPeriod(e.validFrom, e.validTo), e.version));
    }

    @Override
    public Page<Qualification> qualifications() {
        return page(QualificationEntity.class, JpaQualificationStore::toDomain);
    }

    @Override
    public Page<ExternalId> externalIds() {
        return page(ExternalIdEntity.class, JpaExternalIdStore::toDomain);
    }

    @Override
    public Page<ChangeOrder> changeOrders() {
        return page(ChangeOrderEntity.class, JpaChangeOrderStore::toDomain);
    }

    // The last runs of the jobs and the members that the projection added are not data, and an export has none of
    // them. A job can run in a new realm before the restore, for example the timer of the projection.
    private static final Set<Class<?>> NOT_DATA = Set.of(HierarchyEntity.class, JobRunEntity.class,
            ProjectedMemberEntity.class);

    // The table of each list of an export.
    private static final Map<String, Class<?>> TABLES = Map.ofEntries(
            Map.entry("ref-kinds", RealmKindEntity.class),
            Map.entry("ref-items", RefItemEntity.class),
            Map.entry("ref-item-names", RefItemNameEntity.class),
            Map.entry("attribute-definitions", AttributeDefinitionEntity.class),
            Map.entry("hierarchies", HierarchyEntity.class),
            Map.entry("org-units", OrgUnitEntity.class),
            Map.entry("org-unit-versions", OrgUnitVersionEntity.class),
            Map.entry("org-unit-parents", OrgUnitLinkEntity.class),
            Map.entry("org-unit-successions", OrgUnitSuccessionEntity.class),
            Map.entry("people", PersonEntity.class),
            Map.entry("employments", EmploymentEntity.class),
            Map.entry("employment-terms", EmploymentTermsEntity.class),
            Map.entry("assignments", AssignmentEntity.class),
            Map.entry("reporting-lines", ReportingLineEntity.class),
            Map.entry("unit-roles", UnitRoleEntity.class),
            Map.entry("qualifications", QualificationEntity.class),
            Map.entry("external-ids", ExternalIdEntity.class),
            Map.entry("change-orders", ChangeOrderEntity.class),
            Map.entry("erasures", ErasureEntity.class));

    // The fields of an export whose field in the entity has another name. The rows of the names of one period share
    // the ID of the period.
    private static final Map<String, Map<String, String>> RENAMED = Map.of(
            "ref-item-names", Map.of("id", "periodId"),
            "assignments", Map.of("rowId", "id"),
            "external-ids", Map.of("resourceType", "kind"));

    // A CLOB takes a text of any length, but @Column gives it the default length.
    private static final Set<String> CLOBS = Set.of("note");

    private static final Map<String, Map<String, Integer>> LENGTHS = TABLES.keySet().stream()
            .collect(Collectors.toUnmodifiableMap(Function.identity(), JpaRealmExportStore::lengthsOf));

    private static Map<String, Integer> lengthsOf(String list) {
        Map<String, Integer> lengths = new HashMap<>();
        for (Field field : TABLES.get(list).getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && field.getType() == String.class && !CLOBS.contains(field.getName())) {
                lengths.put(field.getName(), column.length());
            }
        }
        RENAMED.getOrDefault(list, Map.of()).forEach((exported, stored) -> lengths.put(exported,
                lengths.remove(stored)));
        return Map.copyOf(lengths);
    }

    @Override
    public Map<String, Integer> lengths(String list) {
        return LENGTHS.get(list);
    }

    @Override
    public Optional<String> takenId(String list, String field, Collection<String> ids) {
        Class<?> table = TABLES.get(list);
        if (table == null || ids.isEmpty()) {
            return Optional.empty();
        }
        String id = "x." + RENAMED.getOrDefault(list, Map.of()).getOrDefault(field, field);
        return em.createQuery("select %s from %s x where %s".formatted(id, table.getSimpleName(),
                        ManyIds.has(id, "ids")), String.class)
                .setParameter("ids", ManyIds.of(ids))
                .setMaxResults(1)
                .getResultList().stream()
                .findFirst();
    }

    @Override
    public boolean isEmpty() {
        for (Class<?> entity : RealmData.ENTITIES) {
            if (!NOT_DATA.contains(entity) && count(entity) > 0) {
                return false;
            }
        }
        return em.createQuery("""
                        select count(h) from HierarchyEntity h where h.realmId = :realmId and h.code <> :code""",
                        Long.class)
                .setParameter("realmId", realmId)
                .setParameter("code", Hierarchy.DEFAULT_CODE)
                .getSingleResult() == 0;
    }

    private <E> long count(Class<E> entity) {
        CriteriaBuilder builder = em.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<E> row = query.from(entity);
        query.select(builder.count(row)).where(builder.equal(row.get("realmId"), realmId));
        return em.createQuery(query).getSingleResult();
    }

    @Override
    public List<String> linkedOrganizations() {
        return em.createQuery("""
                        select u.keycloakOrganizationId from OrgUnitEntity u
                        where u.realmId = :realmId and u.keycloakOrganizationId is not null""", String.class)
                .setParameter("realmId", realmId)
                .getResultList();
    }

    @Override
    public void deleteData() {
        RealmData.deleteForRestore(em, realmId);
    }

    // The unique key of the realm and the person gives the page in its order, also while the statistics are old.
    @Override
    public Page<Erasure> erasures() {
        return (afterId, max) -> em.createQuery("""
                        select e from ErasureEntity e where e.realmId = :realmId and e.personId > :after
                        order by e.personId""", ErasureEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max)
                .getResultList().stream()
                .map(e -> new Erasure(e.personId, e.erasedAt))
                .toList();
    }

    @Override
    public void restoreErasure(Erasure erasure) {
        RealmData.recordErasure(em, realmId, erasure.personId(), erasure.erasedAt());
    }

    @Override
    public void eraseAgain() {
        em.flush();
        em.createQuery("""
                        select e.personId from ErasureEntity e where e.realmId = :realmId
                        and e.personId in (select p.id from PersonEntity p where p.realmId = :realmId)""",
                        String.class)
                .setParameter("realmId", realmId)
                .getResultList()
                .forEach(personId -> RealmData.deletePerson(em, realmId, personId));
    }

    @Override
    public void restoreMask(String personId, Instant maskedAt) {
        UserMaskEntity mark = new UserMaskEntity();
        mark.id = UUID.randomUUID().toString();
        mark.realmId = realmId;
        mark.personId = personId;
        mark.maskedAt = maskedAt;
        em.persist(mark);
    }
}
