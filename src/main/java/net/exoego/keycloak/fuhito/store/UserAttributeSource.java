package net.exoego.keycloak.fuhito.store;

import java.util.List;

/**
 * The user attributes that an identity provider fills, for example over SCIM, and the settings of the realm
 * for reading them.
 */
public interface UserAttributeSource {

    /**
     * The settings of the realm.
     *
     * @param enabled true when the realm turned the sync on
     * @param department the name of the user attribute with the department
     * @param title the name of the user attribute with the title
     * @param manager the name of the user attribute with the user ID of the manager
     * @param employeeNumber the name of the user attribute with the staff number
     */
    record Settings(boolean enabled, String department, String title, String manager, String employeeNumber) {
    }

    /**
     * The values of one user. A missing or blank attribute is null.
     *
     * @param userId the ID of the user
     * @param department the department
     * @param title the title
     * @param managerUserId the user ID of the manager
     * @param employeeNumber the staff number
     */
    record Values(String userId, String department, String title, String managerUserId, String employeeNumber) {
    }

    /**
     * Returns the settings of the realm.
     *
     * @return the settings
     */
    Settings settings();

    /**
     * Lists the users of the realm that have a department.
     *
     * @param settings the names of the attributes to read
     * @return the values of each such user
     */
    List<Values> usersWithDepartment(Settings settings);
}
