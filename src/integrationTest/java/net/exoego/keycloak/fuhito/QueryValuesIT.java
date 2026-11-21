package net.exoego.keycloak.fuhito;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A boolean query parameter takes only {@code true} or {@code false}, as a boolean of JSON does. A text query
 * parameter is not empty and not only spaces, as a text of a JSON body is not. Another value fails with 400,
 * {@code invalid.type} or {@code invalid.blank}, and the name of the parameter. JAX-RS read any other boolean as
 * false or as no value, and an empty text as no value. So a typo in {@code dryRun} wrote an import.
 */
class QueryValuesIT {

    private static final byte[] NO_ROWS = new byte[0];

    // Each endpoint with its text parameters, as the OpenAPI document lists them. An ID in the path names nothing,
    // as the check comes before the endpoint reads an ID.
    private static final Map<String, List<String>> TEXTS = Map.ofEntries(
            Map.entry("DELETE /external-ids", List.of("resourceId", "resourceType", "source")),
            Map.entry("GET /assignments", List.of("after", "asOf", "jobTitle", "kind", "occupation", "orgUnitId")),
            Map.entry("GET /assignments/count", List.of("asOf", "jobTitle", "kind", "occupation", "orgUnitId")),
            Map.entry("GET /attribute-definitions", List.of("target")),
            Map.entry("GET /change-log", List.of("resourceId", "resourceType")),
            Map.entry("GET /change-log/count", List.of("resourceId", "resourceType")),
            Map.entry("GET /change-orders", List.of("from", "to")),
            Map.entry("GET /change-orders/count", List.of("from", "to")),
            Map.entry("GET /effective-changes", List.of("from", "to")),
            Map.entry("GET /effective-changes/count", List.of("from", "to")),
            Map.entry("GET /employments", List.of("after", "asOf", "contractEndFrom", "contractEndTo", "employerId",
                    "externalEmployer", "staffNumber")),
            Map.entry("GET /employments/count", List.of("asOf", "contractEndFrom", "contractEndTo", "employerId",
                    "externalEmployer", "staffNumber")),
            Map.entry("GET /export/{list}", List.of("after")),
            Map.entry("GET /external-ids", List.of("resourceId", "resourceType")),
            Map.entry("GET /external-ids/lookup", List.of("externalId", "resourceType", "source")),
            Map.entry("GET /imports/csv/{list}", List.of("asOf", "header")),
            Map.entry("GET /org-units", List.of("after", "asOf", "code", "hierarchy", "q", "unitType")),
            Map.entry("GET /org-units/count", List.of("asOf", "code", "hierarchy", "q", "unitType")),
            Map.entry("GET /org-units/{id}", List.of("asOf", "hierarchy")),
            Map.entry("GET /org-units/{id}/children", List.of("asOf", "hierarchy")),
            Map.entry("GET /org-units/{id}/children/count", List.of("asOf", "hierarchy")),
            Map.entry("GET /org-units/{id}/members", List.of("after", "asOf", "costCenter", "hierarchy", "jobTitle",
                    "occupation", "status", "workLocation")),
            Map.entry("GET /org-units/{id}/members/count", List.of("asOf", "costCenter", "hierarchy", "jobTitle",
                    "occupation", "status", "workLocation")),
            Map.entry("GET /org-units/{id}/path", List.of("asOf", "hierarchy")),
            Map.entry("GET /org-units/{id}/role-holders", List.of("asOf", "role")),
            Map.entry("GET /org-units/{id}/role-holders/count", List.of("asOf", "role")),
            Map.entry("GET /org-units/{id}/subtree", List.of("asOf", "hierarchy")),
            Map.entry("GET /org-units/{id}/subtree/count", List.of("asOf", "hierarchy")),
            Map.entry("GET /people", List.of("after", "q", "staffNumber", "userId")),
            Map.entry("GET /people/count", List.of("q", "staffNumber", "userId")),
            Map.entry("GET /people/{id}/assignments", List.of("asOf")),
            Map.entry("GET /people/{id}/managers", List.of("asOf")),
            Map.entry("GET /people/{id}/qualifications", List.of("asOf")),
            Map.entry("GET /people/{id}/reports", List.of("asOf")),
            Map.entry("GET /people/{id}/reports/count", List.of("asOf")),
            Map.entry("GET /people/{id}/unit-roles", List.of("asOf")),
            Map.entry("GET /people/{id}/unit-roles/count", List.of("asOf")),
            Map.entry("GET /qualifications", List.of("after", "asOf", "kind")),
            Map.entry("GET /qualifications/count", List.of("asOf", "kind")),
            Map.entry("GET /ref-items", List.of("asOf", "kind")),
            Map.entry("GET /ref-items/count", List.of("kind")),
            Map.entry("GET /ref-items/{id}", List.of("asOf")),
            Map.entry("GET /unit-roles", List.of("after", "asOf", "orgUnitId", "role")),
            Map.entry("GET /unit-roles/count", List.of("asOf", "orgUnitId", "role")),
            Map.entry("GET /users/{userId}/assignments", List.of("asOf")),
            Map.entry("GET /users/{userId}/managers", List.of("asOf")),
            Map.entry("GET /users/{userId}/qualifications", List.of("asOf")),
            Map.entry("GET /users/{userId}/reports", List.of("asOf")),
            Map.entry("GET /users/{userId}/reports/count", List.of("asOf")),
            Map.entry("GET /users/{userId}/unit-roles", List.of("asOf")),
            Map.entry("GET /users/{userId}/unit-roles/count", List.of("asOf")),
            Map.entry("POST /imports/csv/{list}", List.of("effectiveDate", "source")),
            Map.entry("PUT /org-units/{id}/parent", List.of("hierarchy")),
            // Every write takes the change order. These are some of them.
            Map.entry("POST /org-units", List.of("changeOrder")),
            Map.entry("PUT /org-units/{id}", List.of("changeOrder")),
            Map.entry("DELETE /employments/{id}", List.of("changeOrder")),
            Map.entry("POST /imports", List.of("changeOrder")),
            Map.entry("POST /restore", List.of("changeOrder")));

    // Each endpoint with its boolean parameters, as the OpenAPI document lists them.
    private static final Map<String, List<String>> BOOLEANS = Map.ofEntries(
            Map.entry("DELETE /attribute-definitions/{id}", List.of("clearValues")),
            Map.entry("GET /org-units", List.of("lateral", "legalEntity", "rollup", "roots")),
            Map.entry("GET /org-units/count", List.of("lateral", "legalEntity", "rollup", "roots")),
            Map.entry("GET /org-units/{id}/children", List.of("lateral")),
            Map.entry("GET /org-units/{id}/children/count", List.of("lateral")),
            Map.entry("GET /org-units/{id}/members", List.of("includeDescendants", "seconded")),
            Map.entry("GET /org-units/{id}/members/count", List.of("includeDescendants", "seconded")),
            Map.entry("GET /org-units/{id}/role-holders", List.of("nearest")),
            Map.entry("GET /org-units/{id}/role-holders/count", List.of("nearest")),
            Map.entry("GET /org-units/{id}/subtree", List.of("lateral")),
            Map.entry("GET /org-units/{id}/subtree/count", List.of("lateral")),
            Map.entry("GET /people/{id}/assignments", List.of("includePath")),
            Map.entry("GET /people/{id}/qualifications", List.of("all")),
            Map.entry("GET /ref-items", List.of("includeInactive")),
            Map.entry("GET /ref-items/count", List.of("includeInactive")),
            Map.entry("GET /users/{userId}/assignments", List.of("includePath")),
            Map.entry("GET /users/{userId}/qualifications", List.of("all")),
            Map.entry("POST /attribute-sync/run", List.of("dryRun")),
            Map.entry("POST /imports/csv/{list}", List.of("dryRun")),
            Map.entry("POST /people/{id}/mask", List.of("clearKeycloakName")),
            Map.entry("POST /restore", List.of("replace")),
            Map.entry("POST /users/{userId}/mask", List.of("clearKeycloakName")));

    // A value of the right form for each text parameter. The endpoint may still fail on it for another reason.
    private static final Map<String, String> GOOD = Map.ofEntries(
            Map.entry("asOf", "2026-04-01"), Map.entry("from", "2026-04-01"), Map.entry("to", "2026-04-01"),
            Map.entry("contractEndFrom", "2026-04-01"), Map.entry("contractEndTo", "2026-04-01"),
            Map.entry("effectiveDate", "2026-04-01"), Map.entry("status", "ACTIVE"), Map.entry("target", "ORG_UNIT"),
            Map.entry("resourceType", "ORG_UNIT"), Map.entry("header", "ja"));

    private static final String SETUP = """
            {"effectiveDate": "2026-04-01",
             "orgUnits": [
               {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validFrom": "2020-04-01"},
               {"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "病棟"}},
                "parentExternalId": "H", "validFrom": "2020-04-01"}],
             "people": [{"externalId": "P1", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}}],
             "employments": [{"externalId": "E1", "personExternalId": "P1", "validFrom": "2020-04-01",
                              "workPercent": 80}],
             "assignments": [
               {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "W", "validFrom": "2020-04-01",
                "allocationPercent": 50}]}
            """;

    // The status, the error, the field, and the type of a response, or the status of another one.
    private static String failure(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 300) {
            return String.valueOf(response.statusCode());
        }
        JsonNode error = Api.json(response);
        JsonNode params = error.path("params");
        StringBuilder failure = new StringBuilder(response.statusCode() + " " + error.path("error").asText());
        for (String param : List.of("field", "type")) {
            if (params.has(param)) {
                failure.append(' ').append(params.get(param).asText());
            }
        }
        return failure.toString();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static HttpResponse<String> send(Api api, String endpoint, String query) throws Exception {
        int space = endpoint.indexOf(' ');
        String path = endpoint.substring(space + 1).replace("{id}", "x").replace("{userId}", "x")
                .replace("{list}", "org-units") + "?" + query;
        return switch (endpoint.substring(0, space)) {
            case "GET" -> api.get(path);
            case "DELETE" -> api.delete(path);
            case "PUT" -> api.put(path, "");
            default -> path.startsWith("/imports/csv/") ? api.postBytes(path, "text/csv; charset=UTF-8", NO_ROWS)
                    : api.post(path, "");
        };
    }

    private static HttpResponse<String> csv(Api api, String query) throws Exception {
        return api.postBytes("/imports/csv/org-units?" + query, "text/csv; charset=UTF-8",
                "externalId,primaryLanguage,name\r\nU1,ja,病棟\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String id(Api api, String resourceType, String externalId) throws Exception {
        return Api.json(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    @Test
    void aBooleanOtherThanTrueOrFalseFailsAndWritesNothing() throws Exception {
        Api api = TestRealm.withEmployer("query-boolean").api;
        ok(api.post("/imports", SETUP));
        String person = id(api, "PERSON", "P1");
        ObjectNode before = RealmExportIT.export(api);

        // Quarkus REST read each of these as false, so the import wrote the row. JSON has only true and false in
        // lower case.
        for (String dryRun : List.of("dryRun=abc", "dryRun=", "dryRun", "dryRun=True", "dryRun=TRUE", "dryRun=1",
                "dryRun=yes", "dryRun=%20true", "dryRun=true%20", "dryRun=%22true%22")) {
            assertEquals("400 invalid.type dryRun boolean", failure(csv(api, "effectiveDate=2026-04-01&" + dryRun)),
                    dryRun);
        }
        assertEquals("400 invalid.type dryRun boolean", failure(api.post("/attribute-sync/run?dryRun=abc", "")));
        assertEquals("400 invalid.type clearKeycloakName boolean",
                failure(api.post("/people/" + person + "/mask?clearKeycloakName=yes", "")));
        assertEquals("400 invalid.type replace boolean",
                failure(api.post("/restore?replace=abc", before.toString())));
        for (String query : List.of("roots=abc", "roots=", "roots", "roots=False")) {
            assertEquals("400 invalid.type roots boolean", failure(api.get("/org-units?" + query)), query);
        }
        // Quarkus REST read these as no value, so the list had every unit.
        for (String query : List.of("lateral=abc", "lateral=", "lateral", "lateral=TRUE")) {
            assertEquals("400 invalid.type lateral boolean", failure(api.get("/org-units?" + query)), query);
        }
        assertEquals("dryRun は boolean 型の値にしてください。別の型の値は変換しません。本文の \"3\" は数値ではなく、3.0 は整数ではありません。"
                + "クエリの数値は JSON と同じ形で、0 から 9 の数字だけで書いてください。クエリの真偽値は小文字の true か false にしてください。",
                Api.json(api.withAcceptLanguage("ja").postBytes("/imports/csv/org-units?effectiveDate=2026-04-01"
                        + "&dryRun=True", "text/csv; charset=UTF-8", NO_ROWS)).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        JsonNode dryRun = ok(csv(api, "effectiveDate=2026-04-01&dryRun=true"));
        assertTrue(dryRun.get("dryRun").asBoolean(), dryRun.toString());
        assertEquals(before, RealmExportIT.export(api));
        JsonNode applied = ok(csv(api, "effectiveDate=2026-04-01&dryRun=false"));
        assertFalse(applied.get("dryRun").asBoolean(), applied.toString());
        assertNotEquals(before, RealmExportIT.export(api));
    }

    @Test
    void anEmptyTextFailsAndWritesNothing() throws Exception {
        Api api = TestRealm.withEmployer("query-text").api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);

        // Quarkus REST read each of these as no value. So the list had the units of today, every person, or the
        // first page, and the import had no source.
        assertEquals("400 invalid.blank asOf", failure(api.get("/org-units?asOf=")));
        assertEquals("400 invalid.blank q", failure(api.get("/people?q=")));
        assertEquals("400 invalid.blank q", failure(api.get("/people?q")));
        assertEquals("400 invalid.blank after", failure(api.get("/people?after=")));
        assertEquals("400 invalid.blank source", failure(csv(api, "effectiveDate=2026-04-01&source=")));
        // This said that the date was missing.
        assertEquals("400 invalid.blank effectiveDate", failure(csv(api, "effectiveDate=")));
        assertEquals("400 invalid.required effectiveDate", failure(csv(api, "dryRun=true")));
        assertEquals("400 invalid.blank changeOrder", failure(csv(api, "effectiveDate=2026-04-01&changeOrder=")));
        assertEquals("q を空や空白だけにすることはできません。値がないときは、本文では null にし、クエリではパラメーターを省いてください。",
                Api.json(api.withAcceptLanguage("ja").get("/people?q=")).get("message").asText());
        assertEquals("q must not be empty or only spaces. For no value, use null in a body, and leave the parameter "
                + "out of a query.", Api.json(api.withAcceptLanguage("en").get("/people?q=")).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        String hospital = id(api, "ORG_UNIT", "H");
        // The realm also has the unit of its legal entity.
        assertEquals(List.of("法人", "病院"), Api.names(Api.json(api.get("/org-units?asOf=2026-04-01&roots=true"))));
        assertEquals(3, Api.json(api.get("/org-units?asOf=2026-04-01&roots=false")).size());
        assertEquals(List.of("法人"), Api.names(Api.json(api.get("/org-units?asOf=2026-04-01&legalEntity=true"))));
        assertEquals(2, Api.json(api.get("/org-units?asOf=2026-04-01&legalEntity=false")).size());
        assertEquals(List.of("法人"), Api.names(Api.json(api.get("/org-units?asOf=2020-03-31"))));
        assertEquals(1, Api.json(api.get("/org-units/" + hospital
                + "/members?asOf=2026-04-01&includeDescendants=true")).size());
        assertEquals(0, Api.json(api.get("/org-units/" + hospital
                + "/members?asOf=2026-04-01&includeDescendants=false")).size());
        assertEquals(1, api.count("/people/count?q=" + encoded("山田")));
        assertEquals(0, api.count("/people/count?q=" + encoded("佐藤")));
        ok(csv(api, "effectiveDate=2026-04-01&source=hr"));
        assertEquals(200, api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=hr&externalId=U1")
                .statusCode());
    }

    // A text of only spaces is blank as String.isBlank says, in a body and in a query. A no-break space is not one.
    @Test
    void aTextOfOnlySpacesFailsAsInABody() throws Exception {
        Api api = TestRealm.create("query-spaces").api;

        for (String spaces : List.of(" ", "   ", "\t", "\n", "\u3000", " \t\u3000")) {
            assertEquals("400 invalid.blank title", failure(api.post("/change-orders",
                    "{\"title\": \"" + spaces.replace("\t", "\\t").replace("\n", "\\n") + "\", "
                            + "\"effectiveDate\": \"bad\"}")), spaces);
            assertEquals("400 invalid.blank q", failure(api.get("/people?q=" + encoded(spaces))), spaces);
        }
        for (String text : List.of("\u00A0", " a ")) {
            assertEquals("400 invalid.date effectiveDate", failure(api.post("/change-orders",
                    "{\"title\": \"" + text + "\", \"effectiveDate\": \"bad\"}")), text);
            assertEquals("200", failure(api.get("/people?q=" + encoded(text))), text);
        }
    }

    @Test
    void everyTextAndBooleanParameterIsChecked() throws Exception {
        Api api = TestRealm.create("query-values").api;
        ObjectNode before = RealmExportIT.export(api);

        for (Map.Entry<String, List<String>> endpoint : TEXTS.entrySet()) {
            for (String name : endpoint.getValue()) {
                String where = endpoint.getKey() + " " + name;
                for (String blank : List.of(name + "=", name, name + "=%20")) {
                    assertEquals("400 invalid.blank " + name, failure(send(api, endpoint.getKey(), blank)),
                            where + " " + blank);
                }
            }
        }
        for (Map.Entry<String, List<String>> endpoint : BOOLEANS.entrySet()) {
            for (String name : endpoint.getValue()) {
                String where = endpoint.getKey() + " " + name;
                for (String bad : List.of(name + "=abc", name + "=", name, name + "=True", name + "=FALSE",
                        name + "=0")) {
                    assertEquals("400 invalid.type " + name + " boolean", failure(send(api, endpoint.getKey(), bad)),
                            where + " " + bad);
                }
            }
        }
        assertEquals(before, RealmExportIT.export(api));

        // The endpoint gets a value of the right form, and then answers as before.
        for (Map.Entry<String, List<String>> endpoint : TEXTS.entrySet()) {
            for (String name : endpoint.getValue()) {
                String failure = failure(send(api, endpoint.getKey(), name + "=" + GOOD.getOrDefault(name, "x")));
                assertFalse(failure.equals("400 invalid.blank " + name), endpoint.getKey() + " " + name);
            }
        }
        for (Map.Entry<String, List<String>> endpoint : BOOLEANS.entrySet()) {
            for (String name : endpoint.getValue()) {
                for (String good : List.of("true", "false")) {
                    String failure = failure(send(api, endpoint.getKey(), name + "=" + good));
                    assertFalse(failure.equals("400 invalid.type " + name + " boolean"),
                            endpoint.getKey() + " " + name + "=" + good);
                }
            }
        }
    }

    @Test
    void theCheckComesAfterTheTokenAndBeforeTheRoles() throws Exception {
        TestRealm realm = TestRealm.create("query-values-caller");
        Api usersOnly = PermissionsIT.administrator(KEYCLOAK.getKeycloakAdminClient(), realm.name,
                "realm-management", "users-viewer", "view-users");
        Api noToken = new Api(realm.name, null);

        assertEquals(401, noToken.get("/org-units?roots=abc").statusCode());
        assertEquals(401, noToken.get("/org-units?asOf=").statusCode());
        assertEquals(403, usersOnly.get("/org-units?roots=true&asOf=2026-04-01").statusCode());
        assertEquals("400 invalid.type roots boolean", failure(usersOnly.get("/org-units?roots=abc")));
        assertEquals("400 invalid.blank asOf", failure(usersOnly.get("/org-units?asOf=")));
    }

    @Test
    void theFirstParameterInTheOrderOfNamesFails() throws Exception {
        Api api = TestRealm.create("query-values-order").api;

        assertEquals("400 invalid.type max integer", failure(api.get("/people?q=&max=abc")));
        assertEquals("400 invalid.blank asOf", failure(api.get("/org-units?roots=abc&asOf=")));
        assertEquals("400 invalid.parameter_twice roots", failure(api.get("/org-units?roots=abc&roots=abc")));
        assertEquals("400 invalid.unknown_parameter a", failure(api.get("/org-units?roots=abc&a=1")));
    }
}
