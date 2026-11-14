package net.exoego.keycloak.fuhito.store;

import java.util.List;
import java.util.Set;

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
     * Lists the users who are direct members of a group.
     *
     * @param groupId the ID of the group
     * @return the user IDs
     */
    Set<String> members(String groupId);

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
}
