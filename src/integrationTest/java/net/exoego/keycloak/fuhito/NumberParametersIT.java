package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A whole number in a query has the form of a number of JSON and is inside the limits of the OpenAPI document. Another
 * value fails with 400, {@code invalid.type} or {@code invalid.out_of_range}, and the name of the parameter. JAX-RS
 * answered 404 to a value that is no number, took {@code ５}, {@code +5}, and an empty value, and the pages kept
 * {@code first} and {@code max} in their limits. A {@code version} that was no number read as none.
 */
class NumberParametersIT {

    private static final String INT = " 0 2147483647";
    private static final String LONG = " 0 9223372036854775807";
    private static final String PAGE = " 0 1000";
    private static final String VERSION = " -9223372036854775808 9223372036854775807";

    // Every read with first or max, and every write with version. The check comes before the endpoint reads an ID.
    private static final List<String> LISTS = List.of("/assignments", "/change-log", "/change-orders/x/changes",
            "/change-orders", "/effective-changes", "/employments", "/export/org-units", "/hierarchies",
            "/org-units/x/children", "/org-units/x/members", "/org-units/x/role-holders", "/org-units/x/subtree",
            "/org-units", "/people/x/reports", "/people/x/unit-roles", "/people", "/qualifications", "/ref-items",
            "/unit-roles", "/users/x/reports", "/users/x/unit-roles");
    private static final List<String> WRITES = List.of("/assignments/x/end", "/assignments/x",
            "/attribute-definitions/x", "/change-orders/x", "/employments/x/end", "/employments/x", "/external-ids",
            "/hierarchies/x", "/org-units/x/end", "/org-units/x/organization", "/org-units/x", "/people/x/data",
            "/people/x", "/qualifications/x", "/ref-items/x", "/ref-kinds/x", "/reporting-lines/x", "/unit-roles/x");

    // The status, the error, the field, and the type or the limits of a response, or the status of another one.
    private static String failure(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 300) {
            return String.valueOf(response.statusCode());
        }
        JsonNode error = Api.json(response);
        JsonNode params = error.path("params");
        StringBuilder failure = new StringBuilder(response.statusCode() + " " + error.path("error").asText());
        for (String param : List.of("field", "type", "min", "max")) {
            if (params.has(param)) {
                failure.append(' ').append(params.get(param).asText());
            }
        }
        return failure.toString();
    }

    private static String employment(TestRealm realm) throws Exception {
        Api api = realm.api;
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        HttpResponse<String> staff = api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2020-04-01\", \"staffNumber\": \"N-1\"}");
        assertEquals(201, staff.statusCode(), staff.body());
        return Api.json(staff).get("employment").get("id").asText();
    }

    @Test
    void aValueThatIsNoWholeNumberFails() throws Exception {
        TestRealm realm = TestRealm.withEmployer("number-type");
        Api api = realm.api;
        String employment = "/employments/" + employment(realm);
        ObjectNode before = RealmExportIT.export(api);

        // JAX-RS answered 404, and a DELETE got version.required.
        for (String max : List.of("abc", "1.5", "1e2", "1.0", "%EF%BC%95", "%2B5", "%205", "5%20", "007", "-", "0x10",
                "", "1%2C000")) {
            assertEquals("400 invalid.type max integer", failure(api.get("/people?max=" + max)), max);
        }
        assertEquals("400 invalid.type max integer", failure(api.get("/people?max")));
        assertEquals("400 invalid.type first integer", failure(api.get("/people?first=")));
        assertEquals("400 invalid.type first integer", failure(api.get("/people?first=1.5")));
        for (String after : List.of("abc", "1.5", "", "%EF%BC%91")) {
            assertEquals("400 invalid.type after integer", failure(api.get("/change-log?after=" + after)), after);
        }
        for (String version : List.of("abc", "2e0", "%EF%BC%92", "%2B2", "%202%20", "", "1.0")) {
            assertEquals("400 invalid.type version integer", failure(api.delete(employment + "?version=" + version)),
                    version);
        }
        assertEquals("400 invalid.type version integer", failure(api.delete(employment + "?version")));
        for (String list : LISTS) {
            assertEquals("400 invalid.type max integer", failure(api.get(list + "?max=abc")), list);
        }
        for (String write : WRITES) {
            assertEquals("400 invalid.type version integer", failure(api.delete(write + "?version=abc")), write);
        }
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aNumberOutsideTheLimitsOfTheDocumentFails() throws Exception {
        TestRealm realm = TestRealm.withEmployer("number-range");
        Api api = realm.api;
        String employment = "/employments/" + employment(realm);
        ObjectNode before = RealmExportIT.export(api);

        // A page read 5000 as 1000, -1 as 0, and a first of -1 as 0. The change log took -1. JAX-RS answered 404 to
        // a number too large for its type, and a DELETE got version.required.
        for (String max : List.of("5000", "1001", "-1", "99999999999", "2147483648", "99999999999999999999")) {
            assertEquals("400 invalid.out_of_range max" + PAGE, failure(api.get("/people?max=" + max)), max);
        }
        for (String first : List.of("-1", "99999999999", "2147483648")) {
            assertEquals("400 invalid.out_of_range first" + INT, failure(api.get("/people?first=" + first)), first);
        }
        for (String after : List.of("-1", "9223372036854775808", "99999999999999999999")) {
            assertEquals("400 invalid.out_of_range after" + LONG, failure(api.get("/change-log?after=" + after)),
                    after);
        }
        for (String version : List.of("9223372036854775808", "-9223372036854775809", "99999999999999999999")) {
            assertEquals("400 invalid.out_of_range version" + VERSION,
                    failure(api.delete(employment + "?version=" + version)), version);
        }
        for (String list : LISTS) {
            assertEquals("400 invalid.out_of_range max" + PAGE, failure(api.get(list + "?max=1001")), list);
            if (!list.startsWith("/export/")) {
                assertEquals("400 invalid.out_of_range first" + INT, failure(api.get(list + "?first=-1")), list);
            }
        }
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aNumberAtEachLimitWorks() throws Exception {
        TestRealm realm = TestRealm.withEmployer("number-limits");
        Api api = realm.api;
        employment(realm);
        String hierarchy = "/hierarchies/" + Api.json(api.post("/hierarchies", "{\"code\": \"project\", "
                + "\"name\": \"プロジェクト\"}")).get("id").asText();
        int people = Api.json(api.get("/people")).size();

        assertEquals(0, Api.json(api.get("/people?max=0")).size());
        assertEquals(0, Api.json(api.get("/people?max=-0")).size());
        assertEquals(people, Api.json(api.get("/people?max=1000")).size());
        assertEquals(people, Api.json(api.get("/people?first=0")).size());
        assertEquals(0, Api.json(api.get("/people?first=2147483647")).size());
        assertEquals(0, Api.json(api.get("/export/people?max=0")).size());
        assertEquals(people, Api.json(api.get("/export/people?max=1000")).size());
        JsonNode log = Api.json(api.get("/change-log?after=0&first=0&max=1000"));
        assertEquals(1, log.get(0).get("sequence").asLong());
        assertEquals(log.size() - 1, Api.json(api.get("/change-log?after=1")).size());
        assertEquals(0, Api.json(api.get("/change-log?after=9223372036854775807")).size());

        // The version passes the check and then fails as any other version that is not the one stored.
        for (String version : List.of("-9223372036854775808", "-1", "0", "9223372036854775807")) {
            assertEquals("409 version.conflict", failure(api.delete(hierarchy + "?version=" + version)), version);
        }
        assertEquals("400 version.required", failure(api.delete(hierarchy)));
        assertEquals(204, api.delete(hierarchy + "?version=" + api.version(hierarchy)).statusCode());
    }

    // The limits are in the OpenAPI document, so the check tells a caller nothing new.
    @Test
    void theCheckComesAfterTheTokenAndBeforeTheRoles() throws Exception {
        TestRealm realm = TestRealm.create("number-caller");
        Api usersOnly = PermissionsIT.administrator(KEYCLOAK.getKeycloakAdminClient(), realm.name,
                "realm-management", "users-viewer", "view-users");
        Api noToken = new Api(realm.name, null);

        assertEquals(401, noToken.get("/people?max=abc").statusCode());
        assertEquals(403, usersOnly.get("/people?max=5").statusCode());
        assertEquals("400 invalid.type max integer", failure(usersOnly.get("/people?max=abc")));
        assertEquals("400 invalid.out_of_range max" + PAGE, failure(usersOnly.get("/people?max=5000")));
    }

    @Test
    void theFirstParameterInTheOrderOfNamesFails() throws Exception {
        Api api = TestRealm.create("number-order").api;

        assertEquals("400 invalid.parameter_twice max", failure(api.get("/people?max=abc&max=abc")));
        assertEquals("400 invalid.type first integer", failure(api.get("/people?max=5000&first=abc")));
        assertEquals("400 invalid.unknown_parameter a", failure(api.get("/people?max=abc&a=1")));
    }
}
