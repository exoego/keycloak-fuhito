package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.TypedQuery;
import net.exoego.keycloak.fuhito.domain.MemberCursor;
import net.exoego.keycloak.fuhito.domain.MemberFilter;

/**
 * The queries of the members of an org unit on a date, as {@link JpaAssignmentStore#membersOn} reads them.
 */
final class MemberQueries {

    // For a secondment, a list of one unit walks up from it, and a list with the units below from each of them.
    private static final String ROOT_LEGAL_ENTITIES = TreeQuery.legalEntities("""
            from OrgUnitVersionEntity v where v.orgUnitId = :rootId and %s and v.%s"""
            .formatted(Rows.ofUnit("v.realmId", "rootId"), TreeQuery.onDate("v")), "entityHierarchyId");

    private static final String TREE_LEGAL_ENTITIES = TreeQuery.legalEntities(
            "from Tree t join OrgUnitVersionEntity v on v.id = t.versionId", "entityHierarchyId");

    private static final String ROOT_PLACES = TreeQuery.places("""
            from OrgUnitVersionEntity v where v.orgUnitId = :rootId and %s and v.%s"""
            .formatted(Rows.ofUnit("v.realmId", "rootId"), TreeQuery.onDate("v")));

    private static final String TREE_PLACES = TreeQuery.places(
            "from Tree t join OrgUnitVersionEntity v on v.id = t.versionId");

    private MemberQueries() {
    }

    private static String withClause(MemberFilter filter, boolean carried, boolean carriedEntity) {
        boolean below = filter.includeDescendants();
        List<String> tables = new ArrayList<>();
        if (filter.places() != null && (carried || !below)) {
            tables.add(ROOT_PLACES);
        }
        if (filter.seconded() != null && (carriedEntity || !below)) {
            tables.add(ROOT_LEGAL_ENTITIES);
        }
        if (below) {
            tables.add(TreeQuery.treeCarrying(carried, carriedEntity));
        }
        if (filter.places() != null && below && !carried) {
            tables.add(TREE_PLACES);
        }
        if (filter.seconded() != null && below && !carriedEntity) {
            tables.add(TREE_LEGAL_ENTITIES);
        }
        return tables.isEmpty() ? "" : "with " + String.join(", ", tables);
    }

    /**
     * Builds the query of a page of members, or of their count.
     *
     * @param filter which assignments to take
     * @param after the last row of the page before, or null
     * @param count true for the count of all pages
     * @return the text of the query in HQL
     */
    static String text(MemberFilter filter, MemberCursor after, boolean count) {
        // Each piece is a fixed text. The values of the request go in as parameters.
        String selected = count ? "select count(a)" : "select a";
        MemberFilter.Places places = filter.places();
        MemberFilter.Seconded seconded = filter.seconded();
        boolean below = filter.includeDescendants();
        // In the default hierarchy, the walk down carries the values. In another one, each unit walks up.
        boolean carried = places != null && below && places.hierarchyId().equals(filter.hierarchyId());
        boolean carriedEntity = seconded != null && below && seconded.hierarchyId().equals(filter.hierarchyId());
        StringBuilder text = new StringBuilder(withClause(filter, carried, carriedEntity));
        String entities = seconded == null || carriedEntity ? ""
                : " left join LegalEntities le on le.unitId = a.orgUnitId and le.entityId is not null";
        if (below) {
            text.append(selected).append(" from AssignmentEntity a join Tree t on t.id = a.orgUnitId")
                    .append(entities).append(" where ").append(Rows.ofUnit("a.realmId", "rootId"));
        } else {
            text.append(selected).append(" from AssignmentEntity a").append(entities)
                    .append(" where a.orgUnitId = :rootId and ").append(Rows.ofUnit("a.realmId", "rootId"));
        }
        text.append(" and a.").append(TreeQuery.onDate("a"));
        if (places != null) {
            // The value of the assignment wins over the one that applies to its org unit.
            if (places.workLocationId() != null) {
                text.append(carried ? " and (coalesce(a.workLocationId, t.workLocationId) = :workLocationId)"
                        : """
                         and (a.workLocationId = :workLocationId or (a.workLocationId is null and a.orgUnitId in
                        (select u.unitId from Places u where u.workLocationId = :workLocationId)))""");
            }
            if (places.costCenterId() != null) {
                text.append(carried ? " and (coalesce(a.costCenterId, t.costCenterId) = :costCenterId)"
                        : """
                         and (a.costCenterId = :costCenterId or (a.costCenterId is null and a.orgUnitId in
                        (select u.unitId from Places u where u.costCenterId = :costCenterId)))""");
            }
        }
        if (filter.occupationId() != null) {
            text.append(" and a.occupationId = :occupationId");
        }
        if (filter.jobTitleId() != null) {
            text.append(" and a.jobTitleId = :jobTitleId");
        }
        // For one unit, the realm of the assignment comes from a sub-select, and the terms take it from there.
        if (filter.status() != null) {
            text.append("""
                     and exists (select 1 from EmploymentTermsEntity e where e.realmId = a.realmId
                    and e.employmentId = a.employmentId and e.status = :status and e.""")
                    .append(TreeQuery.onDate("e")).append(')');
        }
        if (seconded != null) {
            // No employer or no legal entity gives no secondment. A sub-select keeps the employer out of the joins.
            text.append((seconded.wanted() ? " and %1$s <> %2$s" : " and (%1$s is null or %1$s = coalesce(%2$s, %1$s))")
                    .formatted(carriedEntity ? "t.entityId" : "le.entityId",
                            "(select m.employerId from EmploymentEntity m where m.id = a.employmentId)"));
        }
        if (after != null) {
            // The rows after the cursor, in the order of the list. So the database does not count past the
            // rows of the pages before.
            String laterInUnit = """
                    (a.displayOrder > :afterOrder or (a.displayOrder = :afterOrder and (a.validFrom > :afterFrom
                    or (a.validFrom = :afterFrom and a.assignmentId > :afterId))))""";
            if (filter.includeDescendants()) {
                text.append("""
                         and (t.position > (select c.position from Tree c where c.id = :afterUnit)
                        or (a.orgUnitId = :afterUnit and\s""").append(laterInUnit).append("))");
            } else {
                text.append(" and ").append(laterInUnit);
            }
        }
        if (!count) {
            text.append(" order by ").append(filter.includeDescendants() ? "t.position, " : "")
                    .append("a.displayOrder, a.validFrom, a.assignmentId");
        }
        return text.toString();
    }

    /**
     * Gives a query of {@link #text} the values of its parameters.
     *
     * @param query the query
     * @param realmId the ID of the realm
     * @param filter which assignments to take
     * @param after the last row of the page before, or null
     */
    static void bind(TypedQuery<?> query, String realmId, MemberFilter filter, MemberCursor after) {
        query.setParameter("realmId", realmId)
                .setParameter("rootId", filter.orgUnitId())
                .setParameter("date", filter.date());
        if (filter.includeDescendants()) {
            query.setParameter("hierarchyId", filter.hierarchyId());
        }
        if (filter.occupationId() != null) {
            query.setParameter("occupationId", filter.occupationId());
        }
        if (filter.jobTitleId() != null) {
            query.setParameter("jobTitleId", filter.jobTitleId());
        }
        if (filter.status() != null) {
            query.setParameter("status", filter.status().name());
        }
        if (filter.seconded() != null) {
            query.setParameter("entityHierarchyId", filter.seconded().hierarchyId());
        }
        if (filter.places() != null) {
            query.setParameter("placesHierarchyId", filter.places().hierarchyId());
            if (filter.places().workLocationId() != null) {
                query.setParameter("workLocationId", filter.places().workLocationId());
            }
            if (filter.places().costCenterId() != null) {
                query.setParameter("costCenterId", filter.places().costCenterId());
            }
        }
        if (after != null) {
            query.setParameter("afterOrder", after.displayOrder())
                    .setParameter("afterFrom", after.validFrom())
                    .setParameter("afterId", after.assignmentId());
            if (filter.includeDescendants()) {
                query.setParameter("afterUnit", after.orgUnitId());
            }
        }
    }
}
