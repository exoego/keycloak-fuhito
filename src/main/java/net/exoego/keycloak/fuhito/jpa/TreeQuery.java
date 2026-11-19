package net.exoego.keycloak.fuhito.jpa;

/**
 * The parts of a query that walk the org units of a hierarchy on a date in the database.
 * A read of one page then does not load the whole tree.
 * The rows of {@code Tree} have {@code id}, {@code versionId}, {@code parentId}, and {@code position}.
 * The query needs the parameters {@code realmId}, {@code hierarchyId}, and {@code date}.
 */
final class TreeQuery {

    /**
     * Walks from one unit down. Siblings follow the display order, then the primary names.
     * The query also needs the parameter {@code rootId}.
     * Each step finds the version of a child in two lookups. The first takes the latest version that started, by
     * the org unit and the first date. The second reads it by its ID.
     * The versions of one org unit have no gaps, so the latest one that started is the one of the date, or the
     * org unit has ended.
     * It is one part of a {@code with} clause.
     */
    static final String TREE = tree("", "");

    /**
     * The walk of {@link #TREE} as the whole {@code with} clause of a query.
     */
    static final String SUBTREE = "with " + TREE;

    /**
     * Walks from one unit down like {@link #TREE}, and also gives each unit the work location and the cost center
     * that apply to it, in {@code workLocationId} and {@code costCenterId}. A unit takes the values of its parent,
     * so this is right only for the default hierarchy. It is one part of a {@code with} clause, after
     * {@link #places} from the unit.
     */
    static final String TREE_WITH_PLACES = tree(
            ", (select max(u.workLocationId) from Places u) as workLocationId,"
                    + " (select max(u.costCenterId) from Places u) as costCenterId",
            ", coalesce(v.workLocationId, p.workLocationId) as workLocationId,"
                    + " coalesce(v.costCenterId, p.costCenterId) as costCenterId");

    private TreeQuery() {
    }

    static String onDate(String alias) {
        return "validFrom <= :date and (%1$s.validTo is null or %1$s.validTo > :date)".formatted(alias);
    }

    /**
     * Looks up the parent of one unit on the date, for one step of a walk up. A walk up looks up each step in its
     * select. The default hierarchy is new with its realm. While the statistics do not know it, a join there can
     * read every link of the hierarchy for each walk.
     *
     * @param unit the path of the ID of the unit
     * @param hierarchy the name of the parameter with the ID of the hierarchy
     * @return the sub-select, which gives null for a unit with no parent
     */
    static String parentOf(String unit, String hierarchy) {
        return parent(unit, hierarchy, "");
    }

    /**
     * Looks up the parent of one unit like {@link #parentOf}, but only a parent that has a version on the date.
     *
     * @param unit the path of the ID of the unit
     * @param hierarchy the name of the parameter with the ID of the hierarchy
     * @return the sub-select
     */
    static String livingParentOf(String unit, String hierarchy) {
        return parent(unit, hierarchy, """
                 and exists (select 1 from OrgUnitVersionEntity living
                    where living.orgUnitId = l.parentId and living.%s)""".formatted(onDate("living")));
    }

    private static String parent(String unit, String hierarchy, String more) {
        return """
                (select l.parentId from OrgUnitLinkEntity l where l.childId = %s and l.realmId = :realmId
                    and l.hierarchyId = :%s and l.%s%s)""".formatted(unit, hierarchy, onDate("l"), more);
    }

    /**
     * Walks up the default hierarchy from some units, and gives each unit the work location and the cost center
     * that apply to it. It is one part of a {@code with} clause. The rows of {@code Places} have {@code unitId},
     * {@code workLocationId}, and {@code costCenterId}. A value of a row is the one that applies to the unit, or
     * null when the walk had not found it yet. The query also needs the parameter {@code placesHierarchyId}.
     *
     * @param units the {@code from} part that gives the units as the alias {@code v} of their version
     * @return the part of the query
     */
    static String places(String units) {
        // A row has the values of the units up to the one below aboveId. A walk stops when it has both values, or at a
        // unit with no parent that has a version. Each step looks up the version of one unit, like the walk up of
        // JpaOrgUnitStore.DEFAULTS.
        return """
                Places as (
                    select v.orgUnitId as unitId, %3$s as aboveId,
                        v.workLocationId as workLocationId, v.costCenterId as costCenterId
                    %1$s
                    union all
                    select u.unitId as unitId, %4$s as aboveId,
                        coalesce(u.workLocationId, (select w.workLocationId from OrgUnitVersionEntity w
                            where w.orgUnitId = u.aboveId and w.%2$s)) as workLocationId,
                        coalesce(u.costCenterId, (select w.costCenterId from OrgUnitVersionEntity w
                            where w.orgUnitId = u.aboveId and w.%2$s)) as costCenterId
                    from Places u
                    where u.aboveId is not null and (u.workLocationId is null or u.costCenterId is null)
                )
                """.formatted(units, onDate("w"), livingParentOf("v.orgUnitId", "placesHierarchyId"),
                livingParentOf("u.aboveId", "placesHierarchyId"));
    }

    private static String tree(String rootColumns, String childColumns) {
        // Each step takes the hierarchy and the realm from the row of the parent. While the statistics do not know
        // them, a fixed value made the links of a step look like one row, and PostgreSQL could read every link for each
        // row of the step. The links are of the realm, so the versions below the root are found by their units alone.
        return """
                Tree as (
                    select v.orgUnitId as id, v.id as versionId, %5$s as parentId,
                        v.displayOrder as displayOrder, n.phoneticName as phoneticName, n.name as name,
                        cast(:hierarchyId as String) as hierarchyId, v.realmId as realmId%3$s
                    from OrgUnitVersionEntity v
                    join OrgUnitVersionNameEntity n on n.versionId = v.id and n.primary = true
                    where v.orgUnitId = :rootId and %6$s and v.%2$s
                    union all
                    select v.orgUnitId as id, v.id as versionId, l.parentId as parentId,
                        v.displayOrder as displayOrder, n.phoneticName as phoneticName, n.name as name,
                        p.hierarchyId as hierarchyId, p.realmId as realmId%4$s
                    from Tree p
                    join OrgUnitLinkEntity l on l.parentId = p.id and l.hierarchyId = p.hierarchyId
                        and l.realmId = p.realmId and l.%1$s
                    join OrgUnitVersionEntity v on v.id = (select x.id from OrgUnitVersionEntity x
                            where x.orgUnitId = l.childId and x.validFrom <= :date
                            order by x.validFrom desc fetch first 1 rows only)
                        and (v.validTo is null or v.validTo > :date)
                    join OrgUnitVersionNameEntity n on n.versionId = v.id and n.primary = true
                ) search depth first by displayOrder, phoneticName, name, id set position
                """.formatted(onDate("l"), onDate("v"), rootColumns, childColumns,
                parentOf("v.orgUnitId", "hierarchyId"), Rows.ofUnit("v.realmId", "rootId"));
    }
}
