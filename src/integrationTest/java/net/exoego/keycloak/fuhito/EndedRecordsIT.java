package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nurse whose employment ends while it has an assignment, a role over an org unit, and a reporting line that hold,
 * and an assignment with its own role and line that would start later. The end ends the first ones and removes the
 * later ones. Each of them gets an entry of its own, as a part of the write that ends the employment.
 */
class EndedRecordsIT {

    // The server decides what today is. The container runs in UTC.
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private record Nurse(TestRealm realm, String user, String employment, String assignment, String role,
            String line, String later, String laterRole, String laterLine) {

        Api api() {
            return realm.api;
        }

        // The entries that the end gives to the records of the employment, by type, operation, and path.
        Set<String> ended() {
            return Set.of(
                    "FUHITO_ASSIGNMENT UPDATE fuhito/assignments/" + assignment + "/end",
                    "FUHITO_UNIT_ROLE UPDATE fuhito/unit-roles/" + role + "/end",
                    "FUHITO_REPORTING_LINE UPDATE fuhito/reporting-lines/" + line + "/end",
                    "FUHITO_ASSIGNMENT DELETE fuhito/assignments/" + later,
                    "FUHITO_UNIT_ROLE DELETE fuhito/unit-roles/" + laterRole,
                    "FUHITO_REPORTING_LINE DELETE fuhito/reporting-lines/" + laterLine);
        }
    }

    private static Nurse nurse(String prefix, LocalDate from, LocalDate later) throws Exception {
        TestRealm realm = TestRealm.withEmployer(prefix);
        Api api = realm.api;
        realm.refItem("UNIT_ROLE", "safety", "安全管理者");
        String ward = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"));
        String office = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"看護部\"}}, \"validFrom\": \"2020-04-01\"}"));
        String user = realm.user("nurse");
        String head = realm.user("head");
        JsonNode staff = created(api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"" + from + "\"}"));
        String employment = staff.get("employment").get("id").asText();
        String assignment = staff.get("assignment").get("id").asText();
        String laterAssignment = id(api.post("/assignments", "{\"employmentId\": \"" + employment
                + "\", \"orgUnitId\": \"" + office + "\", \"validFrom\": \"" + later + "\"}"));
        return new Nurse(realm, user, employment, assignment, role(api, employment, ward, from),
                line(api, assignment, head, from), laterAssignment, role(api, employment, office, later),
                line(api, laterAssignment, head, later));
    }

    private static String role(Api api, String employment, String orgUnit, LocalDate from) throws Exception {
        return id(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + orgUnit
                + "\", \"role\": \"safety\", \"validFrom\": \"" + from + "\"}"));
    }

    private static String line(Api api, String assignment, String manager, LocalDate from) throws Exception {
        return id(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment + "\", \"managerUserId\": \""
                + manager + "\", \"validFrom\": \"" + from + "\"}"));
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String id(HttpResponse<String> response) throws Exception {
        return created(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static long lastSequence(Api api) throws Exception {
        JsonNode log = ok(api.get("/change-log?max=1000"));
        return log.isEmpty() ? 0 : log.get(log.size() - 1).get("sequence").asLong();
    }

    // The entries after a number. Each is a write with the user and the change order of the first one.
    private static List<JsonNode> entriesAfter(Api api, long after) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        ok(api.get("/change-log?after=" + after + "&max=1000")).forEach(entries::add);
        JsonNode first = entries.getFirst();
        for (JsonNode entry : entries) {
            assertEquals("WRITE", entry.get("kind").asText(), entry.toString());
            assertEquals(first.path("actorUserId").asText(null), entry.path("actorUserId").asText(null),
                    entry.toString());
            assertEquals(first.path("changeOrderId").asText(null), entry.path("changeOrderId").asText(null),
                    entry.toString());
        }
        return entries;
    }

    private static Set<String> described(List<JsonNode> entries) {
        return entries.stream()
                .map(entry -> entry.get("resourceType").asText() + " " + entry.get("operation").asText() + " "
                        + entry.get("resourcePath").asText())
                .collect(Collectors.toSet());
    }

    private static String path(JsonNode entry) {
        return entry.get("resourcePath").asText();
    }

    @Test
    void anEndOfAnEmploymentGivesEachRecordThatItEndsOrRemovesAnEntryOfTheSameWrite() throws Exception {
        Nurse nurse = nurse("ended-records", LocalDate.of(2025, 4, 1), LocalDate.of(2031, 4, 1));
        Api api = nurse.api();
        String order = id(api.post("/change-orders", "{\"title\": \"退職\", \"effectiveDate\": \"2030-04-01\"}"));
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        ok(api.putWithVersion("/employments/" + nurse.employment() + "/end?changeOrder=" + order,
                "{\"validTo\": \"2030-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        List<JsonNode> entries = entriesAfter(api, last);
        assertEquals("fuhito/employments/" + nurse.employment() + "/end", path(entries.getFirst()));
        assertTrue(entries.getFirst().has("actorUserId"));
        assertEquals(order, entries.getFirst().get("changeOrderId").asText());
        Set<String> expected = new HashSet<>(nurse.ended());
        expected.add("FUHITO_EMPLOYMENT UPDATE fuhito/employments/" + nurse.employment() + "/end");
        assertEquals(expected, described(entries));
        assertEquals(7, entries.size());
        assertEquals(7, api.count("/change-orders/" + order + "/changes/count"));
    }

    @Test
    void theRemovalOfTheUserGivesEachRecordThatItsEndEndsOrRemovesAnEntryWithNoUser() throws Exception {
        Nurse nurse = nurse("ended-records-removal", TODAY.minusYears(1), TODAY.plusDays(10));
        Api api = nurse.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        nurse.realm().removeUser(nurse.user());

        reads.assertEveryChangeIsLogged();
        List<JsonNode> entries = entriesAfter(api, last);
        assertEquals("fuhito/employments/" + nurse.employment() + "/end", path(entries.getFirst()));
        assertFalse(entries.getFirst().has("actorUserId"));
        assertFalse(entries.getFirst().has("changeOrderId"));
        Set<String> expected = new HashSet<>(nurse.ended());
        expected.add("FUHITO_EMPLOYMENT UPDATE fuhito/employments/" + nurse.employment() + "/end");
        assertEquals(expected, described(entries));
        assertEquals(TODAY.toString(), ok(api.get("/assignments/" + nurse.assignment())).get("validTo").asText());
    }

    @Test
    void anEndInAnImportGivesEachRecordThatItEndsOrRemovesAnEntry() throws Exception {
        Nurse nurse = nurse("ended-records-import", LocalDate.of(2025, 4, 1), LocalDate.of(2031, 4, 1));
        Api api = nurse.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        JsonNode report = ok(api.post("/imports", "{\"effectiveDate\": \"2030-04-01\", \"employments\": [{\"id\": \""
                + nurse.employment() + "\", \"validTo\": \"2030-04-01\"}]}"));

        assertEquals(1, report.get("counts").get("employments").get("changed").asInt(), report.toString());
        reads.assertEveryChangeIsLogged();
        List<JsonNode> entries = entriesAfter(api, last);
        Set<String> expected = new HashSet<>(nurse.ended());
        expected.add("FUHITO_EMPLOYMENT UPDATE fuhito/employments/" + nurse.employment() + "/end");
        assertEquals(expected, described(entries));
    }

    @Test
    void aTransferGivesEachOldRecordThatItEndsOrRemovesAnEntry() throws Exception {
        Nurse nurse = nurse("ended-records-transfer", LocalDate.of(2025, 4, 1), LocalDate.of(2031, 4, 1));
        Api api = nurse.api();
        String other = nurse.realm().legalEntity("法人B");
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        JsonNode transfer = created(api.postWithVersion("/employments/" + nurse.employment() + "/transfer",
                "{\"employerId\": \"" + other + "\", \"validFrom\": \"2030-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        List<JsonNode> entries = entriesAfter(api, last);
        assertEquals("fuhito/employments/" + nurse.employment() + "/transfer", path(entries.getFirst()));
        Set<String> described = described(entries);
        assertTrue(described.containsAll(nurse.ended()), described.toString());
        // The new employment, its two assignments, their lines, and its roles are new.
        assertEquals(7, described.stream().filter(entry -> entry.contains(" CREATE ")).count(), described.toString());
        assertEquals(1 + 6 + 7, entries.size());
        assertEquals(2, transfer.get("assignments").size());
    }

    @Test
    void anEarlierEndAndAMoveOfAnAssignmentGiveEachLineThatTheyEndOrRemoveAnEntry() throws Exception {
        Nurse nurse = nurse("ended-records-assignment", LocalDate.of(2025, 4, 1), LocalDate.of(2031, 4, 1));
        Api api = nurse.api();
        String planned = id(api.post("/reporting-lines", "{\"assignmentId\": \"" + nurse.assignment()
                + "\", \"managerUserId\": \"" + nurse.realm().user("deputy") + "\", \"kind\": \"DOTTED\", "
                + "\"validFrom\": \"2030-10-01\"}"));
        String ward2 = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"2病棟\"}}, \"validFrom\": \"2020-04-01\"}"));
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        ok(api.putWithVersion("/assignments/" + nurse.assignment() + "/end", "{\"validTo\": \"2030-04-01\"}"));
        JsonNode moved = created(api.postWithVersion("/assignments/" + nurse.later() + "/move",
                "{\"orgUnitId\": \"" + ward2 + "\", \"validFrom\": \"2032-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        List<JsonNode> entries = entriesAfter(api, last);
        assertEquals(Set.of(
                "FUHITO_ASSIGNMENT UPDATE fuhito/assignments/" + nurse.assignment() + "/end",
                "FUHITO_REPORTING_LINE UPDATE fuhito/reporting-lines/" + nurse.line() + "/end",
                "FUHITO_REPORTING_LINE DELETE fuhito/reporting-lines/" + planned,
                "FUHITO_ASSIGNMENT UPDATE fuhito/assignments/" + nurse.later() + "/move",
                "FUHITO_ASSIGNMENT CREATE fuhito/assignments/" + moved.get("started").get("id").asText(),
                "FUHITO_REPORTING_LINE UPDATE fuhito/reporting-lines/" + nurse.laterLine() + "/end"),
                described(entries));
    }

    @Test
    void aSwitchOfThePrimaryFlagGivesTheAssignmentThatGivesItUpAnEntry() throws Exception {
        Nurse nurse = nurse("ended-records-primary", LocalDate.of(2025, 4, 1), LocalDate.of(2031, 4, 1));
        Api api = nurse.api();
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        String claimant = id(api.post("/assignments", "{\"employmentId\": \"" + nurse.employment()
                + "\", \"orgUnitId\": \"" + ok(api.get("/assignments/" + nurse.later())).get("orgUnitId").asText()
                + "\", \"validFrom\": \"2027-04-01\", \"primary\": true, \"switchPrimary\": true}"));
        ok(api.putWithVersion("/assignments/" + nurse.later() + "/details",
                "{\"validFrom\": \"2031-04-01\", \"primary\": true, \"switchPrimary\": true}"));

        reads.assertEveryChangeIsLogged();
        List<JsonNode> entries = entriesAfter(api, last);
        assertEquals(List.of(
                "FUHITO_ASSIGNMENT CREATE fuhito/assignments/" + claimant,
                "FUHITO_ASSIGNMENT UPDATE fuhito/assignments/" + nurse.assignment() + "/details",
                "FUHITO_ASSIGNMENT UPDATE fuhito/assignments/" + nurse.later() + "/details",
                "FUHITO_ASSIGNMENT UPDATE fuhito/assignments/" + claimant + "/details"),
                entries.stream().map(entry -> entry.get("resourceType").asText() + " "
                        + entry.get("operation").asText() + " " + path(entry)).toList());
    }
}
