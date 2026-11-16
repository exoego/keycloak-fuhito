package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
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

    private static final String WITH_PERSON = """
            select r, e.personId, p.userId from UnitRoleEntity r
            join EmploymentEntity e on e.id = r.employmentId
            join PersonEntity p on p.id = e.personId
            where r.realmId = :realmId and %s""";

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
            select r, e.personId, p.userId from Above a
            join UnitRoleEntity r on r.orgUnitId = a.id and r.realmId = :realmId and r.%2$s
            join EmploymentEntity e on e.id = r.employmentId
            join PersonEntity p on p.id = e.personId
            join RefItemEntity k on k.id = r.roleId
            where (a.depth = 0 or r.includesDescendants = true)%%s%%s
            order by a.depth, k.code, r.id""".formatted(TreeQuery.onDate("l"), TreeQuery.onDate("r"));

    // A role is left out when a nearer unit has a holder of the same role over the unit.
    private static final String NEAREST = """
             and not exists (select 1 from Above b
                join UnitRoleEntity o on o.orgUnitId = b.id and o.realmId = :realmId and o.%s
                where o.roleId = r.roleId and b.depth < a.depth and (b.depth = 0 or o.includesDescendants = true))"""
            .formatted(TreeQuery.onDate("o"));

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
    public List<UnitRole> holdersOn(String hierarchyId, String orgUnitId, String roleId, boolean nearest,
            LocalDate date, int first, int max) {
        TypedQuery<Object[]> query = em.createQuery(HOLDERS.formatted(roleId == null ? "" : " and r.roleId = :roleId",
                        nearest ? NEAREST : ""), Object[].class)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("orgUnitId", orgUnitId)
                .setParameter("date", date);
        if (roleId != null) {
            query.setParameter("roleId", roleId);
        }
        return list(query.setFirstResult(first).setMaxResults(max));
    }

    @Override
    public List<UnitRole> ofPersonOn(String personId, LocalDate date, int first, int max) {
        return list(em.createQuery(WITH_PERSON.formatted("e.personId = :personId and r.%s order by r.validFrom, r.id"
                        .formatted(TreeQuery.onDate("r"))), Object[].class)
                .setParameter("personId", personId)
                .setParameter("date", date)
                .setFirstResult(first)
                .setMaxResults(max));
    }

    private List<UnitRole> list(TypedQuery<Object[]> query) {
        return query.setParameter("realmId", realmId)
                .getResultStream()
                .map(row -> {
                    UnitRoleEntity e = (UnitRoleEntity) row[0];
                    return new UnitRole(e.id, e.employmentId, (String) row[1], (String) row[2], e.orgUnitId, e.roleId,
                            e.includesDescendants, new ValidPeriod(e.validFrom, e.validTo));
                })
                .toList();
    }

    @Override
    public List<UnitRole> listOn(LocalDate date, String afterId, int max) {
        return list(em.createQuery(WITH_PERSON.formatted("""
                        r.validFrom <= :date and (r.validTo is null or r.validTo > :date) and r.id > :after
                        order by r.id"""), Object[].class)
                .setParameter("date", date)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max));
    }

    @Override
    public void insert(UnitRole role) {
        UnitRoleEntity entity = new UnitRoleEntity();
        entity.id = role.id();
        entity.realmId = realmId;
        entity.employmentId = role.employmentId();
        entity.orgUnitId = role.orgUnitId();
        entity.roleId = role.roleId();
        entity.includesDescendants = role.includesDescendants();
        entity.validFrom = role.period().from();
        entity.validTo = role.period().to();
        em.persist(entity);
        em.flush();
    }

    @Override
    public void update(UnitRole role) {
        UnitRoleEntity entity = Rows.require(em, UnitRoleEntity.class, role.id(), realmId, found -> found.realmId);
        entity.validFrom = role.period().from();
        entity.validTo = role.period().to();
        em.flush();
    }

    @Override
    public void delete(String id) {
        UnitRoleEntity entity = em.find(UnitRoleEntity.class, id);
        if (entity != null && entity.realmId.equals(realmId)) {
            em.remove(entity);
            em.flush();
        }
    }
}
