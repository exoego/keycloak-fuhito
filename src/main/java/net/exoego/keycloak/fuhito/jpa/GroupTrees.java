package net.exoego.keycloak.fuhito.jpa;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.OrganizationGroups.Group;

/**
 * Reads every Keycloak group below one group in one query, in the order that a walk through the API of Keycloak
 * gives. That walk reads the subgroups of one group at a time. The cache of Keycloak holds 10,000 entries by
 * default, so in a large tree most of those reads go to the database again on each walk.
 */
public final class GroupTrees {

    // The order of GroupModel.COMPARE_BY_NAME, which Keycloak uses for the subgroups of a group.
    private static final Comparator<Group> BY_NAME = Comparator.comparing(Group::name);

    private GroupTrees() {
    }

    /**
     * Lists every group of an organization, each parent before its children. Keycloak gives the groups at the top
     * in the order of names in the database, and the subgroups of a group in the order of names in Java.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param organizationGroupId the ID of the hidden group of the organization
     * @param attribute the name of the group attribute to read into {@link Group#orgUnitId}
     * @return the groups. A group at the top of the organization has no parent here.
     */
    public static List<Group> ofOrganization(EntityManager em, String realmId, String organizationGroupId,
            String attribute) {
        return below(em, realmId, organizationGroupId, attribute, false);
    }

    /**
     * Lists every group below a group, each parent before its children, and the subgroups of a group in the order
     * of names in Java, as Keycloak gives them.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param groupId the ID of the group. It is not in the list.
     * @param attribute the name of the group attribute to read into {@link Group#orgUnitId}
     * @return the groups. A group right below the given one has no parent here.
     */
    public static List<Group> below(EntityManager em, String realmId, String groupId, String attribute) {
        return below(em, realmId, groupId, attribute, true);
    }

    // A subgroup has the type of its parent, as in Keycloak.
    private static List<Group> below(EntityManager em, String realmId, String startId, String attribute,
            boolean sortTop) {
        Map<String, Group> byId = new LinkedHashMap<>();
        em.createQuery("""
                        with Tree as (
                            select g.id as id, g.type as type from GroupEntity g
                            where g.id = :startId and g.realm = :realmId
                            union all
                            select c.id as id, c.type as type from Tree t
                            join GroupEntity c on c.parentId = t.id and c.realm = :realmId and c.type = t.type
                        )
                        select g.id, g.name, g.parentId, a.value from Tree t
                        join GroupEntity g on g.id = t.id
                        left join GroupAttributeEntity a on a.group.id = g.id and a.name = :attribute
                        where g.id <> :startId
                        order by g.name""", Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("startId", startId)
                .setParameter("attribute", attribute)
                .getResultList().stream()
                .forEach(row -> byId.putIfAbsent((String) row[0], new Group((String) row[0], (String) row[1],
                        startId.equals(row[2]) ? null : (String) row[2], (String) row[3])));
        Map<String, List<Group>> children = new HashMap<>();
        byId.values().forEach(group -> children.computeIfAbsent(group.parentId(), id -> new ArrayList<>()).add(group));
        children.forEach((parentId, list) -> {
            if (parentId != null || sortTop) {
                list.sort(BY_NAME);
            }
        });
        List<Group> result = new ArrayList<>();
        collect(null, children, result);
        return result;
    }

    private static void collect(String parentId, Map<String, List<Group>> children, List<Group> result) {
        for (Group group : children.getOrDefault(parentId, List.of())) {
            result.add(group);
            collect(group.id(), children, result);
        }
    }
}
