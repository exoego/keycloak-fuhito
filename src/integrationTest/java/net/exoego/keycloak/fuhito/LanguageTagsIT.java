package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.DuplicateLanguagesIT.assertFails;
import static net.exoego.keycloak.fuhito.DuplicateLanguagesIT.counts;
import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageTagsIT {

    private static TestRealm realm;
    private static Api api;

    @BeforeAll
    static void createRealm() {
        realm = TestRealm.create("language-tags");
        api = realm.api;
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    static List<String> keys(JsonNode object) {
        List<String> keys = new ArrayList<>();
        object.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static String idOf(Api to, String resourceType, String externalId) throws Exception {
        return ok(to.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    @Test
    void aWriteStoresEachTagAsSent() throws Exception {
        JsonNode unit = created(api.post("/org-units", """
                {"primaryLanguage": "JA", "validFrom": "2026-04-01", "names": {"JA": {"name": "総務部"},
                  "en": {"name": "General Affairs"}, "en-us": {"name": "General Affairs (US)"},
                  "iw": {"name": "מנהלה"}}}
                """));
        assertEquals("JA", unit.get("primaryLanguage").asText());
        assertEquals(List.of("JA", "en", "en-us", "iw"), keys(unit.get("names")));
        JsonNode read = ok(api.get("/org-units/" + unit.get("id").asText() + "?asOf=2026-04-01"));
        assertEquals("JA", read.get("primaryLanguage").asText());
        assertEquals(unit.get("names"), read.get("names"));
        JsonNode person = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"鈴木\"}, \"EN-us\": {\"name\": \"Suzuki\"}}}"));
        assertEquals(List.of("EN-us", "ja"), keys(person.get("names")));

        // primaryLanguage is the same text as a key.
        assertFails(realm, "invalid.primary_language", Map.of("value", "ja"), () -> api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"JA\": {\"name\": \"人事部\"}}, "
                        + "\"validFrom\": \"2026-04-01\"}"));
        assertFails(realm, "invalid.primary_language", Map.of("value", "en-US"), () -> api.putWithVersion(
                "/people/" + person.get("id").asText(), "{\"primaryLanguage\": \"en-US\", \"names\": "
                        + "{\"en-us\": {\"name\": \"Suzuki\"}}}"));
    }

    @Test
    void aReaderGetsTheNameOfItsTagInAnyCaseElseOfItsLanguageAlone() throws Exception {
        String id = created(api.post("/org-units", """
                {"primaryLanguage": "JA", "validFrom": "2026-04-01", "names": {"JA": {"name": "経理部"},
                  "en": {"name": "Accounting"}, "en-us": {"name": "Accounting (US)"},
                  "iw": {"name": "הנהלת חשבונות"}}}
                """)).get("id").asText();
        Map<String, String> names = new LinkedHashMap<>();
        for (String language : List.of("ja", "EN-US", "en-GB", "iw", "IW-il", "he", "fr")) {
            names.put(language, nameIn(language, id));
        }
        // Locale would turn iw into he. The tag of the reader goes through no such mapping.
        assertEquals(List.of("経理部", "Accounting (US)", "Accounting", "הנהלת חשבונות", "הנהלת חשבונות", "経理部",
                "経理部"), List.copyOf(names.values()), names.toString());

        // The tag with the highest q wins, and the first one on a tie. * names no language.
        Map<String, String> weighted = new LinkedHashMap<>();
        weighted.put("fr;q=0.5, iw;q=0.9, en;q=0.8", "הנהלת חשבונות");
        weighted.put("*, en-GB;q=0.8, he;q=0.9", "経理部");
        weighted.put("en-GB;q=0.8, EN-us", "Accounting (US)");
        weighted.put("en;q=0.5, iw;Q=0.5", "Accounting");
        weighted.put("*", "経理部");
        for (Map.Entry<String, String> header : weighted.entrySet()) {
            assertEquals(header.getValue(), nameIn(header.getKey(), id), header.getKey());
        }
    }

    private static String nameIn(String acceptLanguage, String id) throws Exception {
        return ok(api.withAcceptLanguage(acceptLanguage).get("/org-units/" + id + "?asOf=2026-04-01")).get("name")
                .asText();
    }

    @Test
    void anImportRowReplacesAStoredKeyInAnotherCaseWithItsOwnKey() throws Exception {
        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "orgUnits": [{"externalId": "R", "primaryLanguage": "ja",
                  "names": {"ja": {"name": "放射線科"}, "en": {"name": "Radiology"}}}]}
                """));
        String id = idOf(api, "ORG_UNIT", "R");

        // The stored primary language is ja, and the key becomes JA.
        assertImportFails("orgUnits invalid.primary_language ja", """
                {"effectiveDate": "2027-04-01", "orgUnits": [{"externalId": "R", "names": {"JA": {"name": "放射線部"}}}]}
                """);
        ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "orgUnits": [{"externalId": "R", "primaryLanguage": "JA",
                  "names": {"JA": {"shortName": "放射線"}}}]}
                """));
        JsonNode changed = ok(api.get("/org-units/" + id + "?asOf=2027-04-01"));
        assertEquals("JA", changed.get("primaryLanguage").asText());
        assertEquals(List.of("JA", "en"), keys(changed.get("names")));
        assertEquals("放射線科", changed.get("names").get("JA").get("name").asText());
        assertEquals("放射線", changed.get("names").get("JA").get("shortName").asText());

        // A key with null removes the stored key in another case.
        ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "orgUnits": [{"externalId": "R", "names": {"EN": null}}]}
                """));
        assertEquals(List.of("JA"), keys(ok(api.get("/org-units/" + id + "?asOf=2027-04-01")).get("names")));
        assertEquals(List.of("en", "ja"), keys(ok(api.get("/org-units/" + id + "?asOf=2026-04-01")).get("names")));
    }

    private static void assertImportFails(String error, String document) throws Exception {
        List<String> before = counts(realm);
        HttpResponse<String> response = api.post("/imports", document);
        assertEquals(400, response.statusCode(), response.body());
        JsonNode failed = Api.json(response).get("errors").get(0);
        assertEquals(error, failed.get("list").asText() + " " + failed.get("error").asText() + " "
                + failed.get("params").path("value").asText(), response.body());
        assertEquals(before, counts(realm));
    }
}
