package net.exoego.keycloak.fuhito.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.exoego.keycloak.fuhito.domain.FuhitoException;

/**
 * The groups and the members of the Keycloak organizations of one realm.
 */
public interface OrganizationGroups {

    /**
     * A group of a Keycloak organization.
     *
     * @param id the ID of the group
     * @param name the name
     * @param parentId the ID of the parent group, or null for a group at the top of the organization
     * @param orgUnitId the ID of the org unit that the group mirrors, or null for a group that someone made by hand
     */
    record Group(String id, String name, String parentId, String orgUnitId) {
    }

    /**
     * Tells whether the realm asked for the projection.
     *
     * @return true when the projection is turned on for the realm
     */
    boolean projectionEnabled();

    /**
     * Lists the IDs of every organization of the realm.
     *
     * @return the IDs
     */
    List<String> organizations();

    /**
     * Lists all groups of an organization, at every depth. A parent comes before its children.
     *
     * @param organizationId the ID of the organization
     * @return the groups
     */
    List<Group> groups(String organizationId);

    /**
     * Creates a group that mirrors an org unit.
     *
     * @param organizationId the ID of the organization
     * @param name the name
     * @param parentId the ID of the parent group, or null for the top of the organization
     * @param orgUnitId the ID of the org unit
     * @return the ID of the new group
     */
    String createGroup(String organizationId, String name, String parentId, String orgUnitId);

    /**
     * Changes the name of a group.
     *
     * @param groupId the ID of the group
     * @param name the new name
     */
    void renameGroup(String groupId, String name);

    /**
     * Moves a group below another parent.
     *
     * @param organizationId the ID of the organization
     * @param groupId the ID of the group
     * @param parentId the ID of the new parent group, or null for the top of the organization
     */
    void moveGroup(String organizationId, String groupId, String parentId);

    /**
     * Removes a group and the groups below it.
     *
     * @param groupId the ID of the group
     */
    void removeGroup(String groupId);

    /**
     * The members of some groups.
     *
     * @param groupIds the IDs of the groups
     * @return the IDs of the users by the ID of their group. A group with no member can be left out.
     */
    Map<String, Set<String>> members(Collection<String> groupIds);

    /**
     * The members of an organization, out of some users.
     *
     * @param organizationId the ID of the organization
     * @param userIds the IDs of the users to look at
     * @return the IDs of those users that are members of the organization
     */
    Set<String> organizationMembers(String organizationId, Collection<String> userIds);

    /**
     * Makes a user a member of a group.
     *
     * @param groupId the ID of the group
     * @param userId the ID of the user
     * @return false when Keycloak has no such user, so nothing changed
     */
    boolean join(String groupId, String userId);

    /**
     * Takes a user out of a group.
     *
     * @param groupId the ID of the group
     * @param userId the ID of the user
     * @return false when Keycloak has no such user, so nothing changed
     */
    boolean leave(String groupId, String userId);

    /**
     * Makes a user an unmanaged member of an organization, unless the user is a member already.
     *
     * @param organizationId the ID of the organization
     * @param userId the ID of the user
     * @return true when the user became a member now
     */
    boolean addOrganizationMember(String organizationId, String userId);

    /**
     * Takes an unmanaged member out of an organization and out of its groups. A managed member stays.
     *
     * @param organizationId the ID of the organization
     * @param userId the ID of the user
     * @return true when the user left the organization now. False when Keycloak has no such user, or the user is
     *         no member, or a managed one.
     */
    boolean removeOrganizationMember(String organizationId, String userId);

    /**
     * Returns the realm group that the whole tree is mirrored below.
     *
     * @return the ID of the group, or empty when the realm mirrors into no realm group
     * @throws FuhitoException when the realm names a group that does not exist
     */
    Optional<String> realmGroupRoot();

    /**
     * Lists every group below a realm group, each parent before its children. A group right below the root has
     * no parent here.
     *
     * @param rootGroupId the ID of the realm group
     * @return the groups
     */
    List<Group> realmGroups(String rootGroupId);

    /**
     * Creates a realm group that mirrors an org unit.
     *
     * @param rootGroupId the ID of the realm group that the tree is below
     * @param name the name
     * @param parentId the ID of the parent group, or null for a group right below the root
     * @param orgUnitId the ID of the org unit that the group mirrors
     * @return the ID of the new group
     */
    String createRealmGroup(String rootGroupId, String name, String parentId, String orgUnitId);

    /**
     * Moves a realm group below another parent.
     *
     * @param rootGroupId the ID of the realm group that the tree is below
     * @param groupId the ID of the group
     * @param parentId the ID of the new parent, or null for right below the root
     */
    void moveRealmGroup(String rootGroupId, String groupId, String parentId);

    /**
     * Runs a piece of work that reads much and writes through this interface only.
     * Keycloak may then put off writing its changes to the database until the work is done. Without that, it
     * looks through every object that it has loaded before each read, and a rebuild of a large realm takes
     * minutes when nothing changed.
     *
     * @param <T> the type of the result
     * @param work the work
     * @return the result of the work
     */
    default <T> T inOneFlush(Supplier<T> work) {
        return work.get();
    }
}
