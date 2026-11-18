package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.store.OrganizationGroups.Group;
import net.exoego.keycloak.fuhito.store.OrganizationGroups.OwnedGroup;

/**
 * Reads who is in which Keycloak group, and which groups there are, for many groups or many users in one query.
 * The API of Keycloak answers for one group or one user at a time, and loads every user that it names.
 * These reads use the table of Keycloak for the users in its own database. A user that only a user storage
 * provider holds is not in it.
 */
public final class GroupMemberships {

    private GroupMemberships() {
    }

    /**
     * The members of each group.
     *
     * @param em the entity manager of the transaction
     * @param groupIds the IDs of the groups
     * @return the IDs of the users by the ID of their group. A group with no member is left out.
     */
    public static Map<String, Set<String>> byGroup(EntityManager em, Collection<String> groupIds) {
        Map<String, Set<String>> result = new HashMap<>();
        em.createQuery("select m.groupId, m.user.id from UserGroupMembershipEntity m where %s"
                        .formatted(ManyIds.has("m.groupId", "groupIds")), Object[].class)
                .setParameter("groupIds", ManyIds.of(groupIds))
                .getResultStream()
                .forEach(row -> result.computeIfAbsent((String) row[0], id -> new HashSet<>()).add((String) row[1]));
        return result;
    }

    /**
     * The groups of each user.
     *
     * @param em the entity manager of the transaction
     * @param userIds the IDs of the users
     * @return the IDs of the groups by the ID of their user. A user in no group is left out.
     */
    public static Map<String, Set<String>> byUser(EntityManager em, Collection<String> userIds) {
        Map<String, Set<String>> result = new HashMap<>();
        em.createQuery("select m.user.id, m.groupId from UserGroupMembershipEntity m where %s"
                        .formatted(ManyIds.has("m.user.id", "userIds")), Object[].class)
                .setParameter("userIds", ManyIds.of(userIds))
                .getResultStream()
                .forEach(row -> result.computeIfAbsent((String) row[0], id -> new HashSet<>()).add((String) row[1]));
        return result;
    }

    /**
     * Every group of a realm that has an attribute, with the organization of the group.
     *
     * @param em the entity manager of the transaction
     * @param realmId the ID of the realm
     * @param attribute the name of the attribute
     * @return the groups, with the value of the attribute as the org unit
     */
    public static List<OwnedGroup> withAttribute(EntityManager em, String realmId, String attribute) {
        return em.createQuery("""
                        select g.id, g.name, g.parentId, a.value, o.id, o.groupId
                        from GroupAttributeEntity a join a.group g left join g.organization o
                        where g.realm = :realmId and a.name = :attribute""", Object[].class)
                .setParameter("realmId", realmId)
                .setParameter("attribute", attribute)
                .getResultStream()
                .map(row -> {
                    String parentId = (String) row[2];
                    // A group at the top of an organization sits below the hidden group of the organization.
                    boolean top = row[4] != null && parentId.equals(row[5]);
                    return new OwnedGroup(new Group((String) row[0], (String) row[1], top ? null : parentId,
                            (String) row[3]), (String) row[4]);
                })
                .toList();
    }

    /**
     * The users that are in one group, out of some users.
     *
     * @param em the entity manager of the transaction
     * @param groupId the ID of the group
     * @param userIds the IDs of the users to look at
     * @return the IDs of those users that are members
     */
    public static Set<String> among(EntityManager em, String groupId, Collection<String> userIds) {
        return new HashSet<>(em.createQuery("""
                        select m.user.id from UserGroupMembershipEntity m where m.groupId = :groupId
                        and %s""".formatted(ManyIds.has("m.user.id", "userIds")), String.class)
                .setParameter("groupId", groupId)
                .setParameter("userIds", ManyIds.of(userIds))
                .getResultList());
    }
}
