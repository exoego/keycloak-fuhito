package net.exoego.keycloak.fuhito.jpa;

/**
 * The parts of a query that walk the org units of a hierarchy on a date in the database.
 * A read of one page then does not load the whole tree.
 * The rows of {@code Tree} have {@code id}, {@code versionId}, {@code parentId}, and {@code position}.
 * The query needs the parameters {@code realmId}, {@code hierarchyId}, and {@code date}.
 */
final class TreeQuery {

    /**
     * Walks from one unit down. The query also needs the parameter {@code rootId}.
     * Each step finds the version of a child in two lookups that only plain indexes can serve. The first takes
     * the latest version that started, by the org unit and the first date. The second reads it by its ID.
     * One lookup by the org unit and the dates would leave the choice of the index to the database, and the
     * index of the exclusion constraint is about ten times slower for it.
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
     * Walks up the default hierarchy from some units, and gives each unit the work location and the cost center
     * that apply to it. It is one part of a {@code with} clause. The rows of {@code Places} have {@code unitId},
     * {@code workLocationId}, and {@code costCenterId}. A value of a row is the one that applies to the unit, or
     * null when the walk had not found it yet. The query also needs the parameter {@code placesHierarchyId}.
     *
     * @param units the {@code from} part that gives the units as the alias {@code v} of their version
     * @return the part of the query
     */
    static String places(String units) {
        // A walk stops when it has both values. A step finds a version like the walk down does.
        return """
                Places as (
                    select v.orgUnitId as unitId, v.orgUnitId as atId, v.workLocationId as workLocationId,
                        v.costCenterId as costCenterId
                    %1$s
                    union all
                    select u.unitId as unitId, l.parentId as atId,
                        coalesce(u.workLocationId, v.workLocationId) as workLocationId,
                        coalesce(u.costCenterId, v.costCenterId) as costCenterId
                    from Places u
                    join OrgUnitLinkEntity l on l.childId = u.atId and l.realmId = :realmId
                        and l.hierarchyId = :placesHierarchyId and l.%2$s
                    join OrgUnitVersionEntity v on v.id = (select x.id from OrgUnitVersionEntity x
                            where x.orgUnitId = l.parentId and x.validFrom <= :date
                            order by x.validFrom desc fetch first 1 rows only)
                        and v.realmId = :realmId and (v.validTo is null or v.validTo > :date)
                    where u.workLocationId is null or u.costCenterId is null
                )
                """.formatted(units, onDate("l"));
    }

    private static String tree(String rootColumns, String childColumns) {
        return """
                Tree as (
                    select v.orgUnitId as id, v.id as versionId,
                        (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                            and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%1$s) as parentId,
                        v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name%3$s
                    from OrgUnitVersionEntity v
                    where v.realmId = :realmId and v.%2$s and v.orgUnitId = :rootId
                    union all
                    select v.orgUnitId as id, v.id as versionId, l.parentId as parentId,
                        v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name%4$s
                    from Tree p
                    join OrgUnitLinkEntity l on l.parentId = p.id and l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId and l.%1$s
                    join OrgUnitVersionEntity v on v.id = (select x.id from OrgUnitVersionEntity x
                            where x.orgUnitId = l.childId and x.validFrom <= :date
                            order by x.validFrom desc fetch first 1 rows only)
                        and v.realmId = :realmId and (v.validTo is null or v.validTo > :date)
                ) search depth first by displayOrder, phoneticName, name, id set position
                """.formatted(onDate("l"), onDate("v"), rootColumns, childColumns);
    }
}
