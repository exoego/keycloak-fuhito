package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request with a query parameter that its endpoint does not take, or with one query parameter twice, fails with
 * 400 and writes nothing. JAX-RS would drop the value with no error.
 */
class QueryParametersIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String UNKNOWN = "invalid.unknown_parameter";
    private static final String TWICE = "invalid.parameter_twice";
    private static final String UNIT = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, "
            + "\"validFrom\": \"2021-04-01\"}";

    private static void assertParameter(HttpResponse<String> response, String code, String field) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals(code, error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(Map.of("field", field)), error.get("params"), response.body());
    }

    private static HttpResponse<String> csv(Api api, String query) throws Exception {
        return api.postBytes("/imports/csv/org-units?" + query, "text/csv; charset=UTF-8",
                "externalId,primaryLanguage,name\r\nU1,ja,病棟\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aParameterThatTheEndpointDoesNotTakeFailsAndWritesNothing() throws Exception {
        Api api = TestRealm.create("query-unknown").api;
        assertEquals(201, api.post("/org-units", UNIT).statusCode());
        ObjectNode before = RealmExportIT.export(api);

        // Quarkus REST matched a name in another case, so the endpoint read asof as asOf and dryrun as dryRun.
        // It dropped as_of and dry_run, and the import wrote.
        assertParameter(api.get("/org-units?asof=2020-01-01"), UNKNOWN, "asof");
        assertParameter(api.get("/org-units?as_of=2020-01-01"), UNKNOWN, "as_of");
        assertParameter(api.get("/ref-items/count?kind=UNIT_TYPE&asOf=bad"), UNKNOWN, "asOf");
        assertParameter(csv(api, "effectiveDate=2026-04-01&dryrun=true"), UNKNOWN, "dryrun");
        assertParameter(csv(api, "effectiveDate=2026-04-01&dry_run=true"), UNKNOWN, "dry_run");
        assertParameter(api.get("/org-units?changeOrder=x"), UNKNOWN, "changeOrder");
        assertParameter(api.post("/org-units?asOf=2026-04-01", UNIT), UNKNOWN, "asOf");
        assertParameter(api.get("?max=1"), UNKNOWN, "max");
        assertEquals("このリクエストはクエリパラメーター dryrun を受け付けません。名前を確かめてください。大文字と小文字は区別されます。",
                Api.json(api.withAcceptLanguage("ja").get("/org-units?dryrun=true")).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        assertEquals(0, Api.json(api.get("/org-units?asOf=2020-01-01")).size());
        assertEquals(1, Api.json(api.get("/org-units")).size());
        assertEquals(0, api.count("/ref-items/count?kind=UNIT_TYPE"));
        HttpResponse<String> dryRun = csv(api, "effectiveDate=2026-04-01&dryRun=true");
        assertEquals(200, dryRun.statusCode(), dryRun.body());
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aParameterGivenTwiceFailsAndWritesNothing() throws Exception {
        Api api = TestRealm.create("query-twice").api;
        String order = Api.json(api.post("/change-orders", "{\"title\": \"t\", \"effectiveDate\": \"2026-04-01\"}"))
                .get("id").asText();
        ObjectNode before = RealmExportIT.export(api);

        // The endpoint read the first value.
        assertParameter(api.get("/org-units?asOf=2020-01-01&asOf=2021-01-01"), TWICE, "asOf");
        assertParameter(api.get("/org-units?asOf=2020-01-01&asOf=2020-01-01"), TWICE, "asOf");
        assertParameter(api.get("/org-units?asOf=2020-01-01&ASOF=2021-01-01"), TWICE, "asOf");
        assertParameter(csv(api, "effectiveDate=2026-04-01&dryRun=true&dryRun=false"), TWICE, "dryRun");
        assertParameter(api.post("/org-units?changeOrder=" + order + "&changeOrder=" + order, UNIT), TWICE,
                "changeOrder");
        assertParameter(csv(api, "effectiveDate=2026-04-01&effectiveDate=2026-05-01"), TWICE, "effectiveDate");
        assertEquals("クエリパラメーター asOf が二度あります。一度だけにしてください。",
                Api.json(api.withAcceptLanguage("ja").get("/org-units?asOf=2020-01-01&asOf=2021-01-01"))
                        .get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        assertEquals(201, api.post("/org-units?changeOrder=" + order, UNIT).statusCode());
    }

    // The parameters of each endpoint are in the OpenAPI document, so the check tells a caller nothing new.
    @Test
    void theCheckComesAfterTheTokenAndBeforeTheRoles() throws Exception {
        TestRealm realm = TestRealm.create("query-caller");
        Api usersOnly = PermissionsIT.administrator(KEYCLOAK.getKeycloakAdminClient(), realm.name,
                "realm-management", "users-viewer", "view-users");
        Api noToken = new Api(realm.name, null);

        assertEquals(401, noToken.get("/org-units?asof=2020-01-01").statusCode());
        assertEquals(403, usersOnly.get("/org-units?asOf=2020-01-01").statusCode());
        assertParameter(usersOnly.get("/org-units?asof=2020-01-01"), UNKNOWN, "asof");
    }

    @Test
    void everyListFollowsItsLinksToTheEnd() throws Exception {
        TestRealm realm = TestRealm.withEmployer("query-links");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        for (int i = 0; i < 2; i++) {
            String user = realm.user("nurse-" + i);
            HttpResponse<String> staff = api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + ward
                    + "\", \"validFrom\": \"2020-04-01\", \"staffNumber\": \"N-" + i + "\"}");
            assertEquals(201, staff.statusCode(), staff.body());
            assertEquals(201, api.post("/qualifications", "{\"userId\": \"" + user + "\", \"kind\": \"license\", "
                    + "\"validFrom\": \"2020-04-01\"}").statusCode());
            assertEquals(201, api.post("/unit-roles", "{\"employmentId\": \"" + Api.json(staff).get("employment")
                    .get("id").asText() + "\", \"orgUnitId\": \"" + ward + "\", \"role\": \"approver\", "
                    + "\"validFrom\": \"2020-04-01\"}").statusCode());
        }

        // The first page skips no entry with first. The links go on with after.
        for (String list : List.of("/org-units?asOf=2026-04-01&first=0&max=1",
                "/org-units/" + ward + "/members?asOf=2026-04-01&includeDescendants=true&first=0&max=1",
                "/people?first=0&max=1", "/employments?asOf=2026-04-01&first=0&max=1",
                "/assignments?asOf=2026-04-01&orgUnitId=" + ward + "&first=0&max=1",
                "/qualifications?asOf=2026-04-01&kind=license&first=0&max=1",
                "/unit-roles?asOf=2026-04-01&role=approver&first=0&max=1", "/export/org-units?max=1")) {
            assertEquals(3, follow(api, list).size(), list);
        }
    }

    // Each page that the Link header leads to, from the first one to the empty one.
    private static List<String> follow(Api api, String list) throws Exception {
        List<String> pages = new ArrayList<>();
        for (String path = list; path != null;) {
            HttpResponse<String> response = api.get(path);
            assertEquals(200, response.statusCode(), path + " " + response.body());
            pages.add(path);
            path = response.headers().firstValue("Link")
                    .map(link -> link.substring(link.indexOf("/fuhito") + "/fuhito".length(), link.indexOf('>')))
                    .orElse(null);
        }
        return pages;
    }

    @Test
    void anEndpointOfKeycloakTakesItsQueryAsBefore() throws Exception {
        TestRealm realm = TestRealm.create("query-keycloak");
        realm.user("someone");
        HttpResponse<String> users = HTTP.send(HttpRequest.newBuilder(URI.create(KEYCLOAK.getAuthServerUrl()
                        + "/admin/realms/" + realm.name + "/users?unknown=1&max=1&max=5"))
                .header("Authorization", "Bearer " + KEYCLOAK.getKeycloakAdminClient().tokenManager()
                        .getAccessTokenString())
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, users.statusCode(), users.body());
        assertEquals(List.of("someone"), Api.json(users).findValuesAsText("username"));
    }
}
