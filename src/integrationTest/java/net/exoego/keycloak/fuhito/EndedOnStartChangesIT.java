package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Nurses whose assignments end on the start of another assignment of the same person. A write of one assignment
 * changes {@code endedOnStart} of the other one, which nobody wrote. The other one gets a new version and a
 * propagated change.
 */
class EndedOnStartChangesIT {

    // The server decides what today is. The container runs in UTC.
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private record Ward(TestRealm realm, Api api, String ward, String clinic, String lab) {

        String employment(String user) throws Exception {
            return id(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2020-04-01\"}"));
        }

        String assignment(String employmentId, String orgUnitId, String from, String to, boolean primary)
                throws Exception {
            return id(api.post("/assignments", body(employmentId, orgUnitId, from, to, primary)));
        }
    }

    private static Ward ward(String prefix) throws Exception {
        TestRealm realm = TestRealm.withEmployer(prefix);
        return new Ward(realm, realm.api, unit(realm.api, "病棟"), unit(realm.api, "外来"), unit(realm.api, "検査科"));
    }

    private static String unit(Api api, String name) throws Exception {
        return id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name
                + "\"}}, \"validFrom\": \"2020-04-01\"}"));
    }

    private static String body(String employmentId, String orgUnitId, String from, String to, boolean primary) {
        return "{\"employmentId\": \"" + employmentId + "\", \"orgUnitId\": \"" + orgUnitId + "\", \"validFrom\": \""
                + from + "\"" + (to == null ? "" : ", \"validTo\": \"" + to + "\"") + ", \"primary\": " + primary
                + "}";
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

    private static List<String> ended(Api api, String assignmentId) throws Exception {
        return ok(api.get("/assignments/" + assignmentId)).path("endedOnStart").findValuesAsText("assignmentId");
    }

    private static long lastSequence(Api api) throws Exception {
        long last = 0;
        for (JsonNode page = ok(api.get("/change-log?max=1000")); !page.isEmpty();
                page = ok(api.get("/change-log?max=1000&after=" + last))) {
            last = page.get(page.size() - 1).get("sequence").asLong();
        }
        return last;
    }

    // The propagated changes after a number, by the assignment, each with the path of the write that caused it.
    private static Map<String, String> propagated(Api api, long after) throws Exception {
        Map<Long, String> writes = new HashMap<>();
        Map<String, String> propagated = new TreeMap<>();
        for (JsonNode entry : ok(api.get("/change-log?max=1000&after=" + after))) {
            if (entry.get("kind").asText().equals("WRITE")) {
                writes.put(entry.get("sequence").asLong(), entry.get("resourcePath").asText());
            } else {
                assertEquals(List.of("FUHITO_ASSIGNMENT", "UPDATE", "WRITE"), List.of(
                        entry.get("resourceType").asText(), entry.get("operation").asText(),
                        entry.get("cause").asText()), entry.toString());
                assertFalse(entry.has("actorUserId"), entry.toString());
                propagated.put(entry.get("resourceId").asText(),
                        writes.get(entry.get("causeSequence").asLong()));
            }
        }
        return propagated;
    }

    private static Map<String, String> caused(String path, String... assignmentIds) {
        Map<String, String> caused = new TreeMap<>();
        for (String id : assignmentIds) {
            caused.put(id, path);
        }
        return caused;
    }

    // The case of #721. A move of A in September is stored first, and C starts then. A move of A in June adds A2,
    // which now ends on the start of C.
    @Test
    void anEarlierMoveAfterALaterOneGivesTheLaterAssignmentAPropagatedChange() throws Exception {
        Ward ward = ward("ended-changes-moves");
        Api api = ward.api();
        String employment = ward.employment(ward.realm().user("moved"));
        String a = ward.assignment(employment, ward.ward(), "2026-04-01", null, true);
        String c = created(api.postWithVersion("/assignments/" + a + "/move", "{\"orgUnitId\": \"" + ward.lab()
                + "\", \"validFrom\": \"2027-09-01\"}")).get("started").get("id").asText();
        assertEquals(List.of(a), ended(api, c));
        long version = api.version("/assignments/" + c);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        String a2 = created(api.postWithVersion("/assignments/" + a + "/move", "{\"orgUnitId\": \""
                + ward.clinic() + "\", \"validFrom\": \"2027-06-01\"}")).get("started").get("id").asText();

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(a2), ended(api, c));
        assertEquals(version + 1, api.version("/assignments/" + c));
        // The moved assignment and the new one have entries of writes, and no propagated change.
        assertEquals(caused("fuhito/assignments/" + a + "/move", c), propagated(api, last));
    }

    @Test
    void aClientThatHoldsAnOldReadOfTheNextAssignmentGets409AfterAnEndOfTheOneBefore() throws Exception {
        Ward ward = ward("ended-changes-conflict");
        Api api = ward.api();
        String employment = ward.employment(ward.realm().user("conflict"));
        String a = ward.assignment(employment, ward.ward(), "2026-04-01", "2027-04-01", false);
        String c = ward.assignment(employment, ward.clinic(), "2027-04-01", null, false);
        long read = api.version("/assignments/" + c);

        ok(api.putWithVersion("/assignments/" + a + "/end", "{\"validTo\": \"2027-01-01\"}"));

        HttpResponse<String> stale = api.put("/assignments/" + c + "/end", "{\"version\": " + read
                + ", \"validTo\": \"2028-04-01\"}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("version.conflict", Api.json(stale).get("error").asText());
        assertEquals(List.of(), ended(api, c));
    }

    @Test
    void anEarlierEndAndTheRemovalOfTheEndChangeTheAssignmentsThatStartOnTheOldAndOnTheNewEnd() throws Exception {
        Ward ward = ward("ended-changes-end");
        Api api = ward.api();
        String employment = ward.employment(ward.realm().user("ended"));
        String a = ward.assignment(employment, ward.ward(), "2026-04-01", "2027-10-01", false);
        String onOld = ward.assignment(employment, ward.clinic(), "2027-10-01", null, false);
        String onNew = ward.assignment(employment, ward.lab(), "2027-07-01", null, false);
        String unchanged = ward.assignment(employment, ward.lab(), "2027-07-01", null, true);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        ok(api.putWithVersion("/assignments/" + a + "/end", "{\"validTo\": \"2027-07-01\"}"));

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(List.of(), List.of(a), List.of()),
                List.of(ended(api, onOld), ended(api, onNew), ended(api, unchanged)));
        assertEquals(caused("fuhito/assignments/" + a + "/end", onOld, onNew), propagated(api, last));

        last = lastSequence(api);
        reads = DerivedReads.before(api);
        ok(api.deleteWithVersion("/assignments/" + a + "/end"));

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(), ended(api, onNew));
        assertEquals(caused("fuhito/assignments/" + a + "/end", onNew), propagated(api, last));
    }

    @Test
    void aDeleteChangesTheAssignmentThatStartsOnItsEnd() throws Exception {
        Ward ward = ward("ended-changes-delete");
        Api api = ward.api();
        String employment = ward.employment(ward.realm().user("deleted"));
        String a = ward.assignment(employment, ward.ward(), "2026-04-01", "2027-04-01", false);
        String next = ward.assignment(employment, ward.clinic(), "2027-04-01", null, false);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        assertEquals(204, api.deleteWithVersion("/assignments/" + a).statusCode());

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(), ended(api, next));
        assertEquals(caused("fuhito/assignments/" + a, next), propagated(api, last));
    }

    @Test
    void aSwitchOfThePrimaryFlagAndAChangeOfTheFlagOfTheLastPeriodChangeTheAssignmentThatStartsOnTheEnd()
            throws Exception {
        Ward ward = ward("ended-changes-primary");
        Api api = ward.api();
        String employment = ward.employment(ward.realm().user("switched"));
        String holder = ward.assignment(employment, ward.ward(), "2026-04-01", "2027-04-01", true);
        String next = ward.assignment(employment, ward.lab(), "2027-04-01", null, true);
        assertEquals(List.of(holder), ended(api, next));
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        String taker = id(api.post("/assignments", body(employment, ward.clinic(), "2026-10-01", "2027-04-01", true)
                .replace("}", ", \"switchPrimary\": true}")));

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(taker), ended(api, next));
        // The holder gave up the flag and has an entry of a write. It gets no propagated change.
        assertEquals(caused("fuhito/assignments/" + taker, next), propagated(api, last));

        String post = ward.assignment(employment, ward.ward(), "2028-04-01", "2029-04-01", false);
        String afterPost = ward.assignment(ward.employment(ward.realm().user("other-contract")), ward.lab(),
                "2029-04-01", null, true);
        String ofPerson = ward.assignment(ward.employment(ok(api.get("/assignments/" + next)).get("userId")
                .asText()), ward.clinic(), "2029-04-01", null, true);
        last = lastSequence(api);
        reads = DerivedReads.before(api);

        ok(api.putWithVersion("/assignments/" + post + "/details", "{\"validFrom\": \"2028-10-01\", "
                + "\"primary\": true, \"switchPrimary\": true}"));

        reads.assertEveryChangeIsLogged();
        // The next assignment holds the flag until the date, and the post holds it after the date. The assignment of
        // another employment of the person lists the post. One of another person does not.
        assertEquals(List.of(List.of(post), List.of()), List.of(ended(api, ofPerson), ended(api, afterPost)));
        assertEquals(caused("fuhito/assignments/" + post + "/details", ofPerson), propagated(api, last));
    }

    // The person has a second employment, and its assignments start on the old and on the new end of the first one.
    @Test
    void anEndOfAnEmploymentChangesTheAssignmentsOfAnotherEmploymentOfThePerson() throws Exception {
        Ward ward = ward("ended-changes-employment");
        Api api = ward.api();
        String user = ward.realm().user("two-contracts");
        String first = ward.employment(user);
        String second = ward.employment(user);
        String a = ward.assignment(first, ward.ward(), "2020-04-01", "2028-04-01", false);
        String onOld = ward.assignment(second, ward.clinic(), "2028-04-01", null, false);
        String onNew = ward.assignment(second, ward.lab(), "2027-04-01", null, false);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        ok(api.putWithVersion("/employments/" + first + "/end", "{\"validTo\": \"2027-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(List.of(), List.of(a)), List.of(ended(api, onOld), ended(api, onNew)));
        assertEquals(caused("fuhito/employments/" + first + "/end", onOld, onNew), propagated(api, last));

        // The removal of the end of the employment keeps its assignments as they are.
        last = lastSequence(api);
        reads = DerivedReads.before(api);
        ok(api.deleteWithVersion("/employments/" + first + "/end"));
        reads.assertEveryChangeIsLogged();
        assertEquals(Map.of(), propagated(api, last));
    }

    // The old assignment ends on the start of the assignment of a later employment of the person. Its copy ends there
    // too, and takes its place in the list.
    @Test
    void aTransferAndAMergerGiveTheAssignmentThatStartsOnTheOldEndTheCopy() throws Exception {
        TestRealm realm = TestRealm.create("ended-changes-transfer");
        Api api = realm.api;
        String x = realm.legalEntity("医療法人X会");
        String y = realm.legalEntity("株式会社Yサービス");
        String z = realm.legalEntity("株式会社Zケア");
        String unit = unit(api, "病棟");
        String user = realm.user("sato");
        String old = id(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2020-04-01\", "
                + "\"validTo\": \"2028-04-01\", \"employerId\": \"" + x + "\"}"));
        String later = id(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2028-04-01\", "
                + "\"employerId\": \"" + x + "\"}"));
        String a = id(api.post("/assignments", body(old, unit, "2020-04-01", null, true)));
        String next = id(api.post("/assignments", body(later, unit, "2028-04-01", null, true)));
        assertEquals(List.of(a), ended(api, next));
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        JsonNode transfer = created(api.postWithVersion("/employments/" + old + "/transfer", "{\"employerId\": \""
                + y + "\", \"validFrom\": \"2026-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        String copy = transfer.get("assignments").get(0).get("id").asText();
        assertEquals(List.of(copy), ended(api, next));
        assertEquals(caused("fuhito/employments/" + old + "/transfer", next), propagated(api, last));

        String moved = transfer.get("started").get("id").asText();
        last = lastSequence(api);
        reads = DerivedReads.before(api);
        created(api.post("/employments/transfers", "{\"fromEmployerId\": \"" + y + "\", \"toEmployerId\": \"" + z
                + "\", \"validFrom\": \"2027-04-01\"}"));

        reads.assertEveryChangeIsLogged();
        List<String> listed = ended(api, next);
        assertEquals(1, listed.size());
        assertFalse(Set.of(a, copy).contains(listed.getFirst()), listed.toString());
        assertEquals(caused("fuhito/employments/" + moved + "/transfer", next), propagated(api, last));
    }

    @Test
    void anImportAndACsvImportOfAssignmentsGivePropagatedChanges() throws Exception {
        Ward ward = ward("ended-changes-import");
        Api api = ward.api();
        String employment = ward.employment(ward.realm().user("imported"));
        String a = ward.assignment(employment, ward.ward(), "2026-04-01", "2027-10-01", false);
        String onOld = ward.assignment(employment, ward.clinic(), "2027-10-01", null, false);
        String onNew = ward.assignment(employment, ward.lab(), "2027-07-01", null, false);
        String onAdded = ward.assignment(employment, ward.lab(), "2028-04-01", null, false);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        // A third row writes the assignment that starts on the new end of A.
        JsonNode report = ok(api.post("/imports", "{\"effectiveDate\": \"2027-08-01\", \"assignments\": [{\"id\": \""
                + a + "\", \"validTo\": \"2027-07-01\"}, {\"externalId\": \"N-1\", \"source\": \"sheet\", "
                + "\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + ward.clinic() + "\", "
                + "\"validFrom\": \"2027-10-01\", \"validTo\": \"2028-04-01\"}, {\"id\": \"" + onNew
                + "\", \"displayOrder\": 3}]}"));

        assertEquals(0, report.get("errors").size(), report.toString());
        reads.assertEveryChangeIsLogged();
        String added = ok(api.get("/change-log?kind=WRITE&resourceType=FUHITO_ASSIGNMENT&after=" + last))
                .valueStream().filter(entry -> entry.get("operation").asText().equals("CREATE")).findFirst()
                .orElseThrow().get("resourceId").asText();
        assertEquals(List.of(List.of(), List.of(a), List.of(added)),
                List.of(ended(api, onOld), ended(api, onNew), ended(api, onAdded)));
        // The written assignment has the entry of its write only.
        Map<String, String> expected = caused("fuhito/assignments/" + a + "/end", onOld);
        expected.put(onAdded, "fuhito/assignments/" + added);
        assertEquals(expected, propagated(api, last));
        assertEquals(List.of("fuhito/assignments/" + onNew + "/details"), ok(api.get("/change-log?after=" + last
                + "&resourceId=" + onNew)).findValuesAsText("resourcePath"));

        last = lastSequence(api);
        reads = DerivedReads.before(api);
        HttpResponse<String> csv = api.postBytes("/imports/csv/assignments?effectiveDate=2026-04-01",
                "text/csv; charset=UTF-8", ("id,validTo\r\n" + a + ",2027-10-01\r\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(0, ok(csv).get("errors").size(), csv.body());
        reads.assertEveryChangeIsLogged();
        // The new assignment starts on the old end of A too.
        assertEquals(List.of(List.of(a), List.of(), List.of(a)),
                List.of(ended(api, onOld), ended(api, onNew), ended(api, added)));
        assertEquals(caused("fuhito/assignments/" + a + "/end", onOld, onNew, added), propagated(api, last));
    }

    @Test
    void anImportAndACsvImportOfAnEndOfAnEmploymentGivePropagatedChanges() throws Exception {
        Ward ward = ward("ended-changes-import-end");
        Api api = ward.api();
        String user = ward.realm().user("left");
        String first = ward.employment(user);
        String second = ward.employment(user);
        ward.assignment(first, ward.ward(), "2020-04-01", "2028-04-01", false);
        String onOld = ward.assignment(second, ward.clinic(), "2028-04-01", null, false);
        String onNew = ward.assignment(second, ward.lab(), "2027-04-01", null, false);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        ok(api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"employments\": [{\"id\": \"" + first
                + "\", \"validTo\": \"2027-04-01\"}]}"));

        reads.assertEveryChangeIsLogged();
        assertEquals(caused("fuhito/employments/" + first + "/end", onOld, onNew), propagated(api, last));

        last = lastSequence(api);
        reads = DerivedReads.before(api);
        HttpResponse<String> csv = api.postBytes("/imports/csv/employments?effectiveDate=2026-04-01",
                "text/csv; charset=UTF-8", ("id,validTo\r\n" + first + ",2026-10-01\r\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(0, ok(csv).get("errors").size(), csv.body());
        reads.assertEveryChangeIsLogged();
        assertEquals(caused("fuhito/employments/" + first + "/end", onNew), propagated(api, last));
    }

    // A person who comes back gets a new employment whose assignment ends on the start of a later one.
    @Test
    void aNewStaffAssignmentThatEndsOnTheStartOfALaterOneGivesThatOneAPropagatedChange() throws Exception {
        Ward ward = ward("ended-changes-staff");
        Api api = ward.api();
        String user = ward.realm().user("rehired");
        String later = id(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2028-04-01\"}"));
        String next = ward.assignment(later, ward.clinic(), "2028-04-01", null, true);
        long last = lastSequence(api);
        DerivedReads reads = DerivedReads.before(api);

        String added = created(api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + ward.ward()
                + "\", \"validFrom\": \"2027-04-01\", \"validTo\": \"2028-04-01\"}")).get("assignment").get("id")
                .asText();

        reads.assertEveryChangeIsLogged();
        assertEquals(List.of(added), ended(api, next));
        assertEquals(caused("fuhito/assignments/" + added, next), propagated(api, last));
    }

    // Every assignment of the person that starts today or later goes with the removal. So no list that stays changes.
    @Test
    void theRemovalOfAUserInKeycloakLeavesNoReadThatChangedWithNoEntry() throws Exception {
        Ward ward = ward("ended-changes-removal");
        Api api = ward.api();
        String user = ward.realm().user("removed");
        String employment = ward.employment(user);
        String held = ward.assignment(employment, ward.ward(), "2020-04-01", TODAY.plusDays(60).toString(), false);
        String next = ward.assignment(employment, ward.clinic(), TODAY.plusDays(60).toString(), null, false);
        assertEquals(List.of(held), ended(api, next));
        DerivedReads reads = DerivedReads.before(api);

        ward.realm().removeUser(user);

        reads.assertEveryChangeIsLogged();
        assertEquals(404, api.get("/assignments/" + next).statusCode());
        assertEquals(0, api.count("/change-log/count?kind=PROPAGATED&resourceType=FUHITO_ASSIGNMENT"));
    }
}
