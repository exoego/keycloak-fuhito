package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.DirectReport;
import net.exoego.keycloak.fuhito.domain.ReportingLine;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.ReportingLineStore;

/**
 * Stores the reporting lines of one realm in the database of Keycloak.
 */
public class JpaReportingLineStore implements ReportingLineStore {

    private static String onDate(String alias) {
        return "validFrom <= :date and (%1$s.validTo is null or %1$s.validTo > :date)".formatted(alias);
    }

    private static String present(String row, String t) {
        return """
                exists (select 1 from EmploymentTermsEntity %2$s where %2$s.realmId = :realmId
                    and %2$s.employmentId = %1$s.employmentId and %2$s.status = 'ACTIVE' and %2$s.%3$s)"""
                .formatted(row, t, onDate(t));
    }

    private static String acting(String row, String k) {
        return "exists (select 1 from RefItemEntity %2$s where %2$s.id = %1$s.kindId and %2$s.actingHead = true)"
                .formatted(row, k);
    }

    // True when the unit of the row has a present head that is not acting. Each use gets its own aliases.
    private static String regularPresent(String row, String prefix) {
        String r = prefix + "r";
        return """
                exists (select 1 from AssignmentEntity %2$s where %2$s.realmId = :realmId
                    and %2$s.orgUnitId = %1$s.orgUnitId and %2$s.head = true and %2$s.%3$s
                    and %4$s and not %5$s)"""
                .formatted(row, r, onDate(r), present(r, prefix + "rt"), acting(r, prefix + "rk"));
    }

    // A head row of the date that counts as a head of its unit.
    private static String leads(String row) {
        return "(%1$s.head = true and %2$s and (not %3$s or not %4$s))".formatted(row, present(row, row + "1t"),
                acting(row, row + "1k"), regularPresent(row, row + "1"));
    }

    // A head row of the date whose person looks above the unit. Only an acting head beside a present head does not.
    private static String looksAbove(String row) {
        return "(%1$s.head = true and not (%2$s and %3$s and %4$s))".formatted(row, present(row, row + "2t"),
                acting(row, row + "2k"), regularPresent(row, row + "2"));
    }

    // The database goes down from the line units that the manager leads. It does not go below an org unit that
    // has a head of its own, nor into a group. So a read of one page does not load the tree or the people of the whole realm.
    private static final String REPORTS = """
            with Down as (
                select a.orgUnitId as id, 0 as depth, 1 as open
                from AssignmentEntity a join EmploymentEntity e on e.id = a.employmentId
                where a.realmId = :realmId and e.personId = :managerId and a.%1$s and %6$s
                and not exists (select 1 from OrgUnitVersionEntity gv, RefItemEntity g where gv.realmId = :realmId
                    and gv.orgUnitId = a.orgUnitId and gv.%9$s and g.id = gv.unitTypeId and g.groupUnit = true)
                union all
                select l.childId as id, d.depth + 1 as depth,
                    case when exists (select 1 from AssignmentEntity h join EmploymentEntity he
                            on he.id = h.employmentId
                        where h.realmId = :realmId and h.orgUnitId = l.childId and he.personId <> :managerId
                        and h.%2$s and %7$s) then 0 else 1 end as open
                from Down d
                join OrgUnitLinkEntity l on l.parentId = d.id and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%3$s
                join OrgUnitVersionEntity v on v.orgUnitId = l.childId and v.realmId = :realmId and v.%4$s
                where d.open = 1 and not exists (select 1 from RefItemEntity g where g.id = v.unitTypeId
                    and g.groupUnit = true)
            )
            select 0 as derived, 0 as depth, a.orgUnitId as orgUnitId, a.assignmentId as assignmentId,
                l.id as lineId, l.kind as kind, e.personId as personId, p.userId as userId
            from ReportingLineEntity l
            join AssignmentEntity a on a.assignmentId = l.assignmentId and a.realmId = :realmId and a.%1$s
            join EmploymentEntity e on e.id = a.employmentId
            join PersonEntity p on p.id = e.personId
            where l.realmId = :realmId and l.managerPersonId = :managerId and l.%3$s
            union all
            select 1 as derived, d.depth as depth, a.orgUnitId as orgUnitId, a.assignmentId as assignmentId,
                cast(null as String) as lineId, 'SOLID' as kind, e.personId as personId, p.userId as userId
            from Down d
            join AssignmentEntity a on a.orgUnitId = d.id and a.realmId = :realmId and a.%1$s
            join EmploymentEntity e on e.id = a.employmentId
            join PersonEntity p on p.id = e.personId
            where e.personId <> :managerId
            and ((d.depth = 0 and not %8$s) or (d.depth > 0 and d.open = 1) or (d.depth > 0 and d.open = 0
                and %8$s))
            and not exists (select 1 from ReportingLineEntity s where s.realmId = :realmId
                and s.assignmentId = a.assignmentId and s.kind = 'SOLID' and s.%5$s)
            order by derived, depth, orgUnitId, assignmentId, lineId""".formatted(onDate("a"), onDate("h"), onDate("l"),
            onDate("v"), onDate("s"), leads("a"), leads("h"), looksAbove("a"), onDate("gv"));

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaReportingLineStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    @Override
    public Optional<ReportingLine> find(String id) {
        return findEntity(id).map(JpaReportingLineStore::toDomain);
    }

    @Override
    public List<ReportingLine> findByAssignment(String assignmentId) {
        return list("""
                select l from ReportingLineEntity l where l.realmId = :realmId and l.assignmentId = :value
                order by l.validFrom, l.id""", assignmentId);
    }

    @Override
    public List<ReportingLine> findByManager(String managerPersonId) {
        return list("""
                select l from ReportingLineEntity l where l.realmId = :realmId and l.managerPersonId = :value
                order by l.validFrom, l.id""", managerPersonId);
    }

    private List<ReportingLine> list(String query, String value) {
        return em.createQuery(query, ReportingLineEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("value", value)
                .getResultStream()
                .map(JpaReportingLineStore::toDomain)
                .toList();
    }

    @Override
    public void insert(ReportingLine line) {
        ReportingLineEntity entity = new ReportingLineEntity();
        entity.id = line.id();
        entity.realmId = realmId;
        entity.assignmentId = line.assignmentId();
        entity.managerPersonId = line.managerPersonId();
        // The same session reads the row back, and it then needs the person.
        entity.manager = em.getReference(PersonEntity.class, line.managerPersonId());
        entity.kind = line.kind().name();
        entity.validFrom = line.period().from();
        entity.validTo = line.period().to();
        em.persist(entity);
    }

    @Override
    public void update(ReportingLine line) {
        ReportingLineEntity entity = findEntity(line.id()).orElseThrow();
        entity.validFrom = line.period().from();
        entity.validTo = line.period().to();
        em.flush();
    }

    @Override
    public void delete(String id) {
        findEntity(id).ifPresent(entity -> {
            em.remove(entity);
            em.flush();
        });
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<ReportingLineEntity> findEntity(String id) {
        ReportingLineEntity entity = em.find(ReportingLineEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    private static ReportingLine toDomain(ReportingLineEntity e) {
        return new ReportingLine(e.id, e.assignmentId, e.managerPersonId, e.manager.userId,
                ReportingLine.Kind.valueOf(e.kind),
                new ValidPeriod(e.validFrom, e.validTo));
    }

    @Override
    public List<DirectReport> reportsOn(String hierarchyId, String managerPersonId, LocalDate date, int first,
            int max) {
        return em.createQuery(REPORTS, Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("managerId", managerPersonId)
                .setParameter("date", date)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(row -> new DirectReport((String) row[6], (String) row[7],
                        ReportingLine.Kind.valueOf((String) row[5]), ((Number) row[0]).intValue() == 1,
                        (String) row[4], (String) row[3], (String) row[2]))
                .toList();
    }
}
