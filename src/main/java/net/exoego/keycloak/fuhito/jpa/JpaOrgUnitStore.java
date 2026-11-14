package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * Stores the org units of one realm in the database of Keycloak.
 */
public class JpaOrgUnitStore implements OrgUnitStore {

    /**
     * The code of the hierarchy that every realm has.
     */
    public static final String DEFAULT_HIERARCHY_CODE = "default";

    private static final String ON_DATE = "validFrom <= :date and (%1$s.validTo is null or %1$s.validTo > :date)";

    private static final String NO_PARENT = """
            not exists (select 1 from OrgUnitLinkEntity l where l.realmId = :realmId
            and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)""".formatted(ON_DATE.formatted("l"));

    // The database walks the tree, so that a read of one page does not load the whole tree.
    private static final String TREE = """
            with Tree as (
                select v.orgUnitId as id, v.id as versionId,
                    (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%1$s) as parentId,
                    v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name
                from OrgUnitVersionEntity v
                where v.realmId = :realmId and v.%2$s and %%s
                union all
                select v.orgUnitId as id, v.id as versionId, l.parentId as parentId,
                    v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name
                from Tree p
                join OrgUnitLinkEntity l on l.parentId = p.id and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%1$s
                join OrgUnitVersionEntity v on v.orgUnitId = l.childId and v.realmId = :realmId and v.%2$s
            ) search depth first by displayOrder, phoneticName, name, id set position
            select u, v, t.parentId from Tree t
            join OrgUnitEntity u on u.id = t.id
            join OrgUnitVersionEntity v on v.id = t.versionId
            order by t.position""".formatted(ON_DATE.formatted("l"), ON_DATE.formatted("v"));

    private static final String WHOLE_TREE = TREE.formatted(NO_PARENT);
    private static final String SUBTREE = TREE.formatted("v.orgUnitId = :rootId");

    private static final String SIBLINGS = """
            select u, v from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId
            where v.realmId = :realmId and v.%s and %%s
            order by v.displayOrder, v.phoneticName nulls last, v.name, v.orgUnitId""".formatted(ON_DATE.formatted("v"));

    private static final String ROOTS = SIBLINGS.formatted(NO_PARENT);
    private static final String CHILDREN = SIBLINGS.formatted("""
            exists (select 1 from OrgUnitLinkEntity l where l.realmId = :realmId
            and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.parentId = :parentId
            and l.%s)""".formatted(ON_DATE.formatted("l")));

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaOrgUnitStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public String defaultHierarchyId() {
        return em.createQuery("select h.id from HierarchyEntity h where h.realmId = :realmId and h.code = :code",
                        String.class)
                .setParameter("realmId", realmId)
                .setParameter("code", DEFAULT_HIERARCHY_CODE)
                .getResultStream()
                .findFirst()
                .orElseGet(this::createDefaultHierarchy);
    }

    private String createDefaultHierarchy() {
        HierarchyEntity entity = new HierarchyEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setRealmId(realmId);
        entity.setCode(DEFAULT_HIERARCHY_CODE);
        entity.setName("Default");
        em.persist(entity);
        em.flush();
        return entity.getId();
    }

    @Override
    public Optional<OrgUnit> findUnit(String id) {
        OrgUnitEntity entity = em.find(OrgUnitEntity.class, id);
        // The realm filter keeps one realm from reaching the rows of another by ID.
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(toDomain(entity)) : Optional.empty();
    }

    @Override
    public Optional<OrgUnit> findUnitByCode(String code) {
        return em.createQuery("select u from OrgUnitEntity u where u.realmId = :realmId and u.code = :code",
                        OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .getResultStream()
                .findFirst()
                .map(JpaOrgUnitStore::toDomain);
    }

    @Override
    public Optional<OrgUnit> findUnitByExternalId(String source, String externalId) {
        return em.createQuery("""
                        select u from OrgUnitEntity u where u.realmId = :realmId and u.source = :source
                        and u.externalId = :externalId""", OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("source", source)
                .setParameter("externalId", externalId)
                .getResultStream()
                .findFirst()
                .map(JpaOrgUnitStore::toDomain);
    }

    @Override
    public Optional<OrgUnit> findUnitByOrganizationId(String organizationId) {
        return em.createQuery("""
                        select u from OrgUnitEntity u where u.realmId = :realmId
                        and u.keycloakOrganizationId = :organizationId""", OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("organizationId", organizationId)
                .getResultStream()
                .findFirst()
                .map(JpaOrgUnitStore::toDomain);
    }

    @Override
    public void updateUnit(OrgUnit unit) {
        OrgUnitEntity entity = Rows.require(em, OrgUnitEntity.class, unit.id(), realmId, found -> found.realmId);
        entity.code = unit.code();
        entity.externalId = unit.externalId();
        entity.source = unit.source();
        entity.keycloakOrganizationId = unit.keycloakOrganizationId();
        entity.timeZone = unit.timeZone();
        entity.dayStart = unit.dayStart();
    }

    @Override
    public List<OrgUnit> units() {
        return em.createQuery("select u from OrgUnitEntity u where u.realmId = :realmId", OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<OrgUnitVersion> versionsOn(LocalDate date) {
        return em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.realmId = :realmId
                        and v.validFrom <= :date and (v.validTo is null or v.validTo > :date)""",
                        OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("date", date)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<OrgUnitLink> linksOn(String hierarchyId, LocalDate date) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId
                        and l.validFrom <= :date and (l.validTo is null or l.validTo > :date)""",
                        OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, LocalDate date, int first, int max) {
        var query = em.createQuery(rootId == null ? WHOLE_TREE : SUBTREE, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date);
        if (rootId != null) {
            query.setParameter("rootId", rootId);
        }
        return query.setFirstResult(first).setMaxResults(max).getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), (String) row[2]))
                .toList();
    }

    @Override
    public List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, LocalDate date, int first,
            int max) {
        var query = em.createQuery(parentId == null ? ROOTS : CHILDREN, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date);
        if (parentId != null) {
            query.setParameter("parentId", parentId);
        }
        return query.setFirstResult(first).setMaxResults(max).getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), parentId))
                .toList();
    }

    @Override
    public void insertUnit(OrgUnit unit) {
        OrgUnitEntity entity = new OrgUnitEntity();
        entity.id = unit.id();
        entity.realmId = realmId;
        entity.code = unit.code();
        entity.externalId = unit.externalId();
        entity.source = unit.source();
        entity.keycloakOrganizationId = unit.keycloakOrganizationId();
        entity.timeZone = unit.timeZone();
        entity.dayStart = unit.dayStart();
        em.persist(entity);
        // Other rows refer to this one. Hibernate groups inserts by entity type, so this row goes to the
        // database now, before a row that needs it.
        em.flush();
    }

    @Override
    public List<OrgUnitVersion> versions(String orgUnitId) {
        return em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.realmId = :realmId
                        and v.orgUnitId = :orgUnitId order by v.validFrom""", OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitId", orgUnitId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public void insertVersion(OrgUnitVersion version) {
        OrgUnitVersionEntity entity = new OrgUnitVersionEntity();
        entity.id = version.id();
        entity.realmId = realmId;
        entity.orgUnitId = version.orgUnitId();
        copy(version, entity);
        em.persist(entity);
    }

    @Override
    public void updateVersion(OrgUnitVersion version) {
        OrgUnitVersionEntity entity = Rows.require(em, OrgUnitVersionEntity.class, version.id(), realmId, found -> found.realmId);
        copy(version, entity);
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
    }

    private static void copy(OrgUnitVersion version, OrgUnitVersionEntity entity) {
        entity.validFrom = version.period().from();
        entity.validTo = version.period().to();
        entity.name = version.name();
        entity.names = version.names();
        entity.shortName = version.shortName();
        entity.shortNames = version.shortNames();
        entity.phoneticName = version.phoneticName();
        entity.unitTypeId = version.unitTypeId();
        entity.displayOrder = version.displayOrder();
        entity.attributes = version.attributes();
    }

    @Override
    public List<OrgUnitLink> parentLinks(String hierarchyId, String childId) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId and l.childId = :childId order by l.validFrom""",
                        OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("childId", childId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<OrgUnitLink> childLinks(String parentId) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.parentId = :parentId order by l.validFrom""", OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("parentId", parentId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    private static OrgUnitLink toDomain(OrgUnitLinkEntity e) {
        return new OrgUnitLink(e.id, e.hierarchyId, e.childId, e.parentId, new ValidPeriod(e.validFrom, e.validTo));
    }

    @Override
    public void insertLink(OrgUnitLink link) {
        OrgUnitLinkEntity entity = new OrgUnitLinkEntity();
        entity.id = link.id();
        entity.realmId = realmId;
        entity.hierarchyId = link.hierarchyId();
        entity.childId = link.childId();
        copy(link, entity);
        em.persist(entity);
    }

    @Override
    public void updateLink(OrgUnitLink link) {
        copy(link, findLink(link.id()));
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
    }

    @Override
    public void deleteLink(String id) {
        em.remove(findLink(id));
        em.flush();
    }

    private OrgUnitLinkEntity findLink(String id) {
        OrgUnitLinkEntity entity = Rows.require(em, OrgUnitLinkEntity.class, id, realmId, found -> found.realmId);
        return entity;
    }

    private static void copy(OrgUnitLink link, OrgUnitLinkEntity entity) {
        entity.parentId = link.parentId();
        entity.validFrom = link.period().from();
        entity.validTo = link.period().to();
    }

    private static OrgUnit toDomain(OrgUnitEntity e) {
        return new OrgUnit(e.id, e.code, e.externalId, e.source, e.keycloakOrganizationId, e.timeZone,
                e.dayStart);
    }

    private static OrgUnitVersion toDomain(OrgUnitVersionEntity e) {
        return new OrgUnitVersion(e.id, e.orgUnitId, new ValidPeriod(e.validFrom, e.validTo), e.name, e.names,
                e.shortName, e.shortNames, e.phoneticName, e.unitTypeId, e.displayOrder, e.attributes);
    }
}
