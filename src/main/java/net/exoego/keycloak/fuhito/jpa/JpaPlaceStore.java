package net.exoego.keycloak.fuhito.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.PlaceSpan;
import net.exoego.keycloak.fuhito.domain.ValidPeriod;
import net.exoego.keycloak.fuhito.store.PlaceStore;

/**
 * Reads what applies below an org unit of one realm in the database of Keycloak.
 */
public class JpaPlaceStore implements PlaceStore {

    private final EntityManager em;
    private final String realmId;

    /**
     * Creates the store.
     *
     * @param em the entity manager of the request
     * @param realmId the ID of the realm
     */
    public JpaPlaceStore(EntityManager em, String realmId) {
        this.em = em;
        this.realmId = realmId;
    }

    // Each step down takes the hierarchy and the realm from the row of the parent, as TreeQuery does. The span of a
    // child is the dates that the span of its parent, its link, and its version share. An end of null is no end, and
    // least() of PostgreSQL leaves a null out.
    @Override
    public List<PlaceSpan> placesBelow(String orgUnitId, ValidPeriod period) {
        boolean ends = period.to() != null;
        TypedQuery<Object[]> query = em.createQuery("""
                        with Spans as (
                            select v.orgUnitId as unitId, greatest(v.validFrom, :from) as spanFrom, %1$s as spanTo,
                                v.workLocationId as workLocationId, v.costCenterId as costCenterId,
                                cast(:hierarchyId as String) as hierarchyId, v.realmId as realmId
                            from OrgUnitVersionEntity v
                            where v.orgUnitId = :id and %2$s and (v.validTo is null or v.validTo > :from)%3$s
                            union all
                            select l.childId as unitId, greatest(s.spanFrom, l.validFrom, v.validFrom) as spanFrom,
                                least(s.spanTo, l.validTo, v.validTo) as spanTo,
                                coalesce(v.workLocationId, s.workLocationId) as workLocationId,
                                coalesce(v.costCenterId, s.costCenterId) as costCenterId,
                                s.hierarchyId as hierarchyId, s.realmId as realmId
                            from Spans s
                            join OrgUnitLinkEntity l on l.parentId = s.unitId and l.hierarchyId = s.hierarchyId
                                and l.realmId = s.realmId and (s.spanTo is null or l.validFrom < s.spanTo)
                                and (l.validTo is null or l.validTo > s.spanFrom)
                            join OrgUnitVersionEntity v on v.orgUnitId = l.childId
                                and (v.validTo is null or v.validTo > greatest(s.spanFrom, l.validFrom))
                                and (least(s.spanTo, l.validTo) is null or v.validFrom < least(s.spanTo, l.validTo))
                        )
                        select s.unitId, s.spanFrom, s.spanTo, s.workLocationId, s.costCenterId from Spans s"""
                        .formatted(ends ? "least(v.validTo, :to)" : "v.validTo", Rows.ofUnit("v.realmId", "id"),
                                ends ? " and v.validFrom < :to" : ""), Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("hierarchyId", new JpaHierarchyStore(em, realmId).defaultId())
                .setParameter("id", orgUnitId)
                .setParameter("from", period.from());
        if (ends) {
            query.setParameter("to", period.to());
        }
        return query.getResultList().stream()
                .map(row -> new PlaceSpan((String) row[0], new ValidPeriod((LocalDate) row[1], (LocalDate) row[2]),
                        (String) row[3], (String) row[4]))
                .toList();
    }

    // The rows are found by their units alone, for the reason in Rows.inRealm. The field is a name in the code.
    @Override
    public List<PlaceRow> rowsTakingPlace(Collection<String> orgUnitIds, boolean workLocation, ValidPeriod period) {
        if (orgUnitIds.isEmpty()) {
            return List.of();
        }
        TypedQuery<Object[]> query = em.createQuery("""
                        select a.realmId, a.assignmentId, a.orgUnitId, a.validFrom, a.validTo from AssignmentEntity a
                        where %s and a.%s is null and (a.validTo is null or a.validTo > :from)%s"""
                        .formatted(ManyIds.has("a.orgUnitId", "ids"), workLocation ? "workLocationId" : "costCenterId",
                                period.to() == null ? "" : " and a.validFrom < :to"), Object[].class)
                .setParameter("ids", ManyIds.of(orgUnitIds))
                .setParameter("from", period.from());
        if (period.to() != null) {
            query.setParameter("to", period.to());
        }
        return query.getResultList().stream()
                .filter(Rows.inRealm(realmId, row -> (String) row[0]))
                .map(row -> new PlaceRow((String) row[1], (String) row[2],
                        new ValidPeriod((LocalDate) row[3], (LocalDate) row[4])))
                .toList();
    }

    @Override
    public void countUpUnits(Collection<String> ids) {
        Versions.orgUnitsOfReads(em, ids);
    }

    @Override
    public void countUpAssignments(Collection<String> assignmentIds) {
        Versions.assignmentsOfReads(em, assignmentIds);
    }
}
