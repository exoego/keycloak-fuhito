package net.exoego.keycloak.fuhito.keycloak;

import java.util.List;
import java.util.Map;

import net.exoego.keycloak.fuhito.store.UserAttributeSource;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Reads the settings of the sync from realm attributes, and the values from user attributes.
 */
final class KeycloakUserAttributes implements UserAttributeSource {

    private static final String PREFIX = "fuhito.sync.";

    private final KeycloakSession session;
    private final RealmModel realm;

    KeycloakUserAttributes(KeycloakSession session, RealmModel realm) {
        this.session = session;
        this.realm = realm;
    }

    @Override
    public Settings settings() {
        return new Settings(realm.getAttribute(PREFIX + "enabled", false), name("department"), name("title"),
                name("manager"), name("employeeNumber"));
    }

    private String name(String field) {
        String configured = realm.getAttribute(PREFIX + "attribute." + field);
        return configured == null || configured.isBlank() ? field : configured;
    }

    @Override
    public List<Values> usersWithDepartment(Settings settings) {
        return session.users()
                .searchForUserStream(realm, Map.of(UserModel.INCLUDE_SERVICE_ACCOUNT, "false"))
                .map(user -> new Values(user.getId(), value(user, settings.department()),
                        value(user, settings.title()), value(user, settings.manager()),
                        value(user, settings.employeeNumber())))
                .filter(values -> values.department() != null)
                .toList();
    }

    private static String value(UserModel user, String attribute) {
        String value = user.getFirstAttribute(attribute);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
