package net.exoego.keycloak.fuhito.jpa;

/**
 * The start of a query that walks the org units of a hierarchy on a date in the database.
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
     */
    static final String SUBTREE = """
            with Tree as (
                select v.orgUnitId as id, v.id as versionId,
                    (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%1$s) as parentId,
                    v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name
                from OrgUnitVersionEntity v
                where v.realmId = :realmId and v.%2$s and v.orgUnitId = :rootId
                union all
                select v.orgUnitId as id, v.id as versionId, l.parentId as parentId,
                    v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name
                from Tree p
                join OrgUnitLinkEntity l on l.parentId = p.id and l.realmId = :realmId
                    and l.hierarchyId = :hierarchyId and l.%1$s
                join OrgUnitVersionEntity v on v.id = (select x.id from OrgUnitVersionEntity x
                        where x.orgUnitId = l.childId and x.validFrom <= :date
                        order by x.validFrom desc fetch first 1 rows only)
                    and v.realmId = :realmId and (v.validTo is null or v.validTo > :date)
            ) search depth first by displayOrder, phoneticName, name, id set position
            """.formatted(onDate("l"), onDate("v"));

    private TreeQuery() {
    }

    static String onDate(String alias) {
        return "validFrom <= :date and (%1$s.validTo is null or %1$s.validTo > :date)".formatted(alias);
    }
}
