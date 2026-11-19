package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.UnitRole;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.UnitRoleStore;

/**
 * Stores the roles over org units of one realm in the database of Keycloak.
 */
public class JpaUnitRoleStore implements UnitRoleStore {

    private static final String FROM_PERSON = """
             from UnitRoleEntity r
            join EmploymentEntity e on e.id = r.employmentId
            join PersonEntity p on p.id = e.personId
            where r.realmId = :realmId and %s""";

    private static final String WITH_PERSON = "select r, e.personId, p.userId" + FROM_PERSON;

    // The database goes up from the unit, so a read does not load the tree.
    private static final String HOLDERS = """
            with Above as (
                select u.id as id, 0 as depth
                from OrgUnitEntity u
                where u.realmId = :realmId and u.id = :orgUnitId
                union all
                select l.parentId as id, a.depth + 1 as depth
                from Above a
                join OrgUnitLinkEntity l on l.childId = a.id and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%1$s
            )
            select %%s from Above a
            join UnitRoleEntity r on r.orgUnitId = a.id and r.realmId = :realmId and r.%2$s
            join EmploymentEntity e on e.id = r.employmentId
            join PersonEntity p on p.id = e.personId
            join RefItemEntity k on k.id = r.roleId
            where (a.depth = 0 or r.includesDescendants = true)%%s%%s""".formatted(TreeQuery.onDate("l"),
            TreeQuery.onDate("r"));

    // A role is left out when a nearer unit has a holder of the same role over the unit.
    private static final String NEAREST = " and not exists (select 1 from Above b"
            + " join UnitRoleEntity o on o.orgUnitId = b.id and o.realmId = :realmId and o." + TreeQuery.onDate("o")
            + " where o.roleId = r.roleId and b.depth < a.depth and (b.depth = 0 or o.includesDescendants = true))";

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaUnitRoleStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<UnitRole> find(String id) {
        return list(em.createQuery(WITH_PERSON.formatted("r.id = :id"), Object[].class).setParameter("id", id))
                .stream().findFirst();
    }

    @Override
    public List<UnitRole> findByEmployment(String employmentId) {
        return list(em.createQuery(WITH_PERSON.formatted("r.employmentId = :employmentId order by r.validFrom, r.id"),
                Object[].class).setParameter("employmentId", employmentId));
    }

    @Override
    public List<UnitRole> findByEmployments(Collection<String> employmentIds) {
        return list(em.createQuery(WITH_PERSON.formatted(ManyIds.has("r.employmentId", "employmentIds")
                + " order by r.employmentId, r.validFrom, r.id"), Object[].class)
                .setParameter("employmentIds", ManyIds.of(employmentIds)));
    }

    @Override
    public List<UnitRole> holdersOn(String hierarchyId, String orgUnitId, String roleId, boolean nearest,
            LocalDate date, int first, int max) {
        return list(holdersQuery(hierarchyId, orgUnitId, roleId, nearest, date, false, Object[].class)
                .setFirstResult(first).setMaxResults(max));
    }

    @Override
    public long countHoldersOn(String hierarchyId, String orgUnitId, String roleId, boolean nearest,
            LocalDate date) {
        return holdersQuery(hierarchyId, orgUnitId, roleId, nearest, date, true, Long.class)
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    private <T> TypedQuery<T> holdersQuery(String hierarchyId, String orgUnitId, String roleId, boolean nearest,
            LocalDate date, boolean count, Class<T> type) {
        TypedQuery<T> query = em.createQuery(HOLDERS.formatted(count ? "count(r)" : "r, e.personId, p.userId",
                        roleId == null ? "" : " and r.roleId = :roleId", nearest ? NEAREST : "")
                        + (count ? "" : " order by a.depth, k.code, r.id"), type)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("orgUnitId", orgUnitId)
                .setParameter("date", date);
        if (roleId != null) {
            query.setParameter("roleId", roleId);
        }
        return query;
    }

    @Override
    public List<UnitRole> ofPersonOn(String personId, LocalDate date, int first, int max) {
        return list(ofPersonQuery(WITH_PERSON, " order by r.validFrom, r.id", personId, date, Object[].class)
                .setFirstResult(first)
                .setMaxResults(max));
    }

    @Override
    public long countOfPersonOn(String personId, LocalDate date) {
        return ofPersonQuery("select count(r)" + FROM_PERSON, "", personId, date, Long.class)
                .setParameter("realmId", realmId)
                .getSingleResult();
    }

    private <T> TypedQuery<T> ofPersonQuery(String select, String order, String personId, LocalDate date,
            Class<T> type) {
        return em.createQuery(select.formatted("e.personId = :personId and r." + TreeQuery.onDate("r") + order),
                        type)
                .setParameter("personId", personId)
                .setParameter("date", date);
    }

    private List<UnitRole> list(TypedQuery<Object[]> query) {
        return query.setParameter("realmId", realmId)
                .getResultStream()
                .map(row -> {
                    UnitRoleEntity e = (UnitRoleEntity) row[0];
                    return new UnitRole(e.id, e.employmentId, (String) row[1], (String) row[2], e.orgUnitId, e.roleId,
                            e.includesDescendants, new ValidPeriod(e.validFrom, e.validTo), e.version);
                })
                .toList();
    }

    // Each piece is a fixed text. The values of the search go in as parameters.
    private <T> TypedQuery<T> searchQuery(String select, UnitRoleStore.Search search, String afterId,
            Class<T> type) {
        StringBuilder where = new StringBuilder("r.").append(TreeQuery.onDate("r"));
        if (search.roleId() != null) {
            where.append(" and r.roleId = :roleId");
        }
        if (search.orgUnitId() != null) {
            where.append(" and r.orgUnitId = :orgUnitId");
        }
        if (afterId != null) {
            where.append(" and r.id > :afterId");
        }
        TypedQuery<T> query = em.createQuery(select.formatted(where), type)
                .setParameter("realmId", realmId)
                .setParameter("date", search.date());
        if (search.roleId() != null) {
            query.setParameter("roleId", search.roleId());
        }
        if (search.orgUnitId() != null) {
            query.setParameter("orgUnitId", search.orgUnitId());
        }
        if (afterId != null) {
            query.setParameter("afterId", afterId);
        }
        return query;
    }

    @Override
    public List<UnitRole> searchOn(UnitRoleStore.Search search, String afterId, int first, int max) {
        return list(searchQuery(WITH_PERSON + " order by r.id", search, afterId, Object[].class)
                .setFirstResult(first)
                .setMaxResults(max));
    }

    @Override
    public long countSearchOn(UnitRoleStore.Search search) {
        return searchQuery("select count(r) from UnitRoleEntity r where r.realmId = :realmId and %s", search, null,
                Long.class).getSingleResult();
    }

    @Override
    public void insertAll(List<UnitRole> roles) {
        for (UnitRole role : roles) {
            UnitRoleEntity entity = new UnitRoleEntity();
            entity.id = role.id();
            entity.realmId = realmId;
            entity.employmentId = role.employmentId();
            entity.orgUnitId = role.orgUnitId();
            entity.roleId = role.roleId();
            entity.includesDescendants = role.includesDescendants();
            entity.validFrom = role.period().from();
            entity.validTo = role.period().to();
            entity.version = role.version();
            entity.counted = true;
            em.persist(entity);
        }
        em.flush();
    }

    @Override
    public void updateAll(List<UnitRole> roles) {
        for (UnitRole role : roles) {
            UnitRoleEntity entity = Rows.require(em, UnitRoleEntity.class, role.id(), realmId,
                    found -> found.realmId);
            entity.validFrom = role.period().from();
            entity.validTo = role.period().to();
            entity.countUp();
        }
    }

    @Override
    public void deleteAll(Collection<String> ids) {
        for (String id : ids) {
            UnitRoleEntity entity = em.find(UnitRoleEntity.class, id);
            if (entity != null && entity.realmId.equals(realmId)) {
                ExternalIds.deleteOf(em, realmId, "UNIT_ROLE", id);
                em.remove(entity);
            }
        }
        em.flush();
    }
}
