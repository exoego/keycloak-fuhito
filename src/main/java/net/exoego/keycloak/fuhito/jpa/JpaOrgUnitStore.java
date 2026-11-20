package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.Hierarchy;
import net.exoego.keycloak.fuhito.domain.Localized;
import net.exoego.keycloak.fuhito.domain.OrgUnit;
import net.exoego.keycloak.fuhito.domain.OrgUnitLink;
import net.exoego.keycloak.fuhito.domain.OrgUnitName;
import net.exoego.keycloak.fuhito.domain.OrgUnitSuccession;
import net.exoego.keycloak.fuhito.domain.OrgUnitVersion;
import net.exoego.keycloak.fuhito.domain.PlacedOrgUnit;
import net.exoego.keycloak.fuhito.domain.UnitDefaults;
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

    // A rollup unit is not in the default hierarchy.
    private static final String DEFAULT_ROOT = NO_PARENT + marked("rollupUnit", false);

    private static final String PLACED = """
            select u, v, (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)
            from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId
            where v.realmId = :realmId and %s and v.%s""".formatted(TreeQuery.onDate("l"),
            ManyIds.has("v.orgUnitId", "ids"), TreeQuery.onDate("v"));

    // The walks up look up each step with TreeQuery.parentOf. The last row of a walk has no unit. The first units
    // are found without the realm, for the reason in Rows.inRealm, so a walk checks the realm of its rows.
    private static final String ANCESTORS = """
            with Above as (
                select v.orgUnitId as unitId, v.orgUnitId as ancestorId, v.realmId as realmId
                from OrgUnitVersionEntity v
                where %2$s and v.%1$s
                union all
                select a.unitId as unitId, %3$s as ancestorId, a.realmId as realmId
                from Above a where a.ancestorId is not null
            )
            select a.unitId, a.ancestorId from Above a where a.realmId = :realmId and a.ancestorId is not null"""
            .formatted(TreeQuery.onDate("v"), ManyIds.has("v.orgUnitId", "ids"),
                    TreeQuery.parentOf("a.ancestorId", "hierarchyId"));

    // Each unit reads its own version and the versions above it, nearest first. Each step looks up the version of
    // one unit in the select, for the same reason as the parent.
    private static final String DEFAULTS = """
            with Above as (
                select v.orgUnitId as unitId, v.orgUnitId as ancestorId, 0 as depth, v.realmId as realmId
                from OrgUnitVersionEntity v
                where %2$s and v.%3$s
                union all
                select a.unitId as unitId, %4$s as ancestorId, a.depth + 1 as depth, a.realmId as realmId
                from Above a where a.ancestorId is not null
            )
            select a.unitId,
                (select w.workLocationId from OrgUnitVersionEntity w where w.orgUnitId = a.ancestorId and w.%1$s),
                (select w.costCenterId from OrgUnitVersionEntity w where w.orgUnitId = a.ancestorId and w.%1$s)
            from Above a where a.realmId = :realmId and a.ancestorId is not null
            order by a.unitId, a.depth""".formatted(TreeQuery.onDate("w"), ManyIds.has("v.orgUnitId", "ids"),
            TreeQuery.onDate("v"), TreeQuery.parentOf("a.ancestorId", "hierarchyId"));

    // The first units are found without the realm, for the reason in Rows.inRealm, so the walk checks the realm of
    // its rows.
    private static final String LEGAL_ENTITIES = "with " + TreeQuery.legalEntities("""
            from OrgUnitVersionEntity v where %s and v.%s""".formatted(ManyIds.has("v.orgUnitId", "ids"),
            TreeQuery.onDate("v")), "hierarchyId") + """
            select e.unitId, e.entityId from LegalEntities e where e.realmId = :realmId and e.entityId is not null""";

    // A path ends below a parent with no version on the date.
    private static final String PATHS = """
            with Above as (
                select v.orgUnitId as startId, v.orgUnitId as id, 0 as depth, v.realmId as realmId
                from OrgUnitVersionEntity v
                where %2$s and v.%1$s
                union all
                select a.startId as startId, %3$s as id, a.depth + 1 as depth, a.realmId as realmId
                from Above a where a.id is not null
            )
            select u, v, %4$s, a.startId
            from Above a
            join OrgUnitVersionEntity v on v.orgUnitId = a.id and v.%1$s
            join OrgUnitEntity u on u.id = a.id
            where a.realmId = :realmId and a.id is not null
            order by a.startId, a.depth desc""".formatted(TreeQuery.onDate("v"), ManyIds.has("v.orgUnitId", "ids"),
            TreeQuery.livingParentOf("a.id", "hierarchyId"), TreeQuery.parentOf("v.orgUnitId", "hierarchyId"));

    private static final String LINE = """
            with Above as (
                select u.id as id, 0 as depth, u.realmId as realmId
                from OrgUnitEntity u
                where u.id = :id
                union all
                select %s as id, a.depth + 1 as depth, a.realmId as realmId
                from Above a where a.id is not null
            )
            select a.id from Above a where a.realmId = :realmId and a.id is not null order by a.depth"""
            .formatted(TreeQuery.parentOf("a.id", "hierarchyId"));

    // The condition that keeps only the units whose unit type has a mark, only the others, or both, for the
    // version v. The mark is a field name in the code, never a value of a request.
    private static String marked(String mark, Boolean wanted) {
        if (wanted == null) {
            return "";
        }
        return (wanted ? " and" : " and not")
                + " exists (select 1 from RefItemEntity g where g.id = v.unitTypeId and g." + mark + " = true)";
    }

    private static String nature(Boolean lateral) {
        return marked("lateralUnit", lateral);
    }

    private static final String SIBLINGS = """
             from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId%%s
            where v.realmId = :realmId and v.%s and %%s%%s""".formatted(TreeQuery.onDate("v"));

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

    // The units are found by their IDs alone, for the reason in Rows.inRealm.
    @Override
    public List<OrgUnit> findUnits(Collection<String> ids) {
        return em.createQuery("select u from OrgUnitEntity u where %s".formatted(ManyIds.has("u.id", "ids")),
                        OrgUnitEntity.class)
                .setParameter("ids", ManyIds.of(ids))
                .getResultStream()
                .filter(Rows.inRealm(realmId, u -> u.realmId))
                .map(JpaOrgUnitStore::toDomain)
                .toList();
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
    public List<OrgUnitVersion> versionsWithCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return withNames(em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.realmId = :realmId and %s
                        order by v.validFrom, v.orgUnitId""".formatted(ManyIds.has("v.code", "codes")),
                        OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("codes", ManyIds.of(codes))
                .getResultList());
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
        entity.keycloakOrganizationId = unit.keycloakOrganizationId();
        entity.timeZone = unit.timeZone();
        entity.dayStart = unit.dayStart();
        entity.countUp();
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
        return withNames(em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.realmId = :realmId
                        and v.validFrom <= :date and (v.validTo is null or v.validTo > :date)""",
                        OrgUnitVersionEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("date", date)
                .getResultList());
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
    public List<PlacedOrgUnit> treeOn(String hierarchyId, String rootId, Boolean lateral, LocalDate date, int first,
            int max) {
        return placed(treeQuery(hierarchyId, rootId, lateral, date, false, Object[].class)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList(), row -> (String) row[2]);
    }

    @Override
    public long countTreeOn(String hierarchyId, String rootId, Boolean lateral, LocalDate date) {
        return treeQuery(hierarchyId, rootId, lateral, date, true, Long.class).getSingleResult();
    }

    private <T> TypedQuery<T> treeQuery(String hierarchyId, String rootId, Boolean lateral, LocalDate date,
            boolean count, Class<T> type) {
        return em.createQuery(TreeQuery.SUBTREE + (count ? "select count(v)" : "select u, v, t.parentId") + """
                         from Tree t
                        join OrgUnitEntity u on u.id = t.id
                        join OrgUnitVersionEntity v on v.id = t.versionId
                        where 1 = 1""" + nature(lateral) + (count ? "" : " order by t.position"), type)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date)
                .setParameter("rootId", rootId);
    }

    // The order of siblings, by the primary names p of the versions v.
    private static final String SIBLING_ORDER = " order by v.displayOrder, p.phoneticName nulls last, p.name,"
            + " v.orgUnitId";

    // The units after the unit of the cursor, in the order of siblings. A unit with no phonetic name comes after
    // the ones that have it, so the cursor of such a unit needs a text of its own.
    private static final String AFTER = """
             and (v.displayOrder > :afterOrder or v.displayOrder = :afterOrder and (p.phoneticName > :afterPhonetic
            or p.phoneticName is null or p.phoneticName = :afterPhonetic and (p.name > :afterName
            or p.name = :afterName and v.orgUnitId > :afterId)))""";

    private static final String AFTER_NO_PHONETIC = """
             and (v.displayOrder > :afterOrder or v.displayOrder = :afterOrder and p.phoneticName is null
            and (p.name > :afterName or p.name = :afterName and v.orgUnitId > :afterId))""";

    // Each piece is a fixed text. The values of the search go in as parameters.
    private static String searchText(OrgUnitStore.Search search, OrgUnitVersion after, boolean count) {
        StringBuilder text = new StringBuilder(count ? "select count(v)" : """
                select u, v, (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%s)""".formatted(
                TreeQuery.onDate("l")));
        text.append(" from OrgUnitVersionEntity v join OrgUnitEntity u on u.id = v.orgUnitId")
                .append(count ? "" : OrgUnitNames.JOIN_PRIMARY)
                .append(" where v.realmId = :realmId and v.").append(TreeQuery.onDate("v"));
        if (search.text() != null) {
            text.append("""
                     and exists (select 1 from OrgUnitVersionNameEntity n where n.versionId = v.id
                    and (lower(n.name) like :text escape '!' or lower(n.shortName) like :text escape '!'
                    or lower(n.phoneticName) like :text escape '!'))""");
        }
        if (search.code() != null) {
            text.append(" and v.code = :code");
        }
        if (search.unitTypeId() != null) {
            text.append(" and v.unitTypeId = :unitTypeId");
        }
        text.append(nature(search.lateral()));
        text.append(marked("rollupUnit", search.rollup()));
        text.append(marked("legalEntity", search.legalEntity()));
        if (search.roots() != null) {
            text.append(" and ").append(search.roots() == OrgUnitStore.Roots.LINKED ? LINKED_ROOT : DEFAULT_ROOT);
        }
        if (after != null) {
            text.append(after.names().primary().phoneticName() == null ? AFTER_NO_PHONETIC : AFTER);
        }
        if (!count) {
            text.append(SIBLING_ORDER);
        }
        return text.toString();
    }

    private <T> TypedQuery<T> searchQuery(String hierarchyId, OrgUnitStore.Search search, LocalDate date,
            OrgUnitVersion after, boolean count, Class<T> type) {
        TypedQuery<T> query = em.createQuery(searchText(search, after, count), type)
                .setParameter("realmId", realmId)
                .setParameter("date", date);
        if (!count || search.roots() != null) {
            query.setParameter("hierarchyId", hierarchyId);
        }
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
        if (after != null) {
            OrgUnitName names = after.names().primary();
            query.setParameter("afterOrder", after.displayOrder())
                    .setParameter("afterName", names.name())
                    .setParameter("afterId", after.orgUnitId());
            if (names.phoneticName() != null) {
                query.setParameter("afterPhonetic", names.phoneticName());
            }
        }
        return query;
    }

    @Override
    public List<PlacedOrgUnit> searchOn(String hierarchyId, OrgUnitStore.Search search, LocalDate date,
            OrgUnitVersion after, int first, int max) {
        return placed(searchQuery(hierarchyId, search, date, after, false, Object[].class)
                .setFirstResult(first).setMaxResults(max).getResultList(), row -> (String) row[2]);
    }

    @Override
    public long countSearchOn(String hierarchyId, OrgUnitStore.Search search, LocalDate date) {
        return searchQuery(hierarchyId, search, date, null, true, Long.class).getSingleResult();
    }

    // The employments are found by the employer alone and counted for each realm, for the reason in Rows.inRealm.
    @Override
    public long employmentsStartingIn(String employerId, LocalDate from, LocalDate to) {
        return countInRealm(em.createQuery("""
                        select e.realmId, count(e) from EmploymentEntity e where e.employerId = :employerId
                        and e.validFrom >= :from and e.validFrom < :to group by e.realmId""", Object[].class)
                .setParameter("employerId", employerId)
                .setParameter("from", from)
                .setParameter("to", to == null ? LocalDate.of(9999, 12, 31) : to));
    }

    @Override
    public long employmentsHoldingFrom(String employerId, LocalDate date) {
        return countInRealm(em.createQuery("""
                        select e.realmId, count(e) from EmploymentEntity e where e.employerId = :employerId
                        and (e.validTo is null or e.validTo > :date) group by e.realmId""", Object[].class)
                .setParameter("employerId", employerId)
                .setParameter("date", date));
    }

    private long countInRealm(TypedQuery<Object[]> counts) {
        return counts.getResultStream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .mapToLong(row -> (Long) row[1])
                .sum();
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
    public Map<String, UnitDefaults> defaultsOn(Collection<String> ids, LocalDate date) {
        Map<String, UnitDefaults> result = new HashMap<>();
        em.createQuery(DEFAULTS, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", defaultHierarchyId())
                .setParameter("ids", ManyIds.of(ids))
                .setParameter("date", date)
                .getResultStream()
                .filter(row -> row[1] != null || row[2] != null)
                .forEach(row -> result.merge((String) row[0], new UnitDefaults((String) row[1], (String) row[2]),
                        (near, far) -> new UnitDefaults(
                                near.workLocationId() == null ? far.workLocationId() : near.workLocationId(),
                                near.costCenterId() == null ? far.costCenterId() : near.costCenterId())));
        return result;
    }

    @Override
    public Map<String, String> legalEntitiesOn(Collection<String> ids, LocalDate date) {
        Map<String, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        em.createQuery(LEGAL_ENTITIES, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", defaultHierarchyId())
                .setParameter("ids", ManyIds.of(ids))
                .setParameter("date", date)
                .getResultStream()
                .forEach(row -> result.put((String) row[0], (String) row[1]));
        return result;
    }

    // The versions are found by their units alone, for the reason in Rows.inRealm.
    @Override
    public Map<String, ValidPeriod> livesOf(Collection<String> ids) {
        Map<String, ValidPeriod> lives = new HashMap<>();
        if (ids.isEmpty()) {
            return lives;
        }
        em.createQuery("""
                        select v.realmId, v.orgUnitId, v.validFrom, v.validTo from OrgUnitVersionEntity v
                        where %s order by v.validFrom""".formatted(ManyIds.has("v.orgUnitId", "ids")), Object[].class)
                .setParameter("ids", ManyIds.of(ids))
                .getResultStream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .forEach(row -> lives.merge((String) row[1], new ValidPeriod((LocalDate) row[2], (LocalDate) row[3]),
                        (first, later) -> new ValidPeriod(first.from(), later.to())));
        return lives;
    }

    @Override
    public Map<String, List<PlacedOrgUnit>> pathsOn(String hierarchyId, Collection<String> ids, LocalDate date) {
        List<Object[]> rows = em.createQuery(PATHS, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("ids", ManyIds.of(ids))
                .setParameter("date", date)
                .getResultList();
        List<PlacedOrgUnit> placed = placed(rows, row -> (String) row[2]);
        Map<String, List<PlacedOrgUnit>> paths = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            paths.computeIfAbsent((String) rows.get(i)[3], id -> new ArrayList<>()).add(placed.get(i));
        }
        return paths;
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
        return placed(em.createQuery(PLACED, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("ids", ManyIds.of(ids))
                .setParameter("date", date)
                .getResultList(), row -> (String) row[2]);
    }

    @Override
    public List<PlacedOrgUnit> childrenOn(String hierarchyId, String parentId, Boolean lateral, LocalDate date,
            int first, int max) {
        return placed(childrenQuery(hierarchyId, parentId, lateral, date, false, Object[].class)
                .setFirstResult(first).setMaxResults(max).getResultList(), row -> parentId);
    }

    @Override
    public long countChildrenOn(String hierarchyId, String parentId, Boolean lateral, LocalDate date) {
        return childrenQuery(hierarchyId, parentId, lateral, date, true, Long.class).getSingleResult();
    }

    private <T> TypedQuery<T> childrenQuery(String hierarchyId, String parentId, Boolean lateral, LocalDate date,
            boolean count, Class<T> type) {
        return em.createQuery((count ? "select count(v)" : "select u, v") + SIBLINGS.formatted(
                        count ? "" : OrgUnitNames.JOIN_PRIMARY, CHILD_OF, nature(lateral))
                        + (count ? "" : SIBLING_ORDER), type)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("date", date)
                .setParameter("parentId", parentId);
    }

    @Override
    public void insertUnit(OrgUnit unit) {
        insertUnits(List.of(unit));
    }

    @Override
    public void insertUnits(List<OrgUnit> units) {
        if (units.isEmpty()) {
            return;
        }
        for (OrgUnit unit : units) {
            OrgUnitEntity entity = new OrgUnitEntity();
            entity.id = unit.id();
            entity.realmId = realmId;
            entity.source = unit.source();
            entity.keycloakOrganizationId = unit.keycloakOrganizationId();
            entity.timeZone = unit.timeZone();
            entity.dayStart = unit.dayStart();
            entity.version = unit.version();
            entity.counted = true;
            em.persist(entity);
        }
        // Other rows refer to these. Hibernate groups inserts by entity type, so these rows go to the
        // database now, before a row that needs them.
        em.flush();
    }

    @Override
    public List<OrgUnitVersion> versions(String orgUnitId) {
        return withNames(em.createQuery("""
                        select v from OrgUnitVersionEntity v where v.orgUnitId = :orgUnitId
                        order by v.validFrom""", OrgUnitVersionEntity.class)
                .setParameter("orgUnitId", orgUnitId)
                .getResultStream()
                .filter(Rows.inRealm(realmId, v -> v.realmId))
                .toList());
    }

    // The versions are found by their units alone, for the reason in Rows.inRealm.
    @Override
    public Map<String, List<OrgUnitVersion>> versionsOf(Collection<String> orgUnitIds) {
        Map<String, List<OrgUnitVersion>> found = new HashMap<>();
        withNames(em.createQuery("""
                        select v from OrgUnitVersionEntity v where %s
                        order by v.validFrom""".formatted(ManyIds.has("v.orgUnitId", "ids")),
                        OrgUnitVersionEntity.class)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .getResultStream()
                .filter(Rows.inRealm(realmId, v -> v.realmId))
                .toList())
                .forEach(version -> found.computeIfAbsent(version.orgUnitId(), id -> new ArrayList<>()).add(version));
        return found;
    }

    @Override
    public void insertVersion(OrgUnitVersion version) {
        OrgUnitVersionEntity entity = new OrgUnitVersionEntity();
        entity.id = version.id();
        entity.realmId = realmId;
        entity.orgUnitId = version.orgUnitId();
        copy(version, entity);
        em.persist(entity);
        OrgUnitNames.insert(em, realmId, version.id(), version.names());
        Versions.orgUnit(em, realmId, version.orgUnitId());
    }

    @Override
    public void updateVersion(OrgUnitVersion version) {
        OrgUnitVersionEntity entity = Rows.require(em, OrgUnitVersionEntity.class, version.id(), realmId, found -> found.realmId);
        copy(version, entity);
        OrgUnitNames.replace(em, realmId, version.id(), version.names());
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
        Versions.orgUnit(em, realmId, version.orgUnitId());
    }

    private static void copy(OrgUnitVersion version, OrgUnitVersionEntity entity) {
        entity.validFrom = version.period().from();
        entity.validTo = version.period().to();
        entity.code = version.code();
        entity.unitTypeId = version.unitTypeId();
        entity.workLocationId = version.workLocationId();
        entity.costCenterId = version.costCenterId();
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

    // The links are found by the hierarchy and their units, for the reason in Rows.inRealm.
    @Override
    public Map<String, List<OrgUnitLink>> parentLinksOf(String hierarchyId, Collection<String> childIds) {
        Map<String, List<OrgUnitLink>> found = new HashMap<>();
        em.createQuery("""
                        select l from OrgUnitLinkEntity l where l.hierarchyId = :hierarchyId and %s
                        order by l.validFrom""".formatted(ManyIds.has("l.childId", "ids")), OrgUnitLinkEntity.class)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("ids", ManyIds.of(childIds))
                .getResultStream()
                .filter(Rows.inRealm(realmId, l -> l.realmId))
                .map(JpaOrgUnitStore::toDomain)
                .forEach(link -> found.computeIfAbsent(link.childId(), id -> new ArrayList<>()).add(link));
        return found;
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
        Versions.orgUnit(em, realmId, link.childId());
    }

    @Override
    public void updateLink(OrgUnitLink link) {
        OrgUnitLinkEntity entity = findLink(link.id());
        copy(link, entity);
        // A split shortens this row and then inserts the next one. The database must see the shorter row first.
        em.flush();
        Versions.orgUnit(em, realmId, entity.childId);
    }

    @Override
    public void deleteLink(String id) {
        OrgUnitLinkEntity entity = findLink(id);
        em.remove(entity);
        em.flush();
        Versions.orgUnit(em, realmId, entity.childId);
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
        return new OrgUnit(e.id, e.source, e.keycloakOrganizationId, e.timeZone, e.dayStart, e.version);
    }

    private List<OrgUnitVersion> withNames(List<OrgUnitVersionEntity> entities) {
        return withNames(em, realmId, entities);
    }

    /**
     * Reads the names of versions in one query, and gives each version its names.
     *
     * @param em the entity manager
     * @param realmId the ID of the realm
     * @param entities the rows of the versions
     * @return the versions in the same order
     */
    static List<OrgUnitVersion> withNames(EntityManager em, String realmId, List<OrgUnitVersionEntity> entities) {
        Map<String, Localized<OrgUnitName>> names = OrgUnitNames.of(em, realmId,
                entities.stream().map(e -> e.id).toList());
        return entities.stream().map(e -> toDomain(e, names.get(e.id))).toList();
    }

    // Each row has the unit and the version.
    private List<PlacedOrgUnit> placed(List<Object[]> rows, Function<Object[], String> parentId) {
        List<OrgUnitVersion> versions = withNames(rows.stream().map(row -> (OrgUnitVersionEntity) row[1]).toList());
        return IntStream.range(0, rows.size())
                .mapToObj(i -> new PlacedOrgUnit(toDomain((OrgUnitEntity) rows.get(i)[0]), versions.get(i),
                        parentId.apply(rows.get(i))))
                .toList();
    }

    private static OrgUnitVersion toDomain(OrgUnitVersionEntity e, Localized<OrgUnitName> names) {
        return new OrgUnitVersion(e.id, e.orgUnitId, new ValidPeriod(e.validFrom, e.validTo), e.code, names,
                e.unitTypeId, e.workLocationId, e.costCenterId, e.displayOrder, e.attributes);
    }
}
