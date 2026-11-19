package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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

    // The row is of the realm, and so are the terms of its employment. With the realm, PostgreSQL could read every
    // term of a realm that the statistics do not know yet.
    private static String present(String row, String t) {
        return """
                exists (select 1 from EmploymentTermsEntity %2$s where %2$s.employmentId = %1$s.employmentId
                    and %2$s.status = 'ACTIVE' and %2$s.%3$s)"""
                .formatted(row, t, onDate(t));
    }

    private static String acting(String row, String k) {
        return "exists (select 1 from RefItemEntity %2$s where %2$s.id = %1$s.kindId and %2$s.actingHead = true)"
                .formatted(row, k);
    }

    // True when the unit of the row has a present acting head. Each use gets its own aliases.
    private static String actingPresent(String row, String prefix) {
        String r = prefix + "r";
        return """
                exists (select 1 from AssignmentEntity %2$s where %2$s.realmId = :realmId
                    and %2$s.orgUnitId = %1$s.orgUnitId and %2$s.head = true and %2$s.%3$s
                    and %4$s and %5$s)"""
                .formatted(row, r, onDate(r), present(r, prefix + "rt"), acting(r, prefix + "rk"));
    }

    // A head row of the date that counts as a head of its unit.
    private static String leads(String row) {
        return "(%1$s.head = true and %2$s and (%3$s or not %4$s))".formatted(row, present(row, row + "1t"),
                acting(row, row + "1k"), actingPresent(row, row + "1"));
    }

    // A head row of the date. Its person looks above the unit, also when an acting head leads it.
    private static String looksAbove(String row) {
        return "(%1$s.head = true)".formatted(row);
    }

    // A unit that the manager leads below another one is reached twice in Down. Units counts it once, from the top.
    // The database goes down from the line units that the manager leads. It does not go below an org unit that
    // has a head of its own, nor into a lateral unit. So a read of one page does not load the tree or the people of
    // the whole realm.
    private static String reports(String beforeRows, String afterRows) {
        return """
                with Down as (
                    select a.orgUnitId as id, 0 as depth, 1 as open
                    from AssignmentEntity a join EmploymentEntity e on e.id = a.employmentId
                    where a.realmId = :realmId and e.personId = :managerId and a.%1$s and %6$s
                    and not exists (select 1 from OrgUnitVersionEntity gv, RefItemEntity g where gv.realmId = :realmId
                        and gv.orgUnitId = a.orgUnitId and gv.%9$s and g.id = gv.unitTypeId and g.lateralUnit = true)
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
                        and g.lateralUnit = true)
                ),
                Units as (select d.id as id, min(d.depth) as depth, max(d.open) as open from Down d group by d.id)%10$s
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
                from Units d
                join AssignmentEntity a on a.orgUnitId = d.id and a.realmId = :realmId and a.%1$s
                join EmploymentEntity e on e.id = a.employmentId
                join PersonEntity p on p.id = e.personId
                where e.personId <> :managerId
                and ((d.depth = 0 and not %8$s) or (d.depth > 0 and d.open = 1) or (d.depth > 0 and d.open = 0
                    and %8$s))
                and not exists (select 1 from ReportingLineEntity s where s.realmId = :realmId
                    and s.assignmentId = a.assignmentId and s.kind = 'SOLID' and s.%5$s)%11$s"""
                .formatted(onDate("a"), onDate("h"), onDate("l"), onDate("v"), onDate("s"), leads("a"), leads("h"),
                        looksAbove("a"), onDate("gv"), beforeRows, afterRows);
    }

    private static final String REPORTS = reports("\n", "\norder by derived, depth, orgUnitId, assignmentId, lineId");

    // The count needs the rows of both parts of the union, so they go into one more table of the with clause.
    private static final String COUNT_REPORTS = reports(",\nReports as (\n", "\n)\nselect count(*) from Reports r");

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

    // The lines of these reads are found by an ID alone, for the reason in Rows.inRealm.
    @Override
    public List<ReportingLine> findByAssignment(String assignmentId) {
        return list("""
                select l from ReportingLineEntity l where l.assignmentId = :value
                order by l.validFrom, l.id""", assignmentId);
    }

    // The manager comes in the same query, because each line reads the user of its manager.
    @Override
    public List<ReportingLine> findByAssignments(Collection<String> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select l from ReportingLineEntity l join fetch l.manager where %s
                        order by l.validFrom, l.id""".formatted(ManyIds.has("l.assignmentId", "ids")),
                        ReportingLineEntity.class)
                .setParameter("ids", ManyIds.of(assignmentIds))
                .getResultStream()
                .filter(Rows.inRealm(realmId, l -> l.realmId))
                .map(JpaReportingLineStore::toDomain)
                .toList();
    }

    @Override
    public List<ReportingLine> findByManager(String managerPersonId) {
        return list("""
                select l from ReportingLineEntity l where l.managerPersonId = :value
                order by l.validFrom, l.id""", managerPersonId);
    }

    private List<ReportingLine> list(String query, String value) {
        return em.createQuery(query, ReportingLineEntity.class)
                .setParameter("value", value)
                .getResultStream()
                .filter(Rows.inRealm(realmId, l -> l.realmId))
                .map(JpaReportingLineStore::toDomain)
                .toList();
    }

    @Override
    public List<ReportingLine> listOn(LocalDate date, String afterId, int max) {
        return em.createQuery("""
                        select l from ReportingLineEntity l where l.realmId = :realmId and l.validFrom <= :date
                        and (l.validTo is null or l.validTo > :date) and l.id > :after order by l.id""",
                        ReportingLineEntity.class)
                .setParameter("realmId", realmId)
                .setParameter("date", date)
                .setParameter("after", afterId == null ? "" : afterId)
                .setMaxResults(max)
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
        entity.version = line.version();
        entity.counted = true;
        em.persist(entity);
    }

    @Override
    public void updateAll(List<ReportingLine> lines) {
        for (ReportingLine line : lines) {
            ReportingLineEntity entity = findEntity(line.id()).orElseThrow();
            entity.validFrom = line.period().from();
            entity.validTo = line.period().to();
            entity.countUp();
        }
    }

    @Override
    public void deleteAll(Collection<String> ids) {
        List<ReportingLineEntity> found = ids.stream().map(this::findEntity).flatMap(Optional::stream).toList();
        ExternalIds.deleteOfAll(em, realmId, "REPORTING_LINE", found.stream().map(entity -> entity.id).toList());
        found.forEach(em::remove);
        em.flush();
    }

    // The realm filter keeps one realm from reaching the rows of another by ID.
    private Optional<ReportingLineEntity> findEntity(String id) {
        ReportingLineEntity entity = em.find(ReportingLineEntity.class, id);
        return entity != null && entity.realmId.equals(realmId) ? Optional.of(entity) : Optional.empty();
    }

    static ReportingLine toDomain(ReportingLineEntity e) {
        return new ReportingLine(e.id, e.assignmentId, e.managerPersonId, e.manager.userId,
                ReportingLine.Kind.valueOf(e.kind),
                new ValidPeriod(e.validFrom, e.validTo), e.version);
    }

    @Override
    public List<DirectReport> reportsOn(String hierarchyId, String managerPersonId, LocalDate date, int first,
            int max) {
        return reportsQuery(REPORTS, hierarchyId, managerPersonId, date, Object[].class)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultStream()
                .map(row -> new DirectReport((String) row[6], (String) row[7],
                        ReportingLine.Kind.valueOf((String) row[5]), ((Number) row[0]).intValue() == 1,
                        (String) row[4], (String) row[3], (String) row[2]))
                .toList();
    }

    @Override
    public long countReportsOn(String hierarchyId, String managerPersonId, LocalDate date) {
        return reportsQuery(COUNT_REPORTS, hierarchyId, managerPersonId, date, Long.class).getSingleResult();
    }

    private <T> TypedQuery<T> reportsQuery(String text, String hierarchyId, String managerPersonId, LocalDate date,
            Class<T> type) {
        return em.createQuery(text, type)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", hierarchyId)
                .setParameter("managerId", managerPersonId)
                .setParameter("date", date);
    }
}
