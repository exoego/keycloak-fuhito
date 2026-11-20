package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request that names one thing in two ways fails, and writes nothing. Each way alone still works.
 */
class TwoWaysIT {

    private static final String START = "\"validFrom\": \"2026-04-01\"";

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String unit(Api api, String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \""
                + name + "\"}}, " + START + parent + "}")).get("id").asText();
    }

    // The status, the code, and the values of the error, so that one assertion shows every case.
    private static String outcome(HttpResponse<String> response, String... params) throws Exception {
        JsonNode body = Api.json(response);
        StringBuilder outcome = new StringBuilder().append(response.statusCode()).append(' ')
                .append(body.path("error").asText());
        for (String param : params) {
            outcome.append(' ').append(body.path("params").path(param).asText());
        }
        return outcome.toString();
    }

    @Test
    void aPersonOrAManagerByPersonIdAndByUserIdFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("two-ways-person");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String ward = unit(api, "病棟", null);
        String person = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"Nurse\"}}}")).get("id").asText();
        // The user has no person yet, so a write that used the user would create one.
        String doctor = realm.user("doctor");
        String assignment = created(api.post("/staff", "{\"userId\": \"" + realm.user("clerk") + "\", "
                + "\"orgUnitId\": \"" + ward + "\", " + START + "}")).get("assignment").get("id").asText();
        String both = "\"personId\": \"" + person + "\", \"userId\": \"" + doctor + "\", ";
        String managers = "\"managerPersonId\": \"" + person + "\", \"managerUserId\": \"" + doctor + "\", ";
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (String path : List.of("/employments", "/staff", "/qualifications")) {
            String rest = switch (path) {
                case "/staff" -> "\"orgUnitId\": \"" + ward + "\", ";
                case "/qualifications" -> "\"kind\": \"license\", ";
                default -> "";
            };
            expected.add(path + " 400 invalid.twice personId userId");
            got.add(path + " " + outcome(api.post(path, "{" + both + rest + START + "}"), "field", "otherField"));
        }
        expected.add("/reporting-lines 400 invalid.twice managerPersonId managerUserId");
        got.add("/reporting-lines " + outcome(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", " + managers + START + "}"), "field", "otherField"));
        assertEquals(expected, got);
        String body = "{" + both + START + "}";
        assertEquals("personId and userId name one thing in two ways. Give one of them.",
                Api.json(api.post("/employments", body)).get("message").asText());
        assertEquals("personId と userId は同じものを二通りで指しています。どちらか一方にしてください。",
                Api.json(api.withAcceptLanguage("ja").post("/employments", body)).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        for (String way : List.of("\"personId\": \"" + person + "\", ", "\"userId\": \"" + doctor + "\", ")) {
            created(api.post("/employments", "{" + way + START + "}"));
            created(api.post("/staff", "{" + way + "\"orgUnitId\": \"" + ward + "\", " + START + "}"));
            created(api.post("/qualifications", "{" + way + "\"kind\": \"license\", " + START + "}"));
        }
        created(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment + "\", \"managerPersonId\": \""
                + person + "\", " + START + "}"));
        JsonNode dotted = created(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment + "\", "
                + "\"managerUserId\": \"" + doctor + "\", \"kind\": \"DOTTED\", " + START + "}"));
        assertEquals(doctor, dotted.get("managerUserId").asText());
    }

    @Test
    void aStepThatNamesItsUnitOrItsParentByIdAndByRefFailsAndWritesNothing() throws Exception {
        Api api = TestRealm.create("two-ways-reorganization").api;
        String root = unit(api, "病院", null);
        String other = unit(api, "診療所", null);
        String moved = unit(api, "病棟", root);
        long version = Api.json(api.get("/org-units/" + moved)).get("version").asLong();
        String create = "{\"op\": \"create\", \"ref\": \"new\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"新設\"}}, \"parentId\": \"" + root + "\"}, ";
        List<List<String>> steps = List.of(
                List.of("move id unitRef", "{\"op\": \"move\", \"id\": \"" + moved + "\", \"unitRef\": \"new\", "
                        + "\"version\": " + version + ", \"parentId\": \"" + other + "\"}"),
                List.of("create parentId parentRef", "{\"op\": \"create\", \"primaryLanguage\": \"ja\", \"names\": "
                        + "{\"ja\": {\"name\": \"子\"}}, \"parentId\": \"" + other + "\", \"parentRef\": \"new\"}"),
                List.of("move parentId parentRef", "{\"op\": \"move\", \"id\": \"" + moved + "\", \"version\": "
                        + version + ", \"parentId\": \"" + other + "\", \"parentRef\": \"new\"}"));
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (List<String> step : steps) {
            String[] fields = step.getFirst().split(" ", -1);
            expected.add(step.getFirst() + " 400 invalid.twice " + fields[1] + " " + fields[2] + " 1");
            got.add(step.getFirst() + " " + outcome(api.post("/reorganizations", "{\"effectiveDate\": "
                    + "\"2027-04-01\", \"steps\": [" + create + step.getLast() + "]}"), "field", "otherField",
                    "step"));
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        String ok = "{\"effectiveDate\": \"2027-04-01\", \"steps\": [" + create
                + "{\"op\": \"create\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"子\"}}, "
                + "\"parentRef\": \"new\"}, "
                + "{\"op\": \"move\", \"unitRef\": \"new\", \"parentId\": \"" + other + "\"}, "
                + "{\"op\": \"move\", \"id\": \"" + moved + "\", \"version\": " + version + ", \"parentRef\": "
                + "\"new\"}]}";
        HttpResponse<String> done = api.post("/reorganizations", ok);
        assertEquals(200, done.statusCode(), done.body());
        JsonNode results = Api.json(done).get("results");
        String made = results.get(0).get("unit").get("id").asText();
        assertEquals(made, results.get(1).get("unit").get("parentId").asText());
        assertEquals(other, results.get(2).get("unit").get("parentId").asText());
        assertEquals(made, results.get(3).get("unit").get("parentId").asText());
    }

    @Test
    void anImportRowThatNamesTheUserByIdAndByUsernameFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("two-ways-import");
        Api api = realm.api;
        realm.user("nurse");
        String doctor = realm.user("doctor");
        String user = "\"username\": \"nurse\", \"userId\": \"" + doctor + "\"";
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (String list : List.of("people", "employments")) {
            HttpResponse<String> response = api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"" + list
                    + "\": [{\"externalId\": \"X1\", " + user + "}]}");
            JsonNode error = Api.json(response).path("errors").path(0);
            expected.add(list + " 400 import.user_twice userId");
            got.add(list + " " + response.statusCode() + " " + error.path("error").asText() + " "
                    + error.path("params").path("field").asText());
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        HttpResponse<String> one = api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"people\": "
                + "[{\"externalId\": \"P1\", \"username\": \"nurse\"}, {\"externalId\": \"P2\", \"userId\": \""
                + doctor + "\"}], \"employments\": [{\"externalId\": \"E1\", \"username\": \"nurse\"}, "
                + "{\"externalId\": \"E2\", \"userId\": \"" + doctor + "\"}]}");
        assertEquals(200, one.statusCode(), one.body());
        assertEquals(doctor, Api.json(api.get("/users/" + doctor + "/history")).get("userId").asText());
        assertEquals(1, Api.json(api.get("/users/" + doctor + "/history")).get("employments").size());
    }
}
