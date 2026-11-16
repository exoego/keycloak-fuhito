package net.exoego.keycloak.fuhito;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;

import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentExportRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A new realm for one test class, with helpers to fill it.
 */
final class TestRealm {

    // Keycloak makes two new RSA keys for each new realm, which is slow. So the run makes one key,
    // and every realm gets it.
    private static final String PRIVATE_KEY = privateKey();

    final String name;
    final Api api;
    private final Keycloak admin;

    private TestRealm(String name, Keycloak admin) {
        this.name = name;
        this.admin = admin;
        this.api = new Api(name, admin.tokenManager().getAccessTokenString());
    }

    static TestRealm create(String prefix) {
        return create(prefix, Map.of());
    }

    static TestRealm create(String prefix, Map<String, String> attributes) {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        var realm = new RealmRepresentation();
        realm.setRealm(prefix + "-" + UUID.randomUUID());
        realm.setEnabled(true);
        realm.setAttributes(new HashMap<>(attributes));
        withTimeZone(realm);
        createRealm(admin, realm);
        return new TestRealm(realm.getRealm(), admin);
    }

    private static String privateKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Creates a realm with the keys of the tests. */
    static void createRealm(Keycloak admin, RealmRepresentation realm) {
        var keys = new MultivaluedHashMap<String, ComponentExportRepresentation>();
        keys.add("org.keycloak.keys.KeyProvider", key("rsa", "rsa", Map.of("privateKey", PRIVATE_KEY)));
        keys.add("org.keycloak.keys.KeyProvider", key("hmac", "hmac-generated", Map.of("algorithm", "HS512")));
        keys.add("org.keycloak.keys.KeyProvider", key("aes", "aes-generated", Map.of()));
        realm.setComponents(keys);
        admin.realms().create(realm);
    }

    private static ComponentExportRepresentation key(String name, String providerId, Map<String, String> values) {
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("priority", "100");
        values.forEach(config::putSingle);
        var key = new ComponentExportRepresentation();
        key.setName(name);
        key.setProviderId(providerId);
        key.setConfig(config);
        return key;
    }

    /**
     * Gives a realm the time zone that the extension requires, unless the test set one.
     * UTC is the zone of the Keycloak container, so tests can compute the date of the realm.
     */
    static void withTimeZone(RealmRepresentation realm) {
        Map<String, String> attributes = new HashMap<>();
        if (realm.getAttributes() != null) {
            attributes.putAll(realm.getAttributes());
        }
        attributes.putIfAbsent("fuhito.timeZone", "UTC");
        attributes.values().removeIf(String::isEmpty);
        realm.setAttributes(attributes);
    }

    String id() {
        return admin.realm(name).toRepresentation().getId();
    }

    /** Creates a user and returns its ID. */
    String user(String username) {
        return user(username, null, null);
    }

    /** Creates a user with a name and returns its ID. */
    String user(String username, String firstName, String lastName) {
        var user = new UserRepresentation();
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = admin.realm(name).users().create(user)) {
            assertEquals(201, response.getStatus());
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    /** Returns the first and the last name that Keycloak has for a user, with an empty text for a missing one. */
    List<String> nameOfUser(String userId) {
        var user = admin.realm(name).users().get(userId).toRepresentation();
        return List.of(Objects.toString(user.getFirstName(), ""), Objects.toString(user.getLastName(), ""));
    }

    /** Turns on Keycloak Organizations, creates an organization, and returns its ID. */
    String organization(String alias) {
        var realm = new RealmRepresentation();
        realm.setOrganizationsEnabled(true);
        admin.realm(name).update(realm);
        return OrganizationLinkIT.createOrganization(admin, name, alias);
    }

    void removeUser(String userId) {
        admin.realm(name).users().delete(userId).close();
    }

    void remove() {
        admin.realm(name).remove();
    }

    /**
     * Creates a realm that has one legal entity. Such a realm can store employments, and a request need not
     * name the employer.
     */
    static TestRealm withEmployer(String prefix) {
        return withEmployer(prefix, Map.of());
    }

    static TestRealm withEmployer(String prefix, Map<String, String> attributes) {
        TestRealm realm = create(prefix, attributes);
        realm.legalEntity("法人");
        return realm;
    }

    /** Creates a legal entity that exists from 1990 and returns its ID. */
    String legalEntity(String entityName) {
        return legalEntity(api, entityName);
    }

    // The checked exceptions of the HTTP client become errors, so that a setup method need not declare them.
    static String legalEntity(Api api, String entityName) {
        try {
            var type = api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"legal-entity\", "
                    + "\"name\": \"法人\", \"legalEntity\": true}");
            // The type is there already when the realm has a legal entity.
            assertTrue(type.statusCode() == 201 || type.statusCode() == 409, type.body());
            var response = api.post("/org-units", "{\"name\": \"" + entityName
                    + "\", \"unitType\": \"legal-entity\", \"validFrom\": \"1990-01-01\"}");
            assertEquals(201, response.statusCode(), response.body());
            return Api.json(response).get("id").asText();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Creates a reference item and returns its ID. */
    String refItem(String kind, String code, String itemName) throws Exception {
        var response = api.post("/ref-items",
                "{\"kind\": \"" + kind + "\", \"code\": \"" + code + "\", \"name\": \"" + itemName + "\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    /** Defines a free attribute, so that writes may give it. */
    void attribute(String target, String name, String type) throws Exception {
        var response = api.post("/attribute-definitions",
                "{\"target\": \"" + target + "\", \"name\": \"" + name + "\", \"type\": \"" + type + "\"}");
        assertEquals(201, response.statusCode(), response.body());
    }
}
