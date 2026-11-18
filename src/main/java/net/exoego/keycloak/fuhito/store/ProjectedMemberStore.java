package net.exoego.keycloak.fuhito.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The users that the projection added to the Keycloak organizations of one realm.
 * The projection takes out only these users. Keycloak does not record who added a member.
 */
public interface ProjectedMemberStore {

    /**
     * Records that the projection added a user to an organization.
     *
     * @param organizationId the ID of the organization
     * @param userId the ID of the user
     */
    void record(String organizationId, String userId);

    /**
     * Removes the record of a user in an organization, if there is one.
     *
     * @param organizationId the ID of the organization
     * @param userId the ID of the user
     */
    void forget(String organizationId, String userId);

    /**
     * Removes every record of an organization.
     *
     * @param organizationId the ID of the organization
     */
    void forgetOrganization(String organizationId);

    /**
     * The users that have a record in an organization, out of some users.
     *
     * @param organizationId the ID of the organization
     * @param userIds the IDs of the users to look at
     * @return the IDs of those users that have a record
     */
    Set<String> recorded(String organizationId, Collection<String> userIds);

    /**
     * The organizations where some users have a record.
     *
     * @param userIds the IDs of the users to look at
     * @return the IDs of the organizations by the ID of the user. A user with no record is left out.
     */
    Map<String, Set<String>> organizationsOf(Collection<String> userIds);

    /**
     * Reads a page of the users that have a record in an organization, in the order of their IDs.
     *
     * @param organizationId the ID of the organization
     * @param afterUserId the ID after which the page starts, or null for the start
     * @param max the largest number of users
     * @return the IDs of the users
     */
    List<String> recordedAfter(String organizationId, String afterUserId, int max);

    /**
     * Lists the organizations that have records.
     *
     * @return the IDs of the organizations
     */
    List<String> organizations();
}
