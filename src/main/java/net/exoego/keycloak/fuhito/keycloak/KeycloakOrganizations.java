package net.exoego.keycloak.fuhito.keycloak;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.OrganizationGroups;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.OrganizationProvider;

/**
 * The only class that calls the Organizations API of Keycloak. That API changes between minor versions.
 */
final class KeycloakOrganizations implements OrganizationDirectory, OrganizationGroups {

    /**
     * The realm attribute that turns the projection on when it is "true".
     */
    static final String PROJECTION_ATTRIBUTE = "fuhito.projection.enabled";

    /**
     * The group attribute that holds the ID of the org unit that a group mirrors.
     */
    static final String ORG_UNIT_ATTRIBUTE = "fuhito.orgUnitId";

    private final KeycloakSession session;
    private final RealmModel realm;

    KeycloakOrganizations(KeycloakSession session, RealmModel realm) {
        this.session = session;
        this.realm = realm;
    }

    private OrganizationProvider provider() {
        return session.getProvider(OrganizationProvider.class);
    }

    @Override
    public boolean exists(String organizationId) {
        OrganizationProvider provider = provider();
        // No test has a missing provider. It happens when the server runs with the organization feature off.
        return provider != null && provider.isEnabled() && provider.getById(organizationId) != null;
    }

    @Override
    public boolean projectionEnabled() {
        return realm.getAttribute(PROJECTION_ATTRIBUTE, false);
    }

    @Override
    public List<Group> groups(String organizationId) {
        OrganizationModel organization = provider().getById(organizationId);
        List<Group> result = new ArrayList<>();
        provider().getTopLevelGroups(organization, null, null).toList()
                .forEach(group -> collect(group, null, result));
        return result;
    }

    private static void collect(GroupModel group, String parentId, List<Group> result) {
        result.add(new Group(group.getId(), group.getName(), parentId, group.getFirstAttribute(ORG_UNIT_ATTRIBUTE)));
        group.getSubGroupsStream().toList().forEach(child -> collect(child, group.getId(), result));
    }

    @Override
    public String createGroup(String organizationId, String name, String parentId, String orgUnitId) {
        OrganizationModel organization = provider().getById(organizationId);
        return within(organization, () -> {
            GroupModel group = provider().createGroup(organization, name, parentId == null ? null : group(parentId));
            group.setSingleAttribute(ORG_UNIT_ATTRIBUTE, orgUnitId);
            return group.getId();
        });
    }

    @Override
    public void renameGroup(String groupId, String name) {
        GroupModel group = group(groupId);
        within(group.getOrganization(), () -> {
            group.setName(name);
            return null;
        });
    }

    @Override
    public void moveGroup(String organizationId, String groupId, String parentId) {
        OrganizationModel organization = provider().getById(organizationId);
        // A group at the top of an organization sits below the hidden group of the organization.
        GroupModel parent = parentId == null ? provider().getOrganizationGroup(organization) : group(parentId);
        within(organization, () -> {
            session.groups().moveGroup(realm, group(groupId), parent);
            return null;
        });
    }

    @Override
    public void removeGroup(String groupId) {
        GroupModel group = group(groupId);
        within(group.getOrganization(), () -> session.groups().removeGroup(realm, group));
    }

    // Keycloak lets a group of an organization change only while that organization is the one in context.
    private <T> T within(OrganizationModel organization, Supplier<T> action) {
        OrganizationModel before = session.getContext().getOrganization();
        session.getContext().setOrganization(organization);
        try {
            return action.get();
        } finally {
            session.getContext().setOrganization(before);
        }
    }

    @Override
    public Set<String> members(String groupId) {
        return session.users().getGroupMembersStream(realm, group(groupId)).map(UserModel::getId)
                .collect(Collectors.toSet());
    }

    @Override
    public void join(String groupId, String userId) {
        UserModel user = session.users().getUserById(realm, userId);
        GroupModel group = group(groupId);
        // No test has a missing user here, in leave, or in addOrganizationMember. It happens when the user
        // is removed from Keycloak while a rebuild runs, or when a user store that is federated lost the user.
        if (user != null) {
            within(group.getOrganization(), () -> {
                user.joinGroup(group);
                return null;
            });
        }
    }

    @Override
    public void leave(String groupId, String userId) {
        UserModel user = session.users().getUserById(realm, userId);
        GroupModel group = group(groupId);
        if (user != null) {
            within(group.getOrganization(), () -> {
                user.leaveGroup(group);
                return null;
            });
        }
    }

    @Override
    public boolean addOrganizationMember(String organizationId, String userId) {
        OrganizationModel organization = provider().getById(organizationId);
        UserModel user = session.users().getUserById(realm, userId);
        if (user == null || provider().isMember(organization, user)) {
            return false;
        }
        // The default membership is unmanaged, so one user can belong to several organizations.
        return within(organization, () -> provider().addMember(organization, user));
    }

    private GroupModel group(String groupId) {
        GroupModel group = session.groups().getGroupById(realm, groupId);
        // No test reaches this. It happens when someone removes the group while a rebuild runs.
        if (group == null) {
            throw new IllegalStateException("No such group in the realm: " + groupId);
        }
        return group;
    }
}
