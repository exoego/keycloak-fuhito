package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.DuplicateLanguagesIT.assertFails;
import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AcceptLanguageIT {

    private static final String UNIT = """
            {"primaryLanguage": "ja", "validFrom": "2026-04-01", "names": {"ja": {"name": "総務部"},
              "en": {"name": "General Affairs"}}}
            """;

    private static TestRealm realm;
    private static Api api;
    private static String unit;

    @BeforeAll
    static void createRealm() throws Exception {
        realm = TestRealm.create("accept-language");
        api = realm.api;
        HttpResponse<String> created = api.post("/org-units", UNIT);
        assertEquals(201, created.statusCode(), created.body());
        unit = Api.json(created).get("id").asText();
    }

    private static String nameIn(String acceptLanguage) throws Exception {
        return ok(api.withAcceptLanguage(acceptLanguage).get("/org-units/" + unit + "?asOf=2026-04-01")).get("name")
                .asText();
    }

    @Test
    void aHeaderInTheFormOfHttpGivesTheLanguageOfTheNames() throws Exception {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("ja, en;q=0.5", "総務部");
        names.put("en, ja;q=0.5", "General Affairs");
        // RFC 9110 asks a recipient to ignore an empty element of a list.
        names.put("en, , ja", "General Affairs");
        names.put("ja;q=0.5, en;Q=0.9", "General Affairs");
        names.put("ja ; q=0.5 , en\t;\tq=0.9 ,fr;q=0.1", "General Affairs");
        for (Map.Entry<String, String> header : names.entrySet()) {
            assertEquals(header.getValue(), nameIn(header.getKey()), header.getKey());
        }
        assertEquals("総務部", nameIn(null));
    }

    @Test
    void aHeaderInAnotherFormFailsBeforeAnyReadOrWrite() throws Exception {
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("ja;q=2, en;q=0.5", "ja;q=2");
        bad.put("ja;q=.5, en;q=0.4", "ja;q=.5");
        bad.put("ja;q=0.5555", "ja;q=0.5555");
        bad.put("en_US, ja;q=0.5", "en_US");
        bad.put("ja;level=1", "ja;level=1");
        bad.put("en, ja ; q=2 , fr", "ja ; q=2");
        for (Map.Entry<String, String> header : bad.entrySet()) {
            Api caller = api.withAcceptLanguage(header.getKey());
            assertBadHeader(header.getValue(), () -> caller.get("/org-units/" + unit + "?asOf=2026-04-01"));
            assertBadHeader(header.getValue(), () -> caller.post("/org-units", UNIT));
        }

        // The header comes before the query, the body, and the change order.
        Api caller = api.withAcceptLanguage("ja;q=2");
        assertBadHeader("ja;q=2", () -> caller.post("/org-units?nope=1&changeOrder=no-such-order",
                "{\"nope\": 1}"));
    }

    private static void assertBadHeader(String element, DuplicateLanguagesIT.Write write) throws Exception {
        assertFails(realm, "invalid.header", Map.of("field", "Accept-Language", "value", element), () -> {
            HttpResponse<String> response = write.send();
            // A bad header cannot give the language of the message.
            assertEquals("The header Accept-Language has the part \"" + element + "\", which is not in the form of "
                    + "RFC 9110. Fix the part, or leave it out.", Api.json(response).get("message").asText());
            return response;
        });
    }

    @Test
    void theCheckComesAfterTheTokenAndBeforeTheRoles() throws Exception {
        Api usersOnly = PermissionsIT.administrator(KEYCLOAK.getKeycloakAdminClient(), realm.name,
                "realm-management", "users-viewer", "view-users");

        assertEquals(401, new Api(realm.name, null).withAcceptLanguage("ja;q=2").get("/org-units").statusCode());
        assertEquals(403, usersOnly.get("/org-units").statusCode());
        assertBadHeader("ja;q=2", () -> usersOnly.withAcceptLanguage("ja;q=2").get("/org-units"));
    }

    @Test
    void theMessageOfAnErrorIsInTheLanguageOfTheNames() throws Exception {
        String english = "The org unit \"no-such-id\" does not exist.";
        String japanese = "組織単位「no-such-id」は存在しません。";
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("en;q=0.5, ja", japanese);
        messages.put("en;q=0.5 , ja;Q=0.9", japanese);
        messages.put("ja, , en", japanese);
        messages.put("ja;q=0, en", english);
        messages.put("ja;q=0", english);
        messages.put("*, ja;q=0.5", japanese);
        messages.put("fr, ja;q=0.5", english);
        for (Map.Entry<String, String> header : messages.entrySet()) {
            HttpResponse<String> response = api.withAcceptLanguage(header.getKey()).get("/org-units/no-such-id");
            assertEquals(404, response.statusCode(), response.body());
            JsonNode error = Api.json(response);
            assertEquals("org_unit.not_found", error.get("error").asText());
            assertEquals(header.getValue(), error.get("message").asText(), header.getKey());
        }
    }
}
