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
     */
    static final String SUBTREE = from("v.orgUnitId = :rootId");

    private TreeQuery() {
    }

    static String onDate(String alias) {
        return "validFrom <= :date and (%1$s.validTo is null or %1$s.validTo > :date)".formatted(alias);
    }

    static String from(String start) {
        return """
                with Tree as (
                    select v.orgUnitId as id, v.id as versionId,
                        (select l.parentId from OrgUnitLinkEntity l where l.realmId = :realmId
                            and l.hierarchyId = :hierarchyId and l.childId = v.orgUnitId and l.%1$s) as parentId,
                        v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name
                    from OrgUnitVersionEntity v
                    where v.realmId = :realmId and v.%2$s and %3$s
                    union all
                    select v.orgUnitId as id, v.id as versionId, l.parentId as parentId,
                        v.displayOrder as displayOrder, v.phoneticName as phoneticName, v.name as name
                    from Tree p
                    join OrgUnitLinkEntity l on l.parentId = p.id and l.realmId = :realmId
                        and l.hierarchyId = :hierarchyId and l.%1$s
                    join OrgUnitVersionEntity v on v.orgUnitId = l.childId and v.realmId = :realmId and v.%2$s
                ) search depth first by displayOrder, phoneticName, name, id set position
                """.formatted(onDate("l"), onDate("v"), start);
    }
}
