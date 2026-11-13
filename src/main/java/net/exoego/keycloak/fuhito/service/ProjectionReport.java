package net.exoego.keycloak.fuhito.service;

/**
 * What a rebuild of the projection changed in Keycloak.
 *
 * @param organizations the number of linked organizations that were projected
 * @param groupsCreated the number of groups that were created
 * @param groupsRenamed the number of groups that got a new name
 * @param groupsMoved the number of groups that got a new parent
 * @param groupsRemoved the number of groups that were removed
 * @param membersJoined the number of group memberships that were added
 * @param membersLeft the number of group memberships that were removed
 * @param organizationMembersAdded the number of users who became members of an organization
 */
public record ProjectionReport(int organizations, int groupsCreated, int groupsRenamed, int groupsMoved,
        int groupsRemoved, int membersJoined, int membersLeft, int organizationMembersAdded) {
}
