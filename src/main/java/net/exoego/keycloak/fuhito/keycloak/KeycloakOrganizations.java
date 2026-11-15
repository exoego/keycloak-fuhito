package net.exoego.keycloak.fuhito.keycloak;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.domain.FuhitoException;
import net.exoego.keycloak.fuhito.jpa.GroupMemberships;
import net.exoego.keycloak.fuhito.jpa.OneFlush;
import net.exoego.keycloak.fuhito.store.OrganizationDirectory;
import net.exoego.keycloak.fuhito.store.OrganizationGroups;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
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
     * The realm attribute with the path of the realm group that the whole tree is mirrored below.
     */
    static final String REALM_GROUP_ATTRIBUTE = "fuhito.projection.realmGroup";

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
        // There is no provider when the server runs with the organization feature off.
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
    public Optional<String> realmGroupRoot() {
        String path = realm.getAttribute(REALM_GROUP_ATTRIBUTE);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        GroupModel root = KeycloakModelUtils.findGroupByPath(session, realm, path);
        if (root == null) {
            throw FuhitoException.conflict("projection.realm_group_unknown", Map.of("path", path));
        }
        return Optional.of(root.getId());
    }

    @Override
    public List<Group> realmGroups(String rootGroupId) {
        List<Group> result = new ArrayList<>();
        group(rootGroupId).getSubGroupsStream().toList().forEach(group -> collect(group, null, result));
        return result;
    }

    @Override
    public String createRealmGroup(String rootGroupId, String name, String parentId, String orgUnitId) {
        GroupModel group = session.groups().createGroup(realm, name,
                group(parentId == null ? rootGroupId : parentId));
        group.setSingleAttribute(ORG_UNIT_ATTRIBUTE, orgUnitId);
        return group.getId();
    }

    @Override
    public void moveRealmGroup(String rootGroupId, String groupId, String parentId) {
        session.groups().moveGroup(realm, group(groupId), group(parentId == null ? rootGroupId : parentId));
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
    public Map<String, Set<String>> members(Collection<String> groupIds) {
        return GroupMemberships.byGroup(entityManager(), groupIds);
    }

    // A member of an organization is a member of the hidden group of the organization.
    @Override
    public Set<String> organizationMembers(String organizationId, Collection<String> userIds) {
        OrganizationModel organization = provider().getById(organizationId);
        return GroupMemberships.among(entityManager(), provider().getOrganizationGroup(organization).getId(),
                userIds);
    }

    private EntityManager entityManager() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    @Override
    public boolean join(String groupId, String userId) {
        return withUser(groupId, userId, UserModel::joinGroup);
    }

    @Override
    public boolean leave(String groupId, String userId) {
        return withUser(groupId, userId, UserModel::leaveGroup);
    }

    // The user is missing when Keycloak lost it and the assignment stayed. A restore of only the tables
    // of Keycloak does that, and so does a removal of the user while a rebuild runs.
    private boolean withUser(String groupId, String userId, BiConsumer<UserModel, GroupModel> change) {
        UserModel user = session.users().getUserById(realm, userId);
        GroupModel group = group(groupId);
        if (user == null) {
            return false;
        }
        return within(group.getOrganization(), () -> {
            change.accept(user, group);
            return true;
        });
    }

    @Override
    public boolean addOrganizationMember(String organizationId, String userId) {
        OrganizationModel organization = provider().getById(organizationId);
        UserModel user = session.users().getUserById(realm, userId);
        if (user == null) {
            return false;
        }
        // The default membership is unmanaged, so one user can belong to several organizations.
        return within(organization, () -> provider().addMember(organization, user));
    }

    // The rebuild writes only through the providers of Keycloak, and those do not read back what they wrote
    // before the end of a piece.
    @Override
    public <T> T inOneFlush(Supplier<T> work) {
        return OneFlush.run(entityManager(), work);
    }

    private GroupModel group(String groupId) {
        // The group is missing only when someone removes it while a rebuild runs. No test can time that.
        return Objects.requireNonNull(session.groups().getGroupById(realm, groupId),
                "No such group in the realm: " + groupId);
    }
}
