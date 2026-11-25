package net.exoego.keycloak.fuhito.jpa;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

/**
 * Reads who is in which Keycloak group, for many groups or many users in one query.
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
                .getResultList().stream()
                .forEach(row -> result.computeIfAbsent((String) row[0], id -> new HashSet<>()).add((String) row[1]));
        return result;
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
