package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * People whose read gives the name of their Keycloak user, joined in the order of the realm attribute
 * {@code fuhito.nameOrder}. An administrator changes the attributes of the realm in Keycloak. Each test has a realm
 * of its own, because the order belongs to the realm.
 */
class NameOrderChangesIT {

    private static final Keycloak ADMIN = KEYCLOAK.getKeycloakAdminClient();
    private static final String ORDER = "fuhito.nameOrder";

    private static String person(Api api, String body) throws Exception {
        HttpResponse<String> response = api.post("/people", body);
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String personOf(TestRealm realm, String username, String firstName, String lastName)
            throws Exception {
        return person(realm.api, "{\"userId\": \"" + realm.user(username, firstName, lastName) + "\"}");
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static List<String> kindsAndCauses(Api api, String personId) throws Exception {
        return ok(api.get("/change-log?resourceId=" + personId)).valueStream()
                .map(entry -> entry.get("kind").asText() + " " + entry.path("cause").asText())
                .toList();
    }

    private static Map<String, Long> versions(Api api, List<String> people) throws Exception {
        Map<String, Long> versions = new LinkedHashMap<>();
        for (String person : people) {
            versions.put(person, api.version("/people/" + person));
        }
        return versions;
    }

    // Sends every attribute of the realm with one of them changed, as the admin console does. With no value the
    // attribute is left out, which removes it.
    private static void setAttribute(TestRealm realm, String name, String value) {
        RealmResource resource = ADMIN.realm(realm.name);
        Map<String, String> attributes = new HashMap<>(resource.toRepresentation().getAttributes());
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
        var update = new RealmRepresentation();
        update.setAttributes(attributes);
        resource.update(update);
    }

    private static String name(Api api, String personId) throws Exception {
        return ok(api.get("/people/" + personId)).get("name").asText();
    }

    private static long fuhitoAdminEvents(TestRealm realm) {
        return ADMIN.realm(realm.name).getAdminEvents().stream()
                .filter(event -> event.getResourceType().startsWith("FUHITO_"))
                .count();
    }

    @Test
    void aChangeOfTheOrderAddsAPropagatedChangeOfEachPersonWhoseNameItChanges() throws Exception {
        TestRealm realm = TestRealm.create("name-order");
        Api api = realm.api;
        var events = new RealmRepresentation();
        events.setAdminEventsEnabled(true);
        ADMIN.realm(realm.name).update(events);
        String taro = personOf(realm, "taro", "Taro", "Yamada");
        // The read strips the spaces around each part, also the ideographic space.
        String jiro = personOf(realm, "jiro", " Jiro ", "\u3000Sato");
        List<String> changed = List.of(taro, jiro);
        // These reads stay the same in either order.
        List<String> unchanged = List.of(
                person(api, "{\"userId\": \"" + realm.user("hanako", "Hanako", "Suzuki") + "\", "
                        + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"鈴木 花子\"}}}"),
                personOf(realm, "madonna", "Madonna", null),
                personOf(realm, "kim", "Kim", "Kim\u3000"));
        String goro = realm.user("goro", "Goro", "Kato");
        String removed = person(api, "{\"userId\": \"" + goro + "\"}");
        realm.removeUser(goro);
        Map<String, Long> versions = versions(api, changed);
        Map<String, Long> others = versions(api, unchanged);
        long adminEvents = fuhitoAdminEvents(realm);
        DerivedReads reads = DerivedReads.before(api);

        setAttribute(realm, ORDER, "family-first");

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of("Yamada Taro", "Sato Jiro"), List.of(name(api, taro), name(api, jiro)));
        for (String person : changed) {
            assertEquals(versions.get(person) + 1, api.version("/people/" + person));
            assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_REALM_NAME_ORDER_CHANGED"),
                    kindsAndCauses(api, person));
        }
        JsonNode log = ok(api.get("/change-log?resourceId=" + taro));
        JsonNode propagated = log.get(1);
        assertEquals(List.of("FUHITO_PERSON", "UPDATE", "fuhito/people/" + taro), List.of(
                propagated.get("resourceType").asText(), propagated.get("operation").asText(),
                propagated.get("resourcePath").asText()));
        for (String absent : List.of("actorUserId", "changeOrderId", "causeSequence")) {
            assertFalse(propagated.has(absent), absent);
        }
        Duration since = Duration.between(Instant.parse(log.get(0).get("recordedAt").asText()),
                Instant.parse(propagated.get("recordedAt").asText()));
        assertTrue(!since.isNegative() && since.compareTo(Duration.ofMinutes(5)) < 0, since.toString());
        assertEquals(others, versions(api, unchanged));
        for (String person : unchanged) {
            assertEquals(List.of("WRITE "), kindsAndCauses(api, person));
        }
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_USER_REMOVED"), kindsAndCauses(api, removed));
        // A propagated change records no admin event.
        assertEquals(adminEvents, fuhitoAdminEvents(realm));

        DerivedReads back = DerivedReads.before(api);

        setAttribute(realm, ORDER, null);

        back.assertEveryChangeIsLogged();
        assertEquals("Taro Yamada", name(api, taro));
        assertEquals(versions.get(taro) + 2, api.version("/people/" + taro));
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_REALM_NAME_ORDER_CHANGED",
                "PROPAGATED KEYCLOAK_REALM_NAME_ORDER_CHANGED"), kindsAndCauses(api, taro));
        assertEquals(others, versions(api, unchanged));
    }

    @Test
    void theSameOrderAgainOrAnotherValueForTheGivenNameFirstGivesNoPropagatedChange() throws Exception {
        TestRealm realm = TestRealm.create("name-order-same");
        Api api = realm.api;
        String person = personOf(realm, "taro", "Taro", "Yamada");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        // Keycloak sends an event for each of these, but the given name stays first.
        setAttribute(realm, ORDER, "given-first");
        setAttribute(realm, ORDER, "something else");
        setAttribute(realm, ORDER, null);

        reads.assertEveryChangeIsLogged();
        assertEquals("Taro Yamada", name(api, person));
        assertEquals(version, api.version("/people/" + person));
        assertEquals(List.of("WRITE "), kindsAndCauses(api, person));

        setAttribute(realm, ORDER, "family-first");
        setAttribute(realm, ORDER, "family-first");

        assertEquals("Yamada Taro", name(api, person));
        assertEquals(version + 1, api.version("/people/" + person));
        assertEquals(List.of("WRITE ", "PROPAGATED KEYCLOAK_REALM_NAME_ORDER_CHANGED"), kindsAndCauses(api, person));
    }

    @Test
    void aChangeOfAnotherRealmAttributeGivesNoPropagatedChange() throws Exception {
        TestRealm realm = TestRealm.create("name-order-other", Map.of(ORDER, "family-first"));
        Api api = realm.api;
        String person = personOf(realm, "taro", "Taro", "Yamada");
        long version = api.version("/people/" + person);
        DerivedReads reads = DerivedReads.before(api);

        setAttribute(realm, "fuhito.changeLog.keepPropagatedDays", "30");
        setAttribute(realm, "example.nameOrder", "given-first");
        var rename = new RealmRepresentation();
        rename.setDisplayName("Name order");
        ADMIN.realm(realm.name).update(rename);

        reads.assertEveryChangeIsLogged();
        assertEquals("Yamada Taro", name(api, person));
        assertEquals(version, api.version("/people/" + person));
        assertEquals(List.of("WRITE "), kindsAndCauses(api, person));
    }
}
