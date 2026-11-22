package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserRemovalIT {

    // The server decides what today is. The container runs in UTC.
    private static final String TODAY = LocalDate.now(ZoneOffset.UTC).toString();
    private static final String TOMORROW = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
    private static final String YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();

    @Test
    void removingAUserKeepsTheirHistoryAndEndsWhatIsOpen() throws Exception {
        TestRealm realm = TestRealm.withEmployer("user-removal");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"課\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String removed = realm.user("removed");
        String kept = realm.user("kept");
        List<String> removedIds = employ(api, removed, unit);
        List<String> keptIds = employ(api, kept, unit);
        String realmId = realm.id();
        String logRows = "select count(*) from fuhito_change_log where realm_id = '" + realmId + "'";
        int logBefore = Integer.parseInt(KeycloakEnvironment.sql(logRows));
        assertEquals(List.of(kept, removed).stream().sorted().toList(),
                Api.json(api.get("/org-units/" + unit + "/members")).findValuesAsText("userId").stream().distinct().sorted().toList());

        realm.removeUser(removed);

        // No row is gone.
        assertEquals("4", rows("fuhito_employment", realmId));
        assertEquals("6", rows("fuhito_employment_terms", realmId));
        assertEquals("6", rows("fuhito_assignment", realmId));
        // The history of the removed user is still readable.
        JsonNode history = Api.json(api.get("/users/" + removed + "/history"));
        assertEquals(2, history.get("employments").size());
        assertEquals("2024-04-01", history.get("employments").get(0).get("validTo").asText());
        assertEquals(TODAY, history.get("employments").get(1).get("validTo").asText());
        assertEquals(2, history.get("assignments").size());
        for (JsonNode assignment : history.get("assignments")) {
            assertEquals(TODAY, assignment.get("validTo").asText());
        }
        assertEquals(200, api.get("/employments/" + removedIds.get(1)).statusCode());
        // The removed user was a member yesterday, and is not one tomorrow.
        assertEquals(2, Api.json(api.get("/users/" + removed + "/assignments?asOf=" + YESTERDAY)).size());
        assertEquals(0, Api.json(api.get("/users/" + removed + "/assignments?asOf=" + TOMORROW)).size());
        assertEquals(List.of(kept), Api.json(api.get("/org-units/" + unit + "/members?asOf=" + TOMORROW))
                .findValuesAsText("userId").stream().distinct().toList());
        // The other user is untouched.
        assertFalse(Api.json(api.get("/employments/" + keptIds.get(1))).has("validTo"));

        // The change log keeps every entry. It has one more for the end of the open employment, and one for each of
        // its two assignments that the end ended. No user did it.
        assertEquals(logBefore + 3, Integer.parseInt(KeycloakEnvironment.sql(logRows)));
        JsonNode log = Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + removedIds.get(1)));
        assertEquals(List.of("CREATE", "UPDATE"), log.findValuesAsText("operation"));
        assertFalse(log.get(1).has("actorUserId"));
        assertEquals("fuhito/employments/" + removedIds.get(1) + "/end", log.get(1).get("resourcePath").asText());
    }

    @Test
    void erasingAUserRemovesEverythingAboutThemAndNothingElse() throws Exception {
        TestRealm realm = TestRealm.withEmployer("user-erase");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"課\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        realm.refItem("QUALIFICATION", "license", "免許");
        String erased = realm.user("erased");
        String gone = realm.user("gone");
        String kept = realm.user("kept");
        List<String> erasedIds = employ(api, erased, unit);
        List<String> goneIds = employ(api, gone, unit);
        List<String> keptIds = employ(api, kept, unit);
        for (String user : List.of(erased, gone, kept)) {
            assertEquals(201, api.post("/qualifications", "{\"userId\": \"" + user
                    + "\", \"kind\": \"license\", \"validFrom\": \"2010-05-01\"}").statusCode());
        }
        // The erased user reports to the kept one, and is the manager of the kept one.
        List<String> lines = List.of(line(api, erased, kept), line(api, kept, erased));
        String realmId = realm.id();
        // One user is removed from Keycloak first. Their data can still be erased afterwards.
        realm.removeUser(gone);
        assertEquals("6", rows("fuhito_employment", realmId));

        String erasedPerson = Api.json(api.get("/users/" + erased + "/history")).get("personId").asText();
        assertEquals(204, api.delete("/users/" + erased + "/data").statusCode());
        assertEquals(204, api.delete("/users/" + gone + "/data").statusCode());

        assertEquals("2", rows("fuhito_employment", realmId));
        assertEquals("3", rows("fuhito_employment_terms", realmId));
        assertEquals("3", rows("fuhito_assignment", realmId));
        assertEquals("1", rows("fuhito_qualification", realmId));
        // The entries of the change log stay, because they hold only IDs.
        for (String id : List.of(erasedIds.get(0), erasedIds.get(1), goneIds.get(1))) {
            assertEquals(404, api.get("/employments/" + id).statusCode());
            assertFalse(Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + id)).isEmpty());
        }
        for (String line : lines) {
            assertEquals(List.of("CREATE"), Api.json(api.get("/change-log?resourceType=FUHITO_REPORTING_LINE&resourceId="
                    + line)).findValuesAsText("operation"), line);
        }
        assertEquals(200, api.get("/employments/" + keptIds.get(1)).statusCode());
        assertEquals(2, Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + keptIds.get(0))).size());
        // The erased user still exists in Keycloak, with no data. The removed one is unknown now.
        assertEquals(0, Api.json(api.get("/users/" + erased + "/history")).get("employments").size());
        assertEquals(404, api.get("/users/" + gone + "/history").statusCode());
        assertEquals(404, api.delete("/users/" + gone + "/data").statusCode());
        assertEquals(404, api.delete("/users/no-such-user/data").statusCode());
        // The log says that data was erased, and nothing more about the person.
        JsonNode erasure = Api.json(api.get("/change-log?resourceType=FUHITO_PERSON_DATA&resourceId=" + erasedPerson));
        assertEquals("fuhito/people/" + erasedPerson + "/data", erasure.get(0).get("resourcePath").asText());
        // Erasing again finds no person, and writes nothing.
        assertEquals(204, api.delete("/users/" + erased + "/data").statusCode());
        assertFalse(Api.json(api.get("/users/" + erased + "/history")).has("personId"));
        assertEquals(List.of("DELETE"), erasure.findValuesAsText("operation"));
        assertEquals(401, new Api(realm.name, null).delete("/users/" + kept + "/data").statusCode());
    }

    @Test
    void anEraseAndARemovalOfPeopleOfAnAgencyKeepTheAgencyUntilNoEmploymentNamesIt() throws Exception {
        TestRealm realm = TestRealm.withEmployer("user-erase-agency");
        Api api = realm.api;
        String agency = realm.refItem("EXTERNAL_EMPLOYER", "staffing", "Yスタッフ");
        String erased = realm.user("erased");
        String removed = realm.user("removed");
        String masked = realm.user("masked");
        String start = "\"externalEmployer\": \"staffing\", \"validFrom\": \"2025-04-01\"";
        String ofErased = Api.json(api.post("/employments", "{\"userId\": \"" + erased + "\", \"staffNumber\": \"S-1\", "
                + start + "}")).get("id").asText();
        String ofRemoved = Api.json(api.post("/employments", "{\"userId\": \"" + removed + "\", " + start + "}"))
                .get("id").asText();
        String ofMasked = Api.json(api.post("/employments", "{\"userId\": \"" + masked + "\", \"staffNumber\": \"S-3\", "
                + start + "}")).get("id").asText();

        assertEquals(204, api.delete("/users/" + erased + "/data").statusCode());
        assertEquals(404, api.get("/employments/" + ofErased).statusCode());
        assertEquals("ref_item.in_use", Api.json(api.deleteWithVersion("/ref-items/" + agency)).get("error").asText());

        // A removal from Keycloak ends the employment, and the agency has no end that would stop it.
        realm.removeUser(removed);
        JsonNode ended = Api.json(api.get("/employments/" + ofRemoved));
        assertEquals(TODAY, ended.get("validTo").asText());
        assertEquals("staffing", ended.get("externalEmployer").asText());

        // A mask removes the staff number and keeps the employer, which is no personal data.
        assertEquals(200, api.putWithVersion("/employments/" + ofMasked + "/end", "{\"validTo\": \"2026-04-01\"}")
                .statusCode());
        String person = Api.json(api.get("/users/" + masked + "/history")).get("personId").asText();
        assertEquals(200, api.postWithVersion("/people/" + person + "/mask", "").statusCode());
        JsonNode maskedEmployment = Api.json(api.get("/employments/" + ofMasked));
        assertFalse(maskedEmployment.has("staffNumber"));
        assertEquals("staffing", maskedEmployment.get("externalEmployer").asText());

        assertEquals(204, api.delete("/users/" + removed + "/data").statusCode());
        assertEquals(204, api.delete("/users/" + masked + "/data").statusCode());
        assertEquals("0", rows("fuhito_employment", realm.id()));
        assertEquals(204, api.deleteWithVersion("/ref-items/" + agency).statusCode());
    }

    private static String line(Api api, String userId, String managerUserId) throws Exception {
        String assignment = Api.json(api.get("/users/" + userId + "/assignments")).get(0).get("assignmentId").asText();
        HttpResponse<String> created = api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + managerUserId + "\", \"validFrom\": \"2025-10-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        return Api.json(created).get("id").asText();
    }

    // Two employments. The first ended in 2024 and has two sets of terms.
    // The second is open and has two assignments, one of them with two periods.
    private static List<String> employ(Api api, String userId, String unit) throws Exception {
        String first = Api.json(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2024-04-01\"}")).get("id").asText();
        assertEquals(200, api.putWithVersion("/employments/" + first + "/terms",
                "{\"validFrom\": \"2022-04-01\", \"status\": \"ON_LEAVE\"}").statusCode());
        String second = Api.json(api.post("/employments", "{\"userId\": \"" + userId
                + "\", \"validFrom\": \"2025-04-01\"}")).get("id").asText();
        String assignment = Api.json(api.post("/assignments", "{\"employmentId\": \"" + second + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2025-04-01\"}")).get("id").asText();
        assertEquals(200, api.putWithVersion("/assignments/" + assignment + "/details",
                "{\"validFrom\": \"2025-10-01\", \"head\": true}").statusCode());
        assertEquals(201, api.post("/assignments", "{\"employmentId\": \"" + second + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2025-10-01\"}").statusCode());
        return List.of(first, second);
    }

    private static String rows(String table, String realmId) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realmId + "'");
    }

    @Test
    void removingAUserDeletesAFutureEmploymentWithItsRolesAndTheOneThatContinuesIt() throws Exception {
        TestRealm realm = TestRealm.create("user-removal-roles");
        Api api = realm.api;
        String first = realm.legalEntity("法人A");
        String second = realm.legalEntity("法人B");
        realm.refItem("UNIT_ROLE", "hr-contact", "人事担当");
        String user = realm.user("joins-and-moves");
        JsonNode hired = Api.json(api.post("/staff", "{\"userId\": \"" + user + "\", \"employerId\": \"" + first
                + "\", \"orgUnitId\": \"" + first + "\", \"validFrom\": \"" + TOMORROW + "\"}"));
        String employment = hired.get("employment").get("id").asText();
        assertEquals(201, api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + first + "\", \"role\": \"hr-contact\", \"validFrom\": \"" + TOMORROW + "\"}").statusCode());
        assertEquals(200, api.putWithVersion("/external-ids", "{\"resourceType\": \"EMPLOYMENT\", \"resourceId\": \""
                + employment + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}").statusCode());
        String later = LocalDate.parse(TOMORROW).plusDays(30).toString();
        assertEquals(201, api.postWithVersion("/employments/" + employment + "/transfer", "{\"employerId\": \"" + second
                + "\", \"validFrom\": \"" + later + "\"}").statusCode());
        String realmId = realm.id();
        assertEquals("2", rows("fuhito_employment", realmId));
        assertEquals("2", rows("fuhito_unit_role", realmId));

        realm.removeUser(user);

        for (String table : List.of("fuhito_employment", "fuhito_unit_role", "fuhito_assignment",
                "fuhito_external_id")) {
            assertEquals("0", rows(table, realmId), table);
        }
        // A follower of the change log learns that the employment that never held is gone.
        JsonNode log = Api.json(api.get("/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + employment));
        assertEquals("DELETE", log.get(log.size() - 1).get("operation").asText());
        assertFalse(log.get(log.size() - 1).has("actorUserId"));
        // The entries of the employment stand for its ID, from the write of the ID to the removal.
        assertEquals("UPDATE fuhito/employments/" + employment,
                log.get(1).get("operation").asText() + " " + log.get(1).get("resourcePath").asText());
        List<String> types = Api.json(api.get("/change-log?max=1000")).findValuesAsText("resourceType");
        assertFalse(types.contains("FUHITO_EXTERNAL_ID"), types.toString());
    }

    // The removal ends the two open employments in one write, and removes what would start later in each.
    @Test
    void removingAUserRemovesAFutureAssignmentAndTheOneOfAnotherEmploymentAfterIt() throws Exception {
        TestRealm realm = TestRealm.withEmployer("user-removal-continued");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"課\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String user = realm.user("two-contracts");
        List<String> employments = new ArrayList<>();
        for (String from : List.of("2020-04-01", "2021-04-01")) {
            employments.add(Api.json(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \""
                    + from + "\"}")).get("id").asText());
        }
        String soon = LocalDate.parse(TODAY).plusDays(10).toString();
        String later = LocalDate.parse(TODAY).plusDays(20).toString();
        HttpResponse<String> planned = api.post("/assignments", "{\"employmentId\": \"" + employments.get(0)
                + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"" + soon + "\", \"validTo\": \"" + later + "\"}");
        assertEquals(201, planned.statusCode(), planned.body());
        String first = Api.json(planned).get("id").asText();
        HttpResponse<String> imported = api.post("/imports", "{\"effectiveDate\": \"" + TODAY + "\", \"source\": "
                + "\"sheet\", \"assignments\": [{\"externalId\": \"C-1\", \"employmentId\": \"" + employments.get(1)
                + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"" + later + "\"}]}");
        assertEquals(200, imported.statusCode(), imported.body());
        String continuing = ImportIT.lookup(api, "ASSIGNMENT", "C-1");

        realm.removeUser(user);

        for (String employment : employments) {
            assertEquals(TODAY, Api.json(api.get("/employments/" + employment)).get("validTo").asText());
        }
        assertEquals(404, api.get("/assignments/" + first).statusCode());
        assertEquals(404, api.get("/assignments/" + continuing).statusCode());
        ObjectNode export = RealmExportIT.export(api);
        TestRealm target = TestRealm.create("user-removal-continued-restore");
        realm.remove();
        HttpResponse<String> restored = target.api.post("/restore", export.toString());
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(export, RealmExportIT.export(target.api));
    }

    @Test
    void aMaskAndAnEraseKeepTheEarlierEntriesAndAddTheirOwn() throws Exception {
        TestRealm realm = TestRealm.withEmployer("user-erase-log");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"課\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        realm.refItem("QUALIFICATION", "license", "免許");
        String user = realm.user("leaver");
        assertEquals(201, api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\"}").statusCode());
        String person = Api.json(api.get("/users/" + user + "/history")).get("personId").asText();
        // A qualification that is deleted, and an employment that never starts because the user goes.
        String license = Api.json(api.post("/qualifications", "{\"userId\": \"" + user + "\", \"kind\": \"license\", "
                + "\"number\": \"L-777\", \"validFrom\": \"2010-05-01\"}")).get("id").asText();
        assertEquals(204, api.deleteWithVersion("/qualifications/" + license).statusCode());
        assertEquals(201, api.post("/employments", "{\"userId\": \"" + user + "\", \"staffNumber\": \"S-555\", "
                + "\"validFrom\": \"" + TOMORROW + "\"}").statusCode());
        realm.removeUser(user);
        HttpResponse<String> before = api.get("/change-log");
        assertFalse(before.body().contains("L-777"), before.body());
        assertFalse(before.body().contains("S-555"), before.body());
        JsonNode earlier = Api.json(before);
        long last = earlier.get(earlier.size() - 1).get("sequence").asLong();

        assertEquals(200, api.postWithVersion("/people/" + person + "/mask", "").statusCode());
        assertEquals(204, api.deleteWithVersion("/people/" + person + "/data").statusCode());

        assertEquals(earlier, Api.json(api.get("/change-log?max=" + earlier.size())));
        JsonNode added = Api.json(api.get("/change-log?after=" + last));
        assertEquals(List.of("FUHITO_PERSON_DATA", "FUHITO_PERSON_DATA"), added.findValuesAsText("resourceType"));
        assertEquals(List.of(person, person), added.findValuesAsText("resourceId"));
        assertEquals(List.of("UPDATE", "DELETE"), added.findValuesAsText("operation"));
        // The numbers of the realm have no gaps.
        List<Long> numbers = new ArrayList<>();
        Api.json(api.get("/change-log")).forEach(entry -> numbers.add(entry.get("sequence").asLong()));
        assertEquals(LongStream.rangeClosed(1, last + 2).boxed().toList(), numbers);
    }

    @Test
    void removingAUserDeletesAnEmploymentThatNeverStarted() throws Exception {
        TestRealm realm = TestRealm.withEmployer("user-removal-future");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"課\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String user = realm.user("joins-later");
        assertEquals(201, api.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"" + TOMORROW + "\"}").statusCode());
        String realmId = realm.id();
        assertEquals("1", rows("fuhito_employment", realmId));

        realm.removeUser(user);

        // The person never worked here, so there is no history to keep.
        assertEquals("0", rows("fuhito_employment", realmId));
        assertEquals("0", rows("fuhito_employment_terms", realmId));
        assertEquals("0", rows("fuhito_assignment", realmId));
    }
}
