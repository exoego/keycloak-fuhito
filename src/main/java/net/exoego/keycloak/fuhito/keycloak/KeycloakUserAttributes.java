package net.exoego.keycloak.fuhito.keycloak;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import net.exoego.keycloak.fuhito.jpa.UserAttributeValues;
import net.exoego.keycloak.fuhito.store.UserAttributeSource;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

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
                name("manager"), name("employeeNumber"), employerCode());
    }

    private String employerCode() {
        String configured = realm.getAttribute(PREFIX + "employer");
        return configured == null || configured.isBlank() ? null : configured.strip();
    }

    private String name(String field) {
        String configured = realm.getAttribute(PREFIX + "attribute." + field);
        return configured == null || configured.isBlank() ? field : configured;
    }

    @Override
    public List<Values> usersWithDepartment(Settings settings) {
        EntityManager em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        // Two fields can read one attribute.
        Set<String> names = new HashSet<>(List.of(settings.department(), settings.title(), settings.manager(),
                settings.employeeNumber()));
        List<Values> result = new ArrayList<>();
        UserAttributeValues.ofRealm(em, realm.getId(), names).forEach((userId, values) -> {
            String department = value(values, settings.department());
            if (department != null) {
                result.add(new Values(userId, department, value(values, settings.title()),
                        value(values, settings.manager()), value(values, settings.employeeNumber())));
            }
        });
        return result;
    }

    private static String value(Map<String, String> values, String attribute) {
        String value = values.get(attribute);
        // Keycloak itself stores no value of only spaces. Another writer to its tables can.
        return value == null || value.isBlank() ? null : value.strip();
    }
}
