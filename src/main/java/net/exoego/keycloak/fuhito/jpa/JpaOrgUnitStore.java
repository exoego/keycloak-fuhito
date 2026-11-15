package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.OrgUnitStore;

/**
 * Stores the org units of one realm in the database of Keycloak.
 */
public class JpaOrgUnitStore implements OrgUnitStore {

    private static final String NO_PARENT = """
            not exists (select 1 from OrgUnitLinkEntity l where l.realmId = :realmId
            and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)""".formatted(TreeQuery.onDate("l"));

    // A hierarchy other than the default one has only the units that are linked in it.
    private static final String LINKED_ROOT = NO_PARENT + """
             and exists (select 1 from OrgUnitLinkEntity c where c.realmId = :realmId
            and c.hierarchyId = :hierarchyId and c.parentId = v.orgUnitId and c.%s)""".formatted(TreeQuery.onDate("c"));

    private static final String PLACED = """
            select u, v, (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)
            from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId
            where v.realmId = :realmId and %s and v.%s""".formatted(TreeQuery.onDate("l"),
            ManyIds.has("v.orgUnitId", "ids"), TreeQuery.onDate("v"));

    private static final String LEGAL_ENTITIES = """
            select u, v, (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)
            from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId
            where v.realmId = :realmId and v.%s and v.unitTypeId in
                (select r.id from RefItemEntity r where r.realmId = :realmId and r.legalEntity = true)
            order by v.displayOrder, v.phoneticName nulls last, v.name, v.orgUnitId""".formatted(
            TreeQuery.onDate("l"), TreeQuery.onDate("v"));

    private static final String ANCESTORS = """
            with Above as (
                select v.orgUnitId as unitId, v.orgUnitId as ancestorId
                from OrgUnitVersionEntity v
                where v.realmId = :realmId and %3$s and v.%1$s
                union all
                select a.unitId as unitId, l.parentId as ancestorId
                from Above a
                join OrgUnitLinkEntity l on l.childId = a.ancestorId and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%2$s
            )
            select a.unitId, a.ancestorId from Above a""".formatted(TreeQuery.onDate("v"), TreeQuery.onDate("l"),
            ManyIds.has("v.orgUnitId", "ids"));

    private static final String PATH = """
            with Above as (
                select v.orgUnitId as id, v.id as versionId, 0 as depth
                from OrgUnitVersionEntity v
                where v.realmId = :realmId and v.orgUnitId = :id and v.%1$s
                union all
                select p.orgUnitId as id, p.id as versionId, a.depth + 1 as depth
                from Above a
                join OrgUnitLinkEntity l on l.childId = a.id and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%2$s
                join OrgUnitVersionEntity p on p.orgUnitId = l.parentId and p.realmId = :realmId and p.%3$s
            )
            select u, v, (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%2$s)
            from Above a
            join OrgUnitVersionEntity v on v.id = a.versionId
            join OrgUnitEntity u on u.id = a.id
            order by a.depth desc""".formatted(TreeQuery.onDate("v"), TreeQuery.onDate("l"), TreeQuery.onDate("p"));

    private static final String LINE = """
            with Above as (
                select u.id as id, 0 as depth
                from OrgUnitEntity u
                where u.realmId = :realmId and u.id = :id
                union all
                select l.parentId as id, a.depth + 1 as depth
                from Above a
                join OrgUnitLinkEntity l on l.childId = a.id and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%s
            )
            select a.id from Above a order by a.depth""".formatted(TreeQuery.onDate("l"));

    // The condition that keeps only groups, only line units, or both, for the version v.
    private static String nature(Boolean group) {
        if (group == null) {
            return "";
        }
        return """
                 and %s exists (select 1 from RefItemEntity g where g.id = v.unitTypeId and g.groupUnit = true)"""
                .formatted(group ? "" : "not");
    }

    private static final String SIBLINGS = """
            select u, v from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId
            where v.realmId = :realmId and v.%s and %%s%%s
            order by v.displayOrder, v.phoneticName nulls last, v.name, v.orgUnitId""".formatted(TreeQuery.onDate("v"));

    private static final String CHILD_OF = """
            exists (select 1 from OrgUnitLinkEntity l where l.realmId = :realmId
            and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.parentId = :parentId
            and l.%s)""".formatted(TreeQuery.onDate("l"));

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
        return new JpaHierarchyStore(em, realmId).defaultId();
    }

    @Override
    public Map<String, String> hierarchyCodes() {
        return new JpaHierarchyStore(em, realmId).list(0, Integer.MAX_VALUE).stream()
                .collect(Collectors.toMap(Hierarchy::id, Hierarchy::code));
    }

    @Override
    public Optional<OrgUnit> findUnit(String id) {
        OrgUnitEntity entity = em.find(OrgUnitEntity.class, id);
        // The realm filter keeps one realm from reaching the rows of another by ID.
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(toDomain(entity)) : Optional.empty();
    }

    @Override
    public Optional<OrgUnit> findUnitByCode(String code, LocalDate date) {
        return em.createQuery("""
                        select u from OrgUnitEntity u join OrgUnitVersionEntity v on v.orgUnitId = u.id
                        where u.realmId = :realmId and v.realmId = :realmId and v.code = :code and v.%s"""
                        .formatted(TreeQuery.onDate("v")), OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .setParameter("date", date)
                .getResultStream()
                .findFirst()
                .map(JpaOrgUnitStore::toDomain);
    }

    @Override
    public List<OrgUnitVersion> versionsWithCode(String code) {
        return em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.realmId = :realmId and v.code = :code
                        order by v.validFrom, v.orgUnitId""", OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("code", code)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
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
    public List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, Boolean group, LocalDate date, int first,
            int max) {
        return em.createQuery(TreeQuery.SUBTREE + """
                        select u, v, t.parentId from Tree t
                        join OrgUnitEntity u on u.id = t.id
                        join OrgUnitVersionEntity v on v.id = t.versionId
                        where 1 = 1%s
                        order by t.position""".formatted(nature(group)), Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date)
                .setParameter("rootId", rootId)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), (String) row[2]))
                .toList();
    }

    // Each piece is a fixed text. The values of the search go in as parameters.
    private static String searchText(OrgUnitStore.Search search, boolean count) {
        StringBuilder text = new StringBuilder(count ? "select count(v)" : """
                select u, v, (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)""".formatted(
                TreeQuery.onDate("l")));
        text.append("""
                 from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId
                where v.realmId = :realmId and v.""").append(TreeQuery.onDate("v"));
        if (search.text() != null) {
            text.append("""
                     and (lower(v.name) like :text escape '!' or lower(v.shortName) like :text escape '!'
                    or lower(v.phoneticName) like :text escape '!' or v.searchNames like :text escape '!')""");
        }
        if (search.code() != null) {
            text.append(" and v.code = :code");
        }
        if (search.unitTypeId() != null) {
            text.append(" and v.unitTypeId = :unitTypeId");
        }
        text.append(nature(search.group()));
        if (!count) {
            text.append(" order by v.displayOrder, v.phoneticName nulls last, v.name, v.orgUnitId");
        }
        return text.toString();
    }

    private static void bind(TypedQuery<?> query, OrgUnitStore.Search search) {
        if (search.text() != null) {
            String escaped = search.text().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_");
            query.setParameter("text", "%" + escaped + "%");
        }
        if (search.code() != null) {
            query.setParameter("code", search.code());
        }
        if (search.unitTypeId() != null) {
            query.setParameter("unitTypeId", search.unitTypeId());
        }
    }

    @Override
    public List<PlacedOrgUnit> searchOn(String hierarchyId, OrgUnitStore.Search search, LocalDate date, int first,
            int max) {
        TypedQuery<Object[]> query = em.createQuery(searchText(search, false), Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date);
        bind(query, search);
        return query.setFirstResult(first).setMaxResults(max).getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), (String) row[2]))
                .toList();
    }

    @Override
    public long countSearchOn(OrgUnitStore.Search search, LocalDate date) {
        TypedQuery<Long> query = em.createQuery(searchText(search, true), Long.class)
                .setParameter("realmId", realmId)
                .setParameter("date", date);
        bind(query, search);
        return query.getSingleResult();
    }

    @Override
    public List<PlacedOrgUnit> legalEntitiesOn(String hierarchyId, LocalDate date, int first, int max) {
        return em.createQuery(LEGAL_ENTITIES, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), (String) row[2]))
                .toList();
    }

    @Override
    public long employmentsStartingIn(String employerId, LocalDate from, LocalDate to) {
        return em.createQuery("""
                        select count(e) from EmploymentEntity e where e.realmId = :realmId
                        and e.employerId = :employerId and e.validFrom >= :from and e.validFrom < :to""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("employerId", employerId)
                .setParameter("from", from)
                .setParameter("to", to == null ? LocalDate.of(9999, 12, 31) : to)
                .getSingleResult();
    }

    @Override
    public long employmentsHoldingFrom(String employerId, LocalDate date) {
        return em.createQuery("""
                        select count(e) from EmploymentEntity e where e.realmId = :realmId
                        and e.employerId = :employerId and (e.validTo is null or e.validTo > :date)""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("employerId", employerId)
                .setParameter("date", date)
                .getSingleResult();
    }

    @Override
    public long unitRolesHoldingFrom(String orgUnitId, LocalDate date) {
        return em.createQuery("""
                        select count(r) from UnitRoleEntity r where r.realmId = :realmId
                        and r.orgUnitId = :orgUnitId and (r.validTo is null or r.validTo > :date)""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("orgUnitId", orgUnitId)
                .setParameter("date", date)
                .getSingleResult();
    }

    @Override
    public Map<String, Set<String>> ancestorsOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        Map<String, Set<String>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        em.createQuery(ANCESTORS, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("ids", ManyIds.of(ids))
                .setParameter("date", date)
                .getResultStream()
                .forEach(row -> result.computeIfAbsent((String) row[0], id -> new HashSet<>()).add((String) row[1]));
        return result;
    }

    @Override
    public List<PlacedOrgUnit> pathOn(String hierarchyId, String id, LocalDate date) {
        return em.createQuery(PATH, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("id", id)
                .setParameter("date", date)
                .getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), (String) row[2]))
                .toList();
    }

    @Override
    public List<OrgUnit> unitsWithOwnTime() {
        return em.createQuery("""
                        select u from OrgUnitEntity u where u.realmId = :realmId
                        and (u.timeZone is not null or u.dayStart is not null)""", OrgUnitEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<String> lineOn(String hierarchyId, String id, LocalDate date) {
        return em.createQuery(LINE, String.class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("id", id)
                .setParameter("date", date)
                .getResultList();
    }

    @Override
    public List<PlacedOrgUnit> placedOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery(PLACED, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("ids", ManyIds.of(ids))
                .setParameter("date", date)
                .getResultStream()
                .map(row -> new PlacedOrgUnit(toDomain((OrgUnitEntity) row[0]),
                        toDomain((OrgUnitVersionEntity) row[1]), (String) row[2]))
                .toList();
    }

    @Override
    public List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, boolean linkedOnly, Boolean group,
            LocalDate date, int first, int max) {
        String place = parentId != null ? CHILD_OF : linkedOnly ? LINKED_ROOT : NO_PARENT;
        var query = em.createQuery(SIBLINGS.formatted(place, nature(group)), Object[].class)
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
        entity.code = version.code();
        entity.name = version.name();
        entity.names = version.names();
        entity.shortName = version.shortName();
        entity.shortNames = version.shortNames();
        entity.phoneticName = version.phoneticName();
        entity.searchNames = Stream.of(version.names(), version.shortNames())
                .filter(Objects::nonNull)
                .flatMap(names -> names.values().stream())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.joining("\n"));
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
    public List<OrgUnitLink> childLinks(String hierarchyId, String parentId) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId and l.parentId = :parentId order by l.validFrom, l.childId""",
                        OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("parentId", parentId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public List<OrgUnitLink> parentLinks(String childId) {
        return em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.childId = :childId order by l.validFrom, l.hierarchyId""", OrgUnitLinkEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("childId", childId)
                .getResultStream()
                .map(JpaOrgUnitStore::toDomain)
                .toList();
    }

    @Override
    public long childrenHoldingFrom(String parentId, LocalDate date) {
        return em.createQuery("""
                        select count(distinct l.childId) from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.parentId = :parentId and (l.validTo is null or l.validTo > :date)""", Long.class)
                .setParameter("realmId", realmId)
                .setParameter("parentId", parentId)
                .setParameter("date", date)
                .getSingleResult();
    }

    static OrgUnitLink toDomain(OrgUnitLinkEntity e) {
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

    @Override
    public void insertSuccession(OrgUnitSuccession succession) {
        OrgUnitSuccessionEntity entity = new OrgUnitSuccessionEntity();
        entity.id = succession.id();
        entity.realmId = realmId;
        entity.predecessorId = succession.predecessorId();
        entity.successorId = succession.successorId();
        entity.effectiveDate = succession.effectiveDate();
        em.persist(entity);
    }

    @Override
    public List<OrgUnitSuccession> successions(String orgUnitId) {
        return em.createQuery("""
                        select s from OrgUnitSuccessionEntity s where s.realmId = :realmId
                        and (s.predecessorId = :id or s.successorId = :id)
                        order by s.effectiveDate, s.predecessorId, s.successorId""", OrgUnitSuccessionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("id", orgUnitId)
                .getResultStream()
                .map(e -> new OrgUnitSuccession(e.id, e.predecessorId, e.successorId, e.effectiveDate))
                .toList();
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

    static OrgUnit toDomain(OrgUnitEntity e) {
        return new OrgUnit(e.id, e.externalId, e.source, e.keycloakOrganizationId, e.timeZone,
                e.dayStart);
    }

    static OrgUnitVersion toDomain(OrgUnitVersionEntity e) {
        return new OrgUnitVersion(e.id, e.orgUnitId, new ValidPeriod(e.validFrom, e.validTo), e.code, e.name, e.names,
                e.shortName, e.shortNames, e.phoneticName, e.unitTypeId, e.displayOrder, e.attributes);
    }
}
