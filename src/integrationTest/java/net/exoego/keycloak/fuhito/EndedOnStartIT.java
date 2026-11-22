package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A read of an assignment lists the assignments of the same person, in any employment, that end on its start with
 * the same primary flag.
 */
class EndedOnStartIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static TestRealm realm;
    private static Api api;
    private static String ward;
    private static String clinic;
    private static String lab;

    @BeforeAll
    static void createTheUnits() throws Exception {
        realm = TestRealm.withEmployer("ended-on-start");
        api = realm.api;
        ward = unit(api, "病棟");
        clinic = unit(api, "外来");
        lab = unit(api, "検査科");
    }

    private static String unit(Api api, String name) throws Exception {
        return id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name
                + "\"}}, \"validFrom\": \"2020-04-01\"}"));
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static String employment(Api api, String userId, String employerId) throws Exception {
        String employer = employerId == null ? "" : ", \"employerId\": \"" + employerId + "\"";
        return id(api.post("/employments", "{\"userId\": \"" + userId + "\", \"validFrom\": \"2020-04-01\""
                + employer + "}"));
    }

    private static String assignment(Api api, String employmentId, String orgUnitId, String from, String to,
            boolean primary) throws Exception {
        return id(api.post("/assignments", body(employmentId, orgUnitId, from, to, primary)));
    }

    private static String body(String employmentId, String orgUnitId, String from, String to, boolean primary) {
        return "{\"employmentId\": \"" + employmentId + "\", \"orgUnitId\": \"" + orgUnitId + "\", \"validFrom\": \""
                + from + "\"" + (to == null ? "" : ", \"validTo\": \"" + to + "\"") + ", \"primary\": " + primary
                + "}";
    }

    // The IDs in endedOnStart, or none when a read leaves it out.
    private static List<String> ended(JsonNode read) {
        assertFalse(read.has("endedOnStart") && read.get("endedOnStart").isEmpty(), read.toString());
        return read.path("endedOnStart").findValuesAsText("assignmentId");
    }

    private static List<String> ended(Api api, String assignmentId) throws Exception {
        HttpResponse<String> read = api.get("/assignments/" + assignmentId);
        assertEquals(200, read.statusCode(), read.body());
        return ended(Api.json(read));
    }

    private static JsonNode only(JsonNode list, String assignmentId) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode entry : list) {
            String id = entry.has("assignmentId") ? entry.get("assignmentId").asText() : entry.get("id").asText();
            if (id.equals(assignmentId)) {
                found.add(entry);
            }
        }
        assertEquals(1, found.size(), list.toString());
        return found.getFirst();
    }

    private static List<String> sorted(String... ids) {
        return List.of(ids).stream().sorted().toList();
    }

    @Test
    void aMoveGivesTheNewAssignmentTheOldOneInEveryRead() throws Exception {
        String user = realm.user("moved");
        String employment = employment(api, user, null);
        String old = assignment(api, employment, ward, "2020-04-01", null, true);

        HttpResponse<String> move = api.postWithVersion("/assignments/" + old + "/move", "{\"orgUnitId\": \""
                + clinic + "\", \"validFrom\": \"2026-04-01\"}");

        assertEquals(201, move.statusCode(), move.body());
        JsonNode started = Api.json(move).get("started");
        String next = started.get("id").asText();
        JsonNode entry = started.get("endedOnStart").get(0);
        assertEquals(List.of(old, ward, "true"), List.of(entry.get("assignmentId").asText(),
                entry.get("orgUnitId").asText(), entry.get("primary").asText()));
        assertEquals(List.of(old), ended(api, next));
        // A read leaves out the list when no assignment ends on the start.
        assertFalse(Api.json(api.get("/assignments/" + old)).has("endedOnStart"));
        assertFalse(Api.json(move).get("ended").has("endedOnStart"));

        // Every list of assignments has it too.
        String person = Api.json(api.get("/users/" + user + "/history")).get("personId").asText();
        for (String path : List.of("/assignments?asOf=2026-04-01&orgUnitId=" + clinic,
                "/org-units/" + clinic + "/members?asOf=2026-04-01", "/people/" + person + "/assignments?asOf=2026-04-01",
                "/users/" + user + "/assignments?asOf=2026-04-01")) {
            HttpResponse<String> list = api.get(path);
            assertEquals(200, list.statusCode(), list.body());
            assertEquals(List.of(old), ended(only(Api.json(list), next)), path);
        }
        for (String path : List.of("/people/" + person + "/history", "/users/" + user + "/history")) {
            JsonNode assignments = Api.json(api.get(path)).get("assignments");
            assertEquals(List.of(old), ended(only(assignments, next)), path);
            assertEquals(List.of(), ended(only(assignments, old)), path);
        }
        HttpResponse<String> before = api.get("/assignments?asOf=2026-03-31&orgUnitId=" + ward);
        assertEquals(List.of(), ended(only(Api.json(before), old)));
    }

    // Assignment A holds until September, and C starts then. A move of A in June adds A2, which ends when C starts.
    @Test
    void aMoveBeforeALaterAssignmentGivesThatOneTheNewAssignment() throws Exception {
        String employment = employment(api, realm.user("later-first"), null);
        String a = assignment(api, employment, ward, "2026-04-01", "2027-09-01", true);
        String c = assignment(api, employment, lab, "2027-09-01", null, true);
        assertEquals(List.of(a), ended(api, c));

        HttpResponse<String> move = api.postWithVersion("/assignments/" + a + "/move", "{\"orgUnitId\": \""
                + clinic + "\", \"validFrom\": \"2027-06-01\"}");

        assertEquals(201, move.statusCode(), move.body());
        String a2 = Api.json(move).get("started").get("id").asText();
        assertEquals("2027-09-01", Api.json(move).get("started").get("validTo").asText());
        assertEquals(List.of(a2), ended(api, c));
        assertEquals(List.of(a), ended(api, a2));
        assertEquals(List.of(), ended(api, a));
        // Two primary assignments of one employment never share a date, also when an end moves later.
        for (String id : List.of(a2, a)) {
            HttpResponse<String> later = api.putWithVersion("/assignments/" + id + "/end",
                    "{\"validTo\": \"2027-12-01\"}");
            assertEquals(409, later.statusCode(), later.body());
            assertEquals("assignment.primary_exists", Api.json(later).get("error").asText());
            HttpResponse<String> open = api.deleteWithVersion("/assignments/" + id + "/end");
            assertEquals(409, open.statusCode(), open.body());
            assertEquals("assignment.primary_exists", Api.json(open).get("error").asText());
        }
        assertEquals(List.of(a2), ended(api, c));
    }

    @Test
    void everyAssignmentThatEndsOnTheStartWithTheSameFlagCounts() throws Exception {
        String employment = employment(api, realm.user("two-posts"), null);
        String primary = assignment(api, employment, ward, "2020-04-01", "2026-04-01", true);
        String lab2020 = assignment(api, employment, lab, "2020-04-01", "2026-04-01", false);
        String clinic2020 = assignment(api, employment, clinic, "2020-04-01", "2026-04-01", false);

        String nextPrimary = assignment(api, employment, clinic, "2026-04-01", null, true);
        String nextPost = assignment(api, employment, ward, "2026-04-01", null, false);
        String afterGap = assignment(api, employment, lab, "2026-05-01", null, false);

        assertEquals(List.of(primary), ended(api, nextPrimary));
        assertEquals(sorted(lab2020, clinic2020), ended(api, nextPost));
        assertEquals(List.of(), ended(api, afterGap));
    }

    @Test
    void anAssignmentOfAnotherEmploymentOfThePersonCountsAndOneOfAnotherPersonDoesNot() throws Exception {
        String user = realm.user("two-contracts");
        String first = employment(api, user, null);
        String second = employment(api, user, null);
        String other = employment(api, realm.user("someone-else"), null);
        String ofFirst = assignment(api, first, ward, "2020-04-01", "2026-04-01", false);
        assignment(api, other, ward, "2020-04-01", "2026-04-01", false);

        String ofSecond = assignment(api, second, clinic, "2026-04-01", null, false);

        assertEquals(List.of(ofFirst), ended(api, ofSecond));
    }

    @Test
    void aTransferAndAMergerGiveTheCopyTheOldAssignment() throws Exception {
        TestRealm group = TestRealm.create("ended-on-start-transfer");
        try {
            Api groupApi = group.api;
            String x = group.legalEntity("医療法人X会");
            String y = group.legalEntity("株式会社Yサービス");
            String z = group.legalEntity("株式会社Zケア");
            String unit = unit(groupApi, "病棟");
            String sato = employment(groupApi, group.user("sato"), x);
            String ofSato = assignment(groupApi, sato, unit, "2020-04-01", null, true);
            String suzukiUser = group.user("suzuki");
            String suzuki = employment(groupApi, suzukiUser, x);
            String ofSuzuki = assignment(groupApi, suzuki, unit, "2020-04-01", null, true);

            HttpResponse<String> transfer = groupApi.postWithVersion("/employments/" + sato + "/transfer",
                    "{\"employerId\": \"" + y + "\", \"validFrom\": \"2026-04-01\"}");
            assertEquals(201, transfer.statusCode(), transfer.body());
            JsonNode copy = Api.json(transfer).get("assignments").get(0);
            assertEquals(List.of(ofSato), ended(copy));
            assertEquals(List.of(ofSato), ended(groupApi, copy.get("id").asText()));

            HttpResponse<String> merger = groupApi.post("/employments/transfers", "{\"fromEmployerId\": \"" + x
                    + "\", \"toEmployerId\": \"" + z + "\", \"validFrom\": \"2027-04-01\"}");
            assertEquals(201, merger.statusCode(), merger.body());
            String started = Api.json(merger).get("employments").get(0).get("startedEmploymentId").asText();
            List<String> copies = new ArrayList<>();
            for (JsonNode read : Api.json(groupApi.get("/users/" + suzukiUser + "/history")).get("assignments")) {
                if (read.get("employmentId").asText().equals(started)) {
                    copies.add(read.get("id").asText());
                    assertEquals(List.of(ofSuzuki), ended(read));
                }
            }
            assertEquals(1, copies.size());
        } finally {
            group.remove();
        }
    }

    // A read of an assignment is the body of a create. Another list fails, and the create writes nothing.
    @Test
    void aReadIsTheBodyOfACreateAndAnotherListFails() throws Exception {
        String user = realm.user("read-as-body");
        String employment = employment(api, user, null);
        String old = assignment(api, employment, ward, "2020-04-01", "2026-04-01", false);
        String next = assignment(api, employment, clinic, "2026-04-01", null, false);
        ObjectNode read = (ObjectNode) Api.json(api.get("/assignments/" + next));
        assertEquals(204, api.deleteWithVersion("/assignments/" + next).statusCode());

        String again = id(api.post("/assignments", read.toString()));

        assertEquals(List.of(old), ended(api, again));
        assertEquals(204, api.deleteWithVersion("/assignments/" + again).statusCode());
        int before = Api.json(api.get("/users/" + user + "/history")).get("assignments").size();
        String ofRead = "[{\"assignmentId\": \"" + old + "\", \"orgUnitId\": \"" + ward + "\", \"primary\": false}]";
        assertEquals(JSON.readTree(ofRead), read.get("endedOnStart"));
        List<String> wrong = List.of(
                "[{\"assignmentId\": \"" + old + "\", \"orgUnitId\": \"" + ward + "\", \"primary\": true}]",
                "[{\"assignmentId\": \"" + old + "\", \"orgUnitId\": \"" + lab + "\", \"primary\": false}]",
                "[{\"assignmentId\": \"" + old + "\", \"orgUnitId\": \"" + ward + "\"}]",
                "[{\"assignmentId\": \"nope\", \"orgUnitId\": \"" + ward + "\", \"primary\": false}]",
                "[{\"assignmentId\": \"" + old + "\", \"orgUnitId\": \"" + ward + "\", \"primary\": false}, "
                        + "{\"assignmentId\": \"" + old + "\", \"orgUnitId\": \"" + ward + "\", \"primary\": false}]");
        for (String list : wrong) {
            ObjectNode body = read.deepCopy();
            body.set("endedOnStart", JSON.readTree(list));
            HttpResponse<String> failed = api.post("/assignments", body.toString());
            assertEquals(400, failed.statusCode(), list + " " + failed.body());
            assertEquals(List.of("invalid.value_fixed", "endedOnStart"), List.of(
                    Api.json(failed).get("error").asText(), Api.json(failed).get("params").get("field").asText()));
        }
        // A new assignment that starts after a gap has none, so a list fails there too.
        ObjectNode gap = (ObjectNode) JSON.readTree(body(employment, clinic, "2026-05-01", null, false));
        gap.set("endedOnStart", JSON.readTree(ofRead));
        HttpResponse<String> afterGap = api.post("/assignments", gap.toString());
        assertEquals(400, afterGap.statusCode(), afterGap.body());
        assertEquals("invalid.value_fixed", Api.json(afterGap).get("error").asText());
        assertEquals(before, Api.json(api.get("/users/" + user + "/history")).get("assignments").size());
    }

    @Test
    void anImportRowCannotNameTheAssignmentThatItContinues() throws Exception {
        String employment = employment(api, realm.user("import-row"), null);
        for (String field : List.of("continuesAssignmentId", "continuesAssignmentExternalId")) {
            HttpResponse<String> imported = api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"source\": "
                    + "\"sheet\", \"assignments\": [{\"externalId\": \"A\", \"employmentId\": \"" + employment
                    + "\", \"orgUnitId\": \"" + ward + "\", \"" + field + "\": \"x\"}]}");
            assertEquals(400, imported.statusCode(), imported.body());
            assertEquals("invalid.unknown_key", Api.json(imported).get("error").asText(), imported.body());
        }
    }
}
