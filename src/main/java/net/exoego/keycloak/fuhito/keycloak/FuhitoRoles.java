package net.exoego.keycloak.fuhito.keycloak;

import org.keycloak.models.AdminRoles;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

/**
 * The roles that give access to the admin REST API of the extension.
 * They sit beside the admin roles of Keycloak, so that Keycloak checks them the same way.
 */
public final class FuhitoRoles {

    /**
     * Reads the org structure.
     */
    public static final String VIEW_STRUCTURE = "fuhito-view-structure";

    /**
     * Reads everything, also the data of people.
     */
    public static final String VIEW_PEOPLE = "fuhito-view-people";

    /**
     * Writes.
     */
    public static final String MANAGE = "fuhito-manage";

    private FuhitoRoles() {
    }

    /**
     * Adds the roles to the admin clients of a realm, unless they are there.
     * A realm has the client {@code realm-management}, and the master realm has a client for each realm.
     * The administrators of both keep what they could do.
     *
     * @param realm the realm
     */
    public static void addTo(RealmModel realm) {
        ClientModel management = realm.getClientByClientId(Constants.REALM_MANAGEMENT_CLIENT_ID);
        if (management != null) {
            RoleModel manage = addTo(management);
            RoleModel realmAdmin = management.getRole(AdminRoles.REALM_ADMIN);
            if (realmAdmin != null) {
                contain(realmAdmin, manage);
            }
        }
        ClientModel master = realm.getMasterAdminClient();
        if (master != null) {
            RoleModel manage = addTo(master);
            RoleModel admin = master.getRealm().getRole(AdminRoles.ADMIN);
            if (admin != null) {
                contain(admin, manage);
            }
        }
    }

    private static RoleModel addTo(ClientModel client) {
        RoleModel structure = role(client, VIEW_STRUCTURE, "Read the org structure of fuhito.");
        RoleModel people = role(client, VIEW_PEOPLE, "Read the org structure and the data of people in fuhito.");
        RoleModel manage = role(client, MANAGE, "Read and write everything in fuhito.");
        contain(people, structure);
        contain(manage, people);
        return manage;
    }

    private static RoleModel role(ClientModel client, String name, String description) {
        RoleModel role = client.getRole(name);
        if (role == null) {
            role = client.addRole(name);
            role.setDescription(description);
        }
        return role;
    }

    private static void contain(RoleModel parent, RoleModel child) {
        if (parent.getCompositesStream().noneMatch(role -> role.getId().equals(child.getId()))) {
            parent.addCompositeRole(child);
        }
    }
}
