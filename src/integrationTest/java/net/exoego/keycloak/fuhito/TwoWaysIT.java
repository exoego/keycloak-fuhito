package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request that names one thing in two ways fails, and writes nothing. Each way alone still works. A person ID and
 * a user ID are two names of one person, so a create can give both when they name the same person.
 */
class TwoWaysIT {

    private static final String START = "\"validFrom\": \"2026-04-01\"";

    private static final List<String> CREATES = List.of("/employments", "/staff", "/qualifications",
            "/reporting-lines");

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

    // A create on the path that names its person, or the manager of its line, by the given IDs. Null leaves an ID
    // out.
    private static String create(String path, String ward, String assignment, String personId, String userId) {
        boolean line = path.equals("/reporting-lines");
        StringBuilder body = new StringBuilder("{").append(switch (path) {
            case "/staff" -> "\"orgUnitId\": \"" + ward + "\", ";
            case "/qualifications" -> "\"kind\": \"license\", ";
            // A dotted line can have the same dates as another one.
            case "/reporting-lines" -> "\"assignmentId\": \"" + assignment + "\", \"kind\": \"DOTTED\", ";
            default -> "";
        });
        if (personId != null) {
            body.append('"').append(line ? "managerPersonId" : "personId").append("\": \"").append(personId)
                    .append("\", ");
        }
        if (userId != null) {
            body.append('"').append(line ? "managerUserId" : "userId").append("\": \"").append(userId).append("\", ");
        }
        return body.append(START).append('}').toString();
    }

    // The person that a created resource names, by its person ID and its user ID.
    private static String personOf(String path, JsonNode created) {
        if (path.equals("/reporting-lines")) {
            return created.path("managerPersonId").asText() + " " + created.path("managerUserId").asText();
        }
        JsonNode resource = path.equals("/staff") ? created.get("employment") : created;
        return resource.path("personId").asText() + " " + resource.path("userId").asText();
    }

    @Test
    void aPersonOrAManagerByTheIdsOfTwoPeopleFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("two-ways-person");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String ward = unit(api, "病棟", null);
        String nurse = realm.user("nurse");
        String ofNurse = created(api.post("/people", "{\"userId\": \"" + nurse + "\"}")).get("id").asText();
        String doctor = realm.user("doctor");
        created(api.post("/people", "{\"userId\": \"" + doctor + "\"}"));
        String plain = created(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"Nurse\"}}}")).get("id").asText();
        // The user has no person yet, so a write that used the user would create one.
        String nobody = realm.user("nobody");
        String assignment = created(api.post("/staff", "{\"userId\": \"" + realm.user("clerk") + "\", "
                + "\"orgUnitId\": \"" + ward + "\", " + START + "}")).get("assignment").get("id").asText();
        List<List<String>> pairs = List.of(List.of("nurse doctor", ofNurse, doctor),
                List.of("nurse nobody", ofNurse, nobody), List.of("plain doctor", plain, doctor),
                List.of("plain nobody", plain, nobody));
        ObjectNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (List<String> pair : pairs) {
            for (String path : CREATES) {
                String name = path + " " + pair.getFirst();
                expected.add(name + (path.equals("/reporting-lines")
                        ? " 400 invalid.mismatch managerPersonId managerUserId"
                        : " 400 invalid.mismatch personId userId"));
                got.add(name + " " + outcome(api.post(path, create(path, ward, assignment, pair.get(1),
                        pair.get(2))), "field", "otherField"));
            }
        }
        assertEquals(expected, got);
        String body = create("/employments", ward, assignment, ofNurse, doctor);
        assertEquals("personId and userId name different things. Give one of them, or values that name the same "
                + "thing.", Api.json(api.post("/employments", body)).get("message").asText());
        assertEquals("personId と userId は別々のものを指しています。どちらか一方にするか、同じものを指す値にしてください。",
                Api.json(api.withAcceptLanguage("ja").post("/employments", body)).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));

        // Each way works alone.
        for (String path : CREATES) {
            assertEquals(ofNurse + " " + nurse,
                    personOf(path, created(api.post(path, create(path, ward, assignment, ofNurse, null)))), path);
            assertEquals(ofNurse + " " + nurse,
                    personOf(path, created(api.post(path, create(path, ward, assignment, null, nurse)))), path);
        }
    }

    @Test
    void aPersonOrAManagerByBothIdsOfOnePersonWorks() throws Exception {
        TestRealm realm = TestRealm.withEmployer("two-ways-same-person");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String ward = unit(api, "病棟", null);
        String nurse = realm.user("nurse");
        String assignment = created(api.post("/staff", "{\"userId\": \"" + realm.user("clerk") + "\", "
                + "\"orgUnitId\": \"" + ward + "\", " + START + "}")).get("assignment").get("id").asText();
        // A read of an employment of a person with a user has both IDs.
        JsonNode read = Api.json(api.get("/employments/" + created(api.post("/employments", "{\"userId\": \""
                + nurse + "\", " + START + "}")).get("id").asText()));
        String person = read.get("personId").asText();
        assertEquals(nurse, read.get("userId").asText());

        for (String path : CREATES) {
            assertEquals(person + " " + nurse,
                    personOf(path, created(api.post(path, create(path, ward, assignment, person, nurse)))), path);
        }
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
