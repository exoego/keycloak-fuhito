package net.exoego.keycloak.fuhito.jpa;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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

    // The criteria API names the entity by its class, so no query text is put together.
    private <E, T> Page<T> page(Class<E> entity, String idField, Function<E, T> toDomain) {
        return (afterId, max) -> {
            CriteriaBuilder builder = em.getCriteriaBuilder();
            CriteriaQuery<E> query = builder.createQuery(entity);
            Root<E> row = query.from(entity);
            query.where(builder.equal(row.get("realmId"), realmId),
                    builder.greaterThan(row.get(idField), afterId == null ? "" : afterId));
            query.orderBy(builder.asc(row.get(idField)));
            return em.createQuery(query).setMaxResults(max).getResultStream().map(toDomain).toList();
        };
    }

    @Override
    public Page<RealmKind> refKinds() {
        return page(RealmKindEntity.class, "id", JpaRealmKindStore::toDomain);
    }

    @Override
    public Page<RefItem> refItems() {
        return page(RefItemEntity.class, "id", JpaRefItemStore::toDomain);
    }

    @Override
    public Page<AttributeDefinition> attributeDefinitions() {
        return page(AttributeDefinitionEntity.class, "id", JpaAttributeDefinitionStore::toDomain);
    }

    // Every realm has its own default hierarchy, so it is not part of an export.
    @Override
    public Page<Hierarchy> hierarchies() {
        return (afterId, max) -> em.createQuery("""
                        select h from HierarchyEntity h where h.realmId = :realmId and h.code <> :code
                        and h.id > :after order by h.id""", HierarchyEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", Hierarchy.DEFAULT_CODE)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max)
                .getResultStream()
                .map(JpaHierarchyStore::toDomain)
                .toList();
    }

    @Override
    public Page<OrgUnit> orgUnits() {
        return page(OrgUnitEntity.class, "id", JpaOrgUnitStore::toDomain);
    }

    @Override
    public List<OrgUnitVersion> versionsOf(Collection<String> orgUnitIds) {
        return em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.realmId = :realmId and %s
                        order by v.orgUnitId, v.validFrom""".formatted(ManyIds.has("v.orgUnitId", "ids")),
                        OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<OrgUnitLink> parentLinksOf(Collection<String> orgUnitIds) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId and %s
                        order by l.childId, l.hierarchyId, l.validFrom""".formatted(ManyIds.has("l.childId", "ids")),
                        OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .getResultStream()
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
                .getResultStream()
                .map(e -> new OrgUnitSuccession(e.id, e.predecessorId, e.successorId, e.effectiveDate))
                .toList();
    }

    @Override
    public Page<Person> people() {
        return page(PersonEntity.class, "id", JpaPersonStore::toDomain);
    }

    @Override
    public Map<String, Instant> maskedAt(Collection<String> personIds) {
        Map<String, Instant> result = new HashMap<>();
        em.createQuery("""
                        select m from UserMaskEntity m where m.realmId = :realmId and %s"""
                        .formatted(ManyIds.has("m.personId", "ids")), UserMaskEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(personIds))
                .getResultStream()
                .forEach(mask -> result.put(mask.personId, mask.maskedAt));
        return result;
    }

    @Override
    public Page<Employment> employments() {
        return page(EmploymentEntity.class, "id", JpaEmploymentStore::toDomain);
    }

    @Override
    public List<EmploymentTerms> termsOf(Collection<String> employmentIds) {
        return new JpaEmploymentStore(em, realmId).termsOf(employmentIds);
    }

    @Override
    public Page<String> assignmentIds() {
        return (afterId, max) -> em.createQuery("""
                        select distinct a.assignmentId from AssignmentEntity a where a.realmId = :realmId
                        and a.assignmentId > :after order by a.assignmentId""", String.class)
                .setParameter("realmId", realmId)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max)
                .getResultList();
    }

    @Override
    public List<Assignment> rowsOf(Collection<String> assignmentIds) {
        return em.createQuery("""
                        select a from AssignmentEntity a where a.realmId = :realmId and %s
                        order by a.assignmentId, a.validFrom""".formatted(ManyIds.has("a.assignmentId", "ids")),
                        AssignmentEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("ids", ManyIds.of(assignmentIds))
                .getResultStream()
                .map(JpaAssignmentStore::toDomain)
                .toList();
    }

    @Override
    public Page<ReportingLine> reportingLines() {
        return page(ReportingLineEntity.class, "id", JpaReportingLineStore::toDomain);
    }

    @Override
    public Page<UnitRole> unitRoles() {
        return page(UnitRoleEntity.class, "id", e -> new UnitRole(e.id, e.employmentId, null, null, e.orgUnitId,
                e.roleId, e.includesDescendants, new ValidPeriod(e.validFrom, e.validTo)));
    }

    @Override
    public Page<Qualification> qualifications() {
        return page(QualificationEntity.class, "id", JpaQualificationStore::toDomain);
    }

    @Override
    public Page<ExternalId> externalIds() {
        return page(ExternalIdEntity.class, "id", JpaExternalIdStore::toDomain);
    }

    @Override
    public Page<ChangeOrder> changeOrders() {
        return page(ChangeOrderEntity.class, "id", JpaChangeOrderStore::toDomain);
    }

    @Override
    public boolean isEmpty() {
        for (Class<?> entity : RealmData.ENTITIES) {
            if (entity != HierarchyEntity.class && count(entity) > 0) {
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
    public void restoreMask(String personId, Instant maskedAt) {
        UserMaskEntity mark = new UserMaskEntity();
        mark.id = UUID.randomUUID().toString();
        mark.realmId = realmId;
        mark.personId = personId;
        mark.maskedAt = maskedAt;
        em.persist(mark);
    }
}
