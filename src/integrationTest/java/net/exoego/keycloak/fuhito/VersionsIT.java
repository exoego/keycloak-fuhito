package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return response.body().isEmpty() ? null : Api.json(response);
    }

    private static long version(HttpResponse<String> response) throws Exception {
        return ok(response).get("version").asLong();
    }

    private static String id(HttpResponse<String> response) throws Exception {
        return ok(response).get("id").asText();
    }

    // The entry of a list with the ID.
    private static JsonNode entry(JsonNode list, String field, String id) {
        for (JsonNode each : list) {
            if (each.get(field).asText().equals(id)) {
                return each;
            }
        }
        throw new AssertionError(id + " is not in " + list);
    }

    @Test
    void aNewResourceHasOneAndEachWriteCountsItUpByOne() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions");
        Api api = realm.api;

        assertEquals(1, version(api.post("/ref-kinds", "{\"code\": \"site\", \"name\": \"拠点\"}")));
        assertEquals(2, version(api.putWithVersion("/ref-kinds/site", "{\"name\": \"事業所\"}")));
        assertEquals(2, entry(ok(api.get("/ref-kinds")), "code", "site").get("version").asLong());
        assertFalse(entry(ok(api.get("/ref-kinds")), "code", "UNIT_TYPE").has("version"));

        HttpResponse<String> item = api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}");
        String itemId = id(item);
        assertEquals(1, version(item));
        assertEquals(2, version(api.putWithVersion("/ref-items/" + itemId, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京本社\"}}}")));
        assertEquals(2, version(api.get("/ref-items/" + itemId)));

        HttpResponse<String> hierarchy = api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}");
        String hierarchyId = id(hierarchy);
        assertEquals(1, version(hierarchy));
        assertEquals(2, version(api.putWithVersion("/hierarchies/" + hierarchyId, "{\"name\": \"プロジェクト\"}")));
        assertEquals(2, version(api.get("/hierarchies/" + hierarchyId)));

        HttpResponse<String> definition = api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"beds\", \"type\": \"NUMBER\"}");
        String definitionId = id(definition);
        assertEquals(1, version(definition));
        assertEquals(2, version(api.putWithVersion("/attribute-definitions/" + definitionId, "{\"type\": \"STRING\"}")));
        assertEquals(2, version(api.get("/attribute-definitions/" + definitionId)));

        HttpResponse<String> order = api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}");
        String orderId = id(order);
        assertEquals(1, version(order));
        assertEquals(2, version(api.putWithVersion("/change-orders/" + orderId,
                "{\"title\": \"人事異動\", \"effectiveDate\": \"2026-04-01\"}")));
        assertEquals(3, version(api.postWithVersion("/change-orders/" + orderId + "/announce", "")));
        assertEquals(3, version(api.get("/change-orders/" + orderId)));

        // A write of the person and of its ID of another system is one write of the person.
        HttpResponse<String> person = api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Ann\"}}}");
        String personId = id(person);
        assertEquals(1, version(person));
        assertEquals(2, version(api.putWithVersion("/people/" + personId, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Anne\"}}}")));
        assertEquals(3, version(api.putWithVersion("/people/" + personId,
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Annie\"}}, \"source\": \"hr\", "
                        + "\"externalId\": \"P-1\"}")));
        String payroll = "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + personId
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"}";
        assertEquals(1, version(api.putWithVersion("/external-ids", payroll.formatted("X-1"))));
        assertEquals(4, version(api.get("/people/" + personId)));
        assertEquals(2, version(api.putWithVersion("/external-ids", payroll.formatted("X-2"))));
        assertEquals(2, entry(ok(api.get("/external-ids?resourceType=PERSON&resourceId=" + personId)), "source",
                "payroll").get("version").asLong());
        assertEquals(5, version(api.get("/people/" + personId)));
        ok(api.deleteWithVersion("/external-ids?resourceType=PERSON&resourceId=" + personId + "&source=payroll"));
        assertEquals(6, version(api.get("/people/" + personId)));

        realm.refItem("QUALIFICATION", "license", "免許");
        HttpResponse<String> qualification = api.post("/qualifications", "{\"personId\": \"" + personId
                + "\", \"kind\": \"license\", \"validFrom\": \"2020-04-01\"}");
        String qualificationId = id(qualification);
        assertEquals(1, version(qualification));
        assertEquals(2, version(api.putWithVersion("/qualifications/" + qualificationId,
                "{\"kind\": \"license\", \"number\": \"9\", \"validFrom\": \"2020-04-01\"}")));
        assertEquals(2, version(api.get("/qualifications/" + qualificationId)));
        assertEquals(6, version(api.get("/people/" + personId)));

        String unit = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"));
        JsonNode nurse = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        HttpResponse<String> role = api.post("/unit-roles", "{\"employmentId\": \""
                + nurse.get("employment").get("id").asText() + "\", \"orgUnitId\": \"" + unit
                + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}");
        String roleId = id(role);
        assertEquals(1, version(role));
        assertEquals(2, version(api.putWithVersion("/unit-roles/" + roleId + "/end", "{\"validTo\": \"2030-04-01\"}")));
        assertEquals(2, version(api.get("/unit-roles/" + roleId)));

        HttpResponse<String> line = api.post("/reporting-lines", "{\"assignmentId\": \""
                + nurse.get("assignment").get("id").asText() + "\", \"managerUserId\": \"" + realm.user("boss")
                + "\", \"validFrom\": \"2021-04-01\"}");
        String lineId = id(line);
        assertEquals(1, version(line));
        assertEquals(2, version(api.putWithVersion("/reporting-lines/" + lineId + "/end", "{\"validTo\": \"2030-04-01\"}")));
        assertEquals(2, version(api.get("/reporting-lines/" + lineId)));
        // The line and the role are no part of the assignment and the employment.
        assertEquals(1, version(api.get("/assignments/" + nurse.get("assignment").get("id").asText())));
        assertEquals(1, version(api.get("/employments/" + nurse.get("employment").get("id").asText())));
    }

    @Test
    void aChangeOfAPeriodOrOfAParentCountsUpTheOrgUnitOnce() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-unit");
        Api api = realm.api;
        String hospital = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"));
        String other = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"別院\"}}, \"validFrom\": \"2020-04-01\"}"));
        HttpResponse<String> created = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\", \"source\": \"hr\", \"externalId\": \"W\"}");
        String ward = id(created);
        assertEquals(1, version(created));

        // The new name splits the period in two rows. It is one write.
        assertEquals(2, version(api.putWithVersion("/org-units/" + ward, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"第1病棟\"}}, \"validFrom\": \"2027-04-01\"}")));
        assertEquals(2, version(api.get("/org-units/" + ward + "?asOf=2020-05-01")));
        assertEquals(2, version(api.get("/org-units/" + ward + "?asOf=2027-05-01")));
        JsonNode history = ok(api.get("/org-units/" + ward + "/history"));
        assertEquals(2, history.get("version").asLong());
        history.get("versions").forEach(period -> assertEquals(2, period.get("version").asLong()));

        // The old parent link ends, and a new one starts.
        assertEquals(3, version(api.putWithVersion("/org-units/" + ward + "/parent", "{\"parentId\": \"" + other
                + "\", \"validFrom\": \"2025-04-01\"}")));
        assertEquals(4, version(api.putWithVersion("/org-units/" + ward + "/end", "{\"validTo\": \"2030-04-01\"}")));
        assertEquals(5, version(api.deleteWithVersion("/org-units/" + ward + "/end")));
        assertEquals(6, version(api.putWithVersion("/org-units/" + ward + "/time-settings", "{\"timeZone\": \"Asia/Tokyo\"}")));
        assertEquals(1, version(api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \""
                + ward + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}")));
        assertEquals(7, version(api.get("/org-units/" + ward)));
        String organization = realm.organization("x-ward");
        assertEquals(8, version(api.putWithVersion("/org-units/" + ward + "/organization", "{\"organizationId\": \""
                + organization + "\"}")));
        assertEquals(8, version(api.get("/org-units/" + ward)));
        // A write of the child is no write of the parents.
        assertEquals(1, version(api.get("/org-units/" + hospital)));
        assertEquals(1, version(api.get("/org-units/" + other)));

        // The removal of the organization in Keycloak removes the link with one query.
        KEYCLOAK.getKeycloakAdminClient().realm(realm.name).organizations().get(organization).delete().close();
        JsonNode unlinked = ok(api.get("/org-units/" + ward));
        assertFalse(unlinked.has("keycloakOrganizationId"));
        assertEquals(9, unlinked.get("version").asLong());
        assertEquals(10, version(api.deleteWithVersion("/org-units/" + ward + "/organization")));
    }

    @Test
    void aChangeOfTheTermsOrOfAPeriodCountsUpTheEmploymentOrTheAssignmentOnce() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-employment");
        Api api = realm.api;
        String hospital = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"));
        String other = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"別院\"}}, \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("GRADE", "g2", "2級");
        realm.refItem("JOB_TITLE", "chief", "主任");
        JsonNode staff = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + hospital + "\", \"validFrom\": \"2020-04-01\"}"));
        String employment = staff.get("employment").get("id").asText();
        String assignment = staff.get("assignment").get("id").asText();
        assertEquals(1, staff.get("employment").get("version").asLong());
        assertEquals(1, staff.get("assignment").get("version").asLong());

        // A promotion is one write of the employment and one of the assignment.
        JsonNode promoted = ok(api.postWithVersion("/employments/" + employment + "/promotion",
                "{\"validFrom\": \"2026-10-01\", \"grade\": \"g2\", \"jobTitle\": \"chief\"}"));
        assertEquals(2, promoted.get("employment").get("version").asLong());
        assertEquals(2, promoted.get("assignment").get("version").asLong());
        assertEquals(3, version(api.putWithVersion("/employments/" + employment + "/terms",
                "{\"validFrom\": \"2027-04-01\", \"workPercent\": 50}")));
        assertEquals(4, version(api.postWithVersion("/employments/" + employment + "/leave",
                "{\"validFrom\": \"2028-04-01\", \"validTo\": \"2028-10-01\"}")));
        assertEquals(4, version(api.get("/employments/" + employment)));
        // The end of the employment ends the assignment too.
        assertEquals(5, version(api.putWithVersion("/employments/" + employment + "/end", "{\"validTo\": \"2031-04-01\"}")));
        assertEquals(3, version(api.get("/assignments/" + assignment)));
        assertEquals(6, version(api.deleteWithVersion("/employments/" + employment + "/end")));

        // Every period of an assignment has the version of the assignment.
        JsonNode details = ok(api.putWithVersion("/assignments/" + assignment + "/details",
                "{\"validFrom\": \"2029-04-01\", \"jobTitle\": \"chief\", \"displayOrder\": 2}"));
        assertEquals(4, details.get("version").asLong());
        assertEquals(3, details.get("periods").size());
        assertEquals(5, version(api.putWithVersion("/assignments/" + assignment + "/end", "{\"validTo\": \"2030-10-01\"}")));
        assertEquals(6, version(api.deleteWithVersion("/assignments/" + assignment + "/end")));
        JsonNode moved = ok(api.postWithVersion("/assignments/" + assignment + "/move", "{\"orgUnitId\": \"" + other
                + "\", \"validFrom\": \"2029-10-01\"}"));
        assertEquals(7, moved.get("ended").get("version").asLong());
        assertEquals(1, moved.get("started").get("version").asLong());
        assertEquals(6, version(api.get("/employments/" + employment)));

        // A transfer ends the employment and starts new ones.
        String second = TestRealm.legalEntity(api, "第二法人");
        JsonNode transfer = ok(api.postWithVersion("/employments/" + employment + "/transfer", "{\"employerId\": \"" + second
                + "\", \"validFrom\": \"2027-10-01\"}"));
        assertEquals(7, transfer.get("ended").get("version").asLong());
        assertEquals(1, transfer.get("started").get("version").asLong());
        transfer.get("assignments").forEach(copy -> assertEquals(1, copy.get("version").asLong()));
    }

    @Test
    void writesThatDoNotComeThroughTheApiCountUpToo() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-outside");
        Api api = realm.api;
        String unit = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"));
        realm.refItem("QUALIFICATION", "license", "免許");

        // A mask writes the person and everything that names it.
        String masked = realm.user("masked");
        JsonNode staff = ok(api.post("/staff", "{\"userId\": \"" + masked + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\", \"staffNumber\": \"S-1\"}"));
        String qualification = id(api.post("/qualifications", "{\"userId\": \"" + masked
                + "\", \"kind\": \"license\", \"number\": \"1\", \"validFrom\": \"2010-04-01\"}"));
        String person = staff.get("employment").get("personId").asText();
        String employment = staff.get("employment").get("id").asText();
        assertEquals(2, version(api.putWithVersion("/employments/" + employment + "/end", "{\"validTo\": \"2024-04-01\"}")));
        assertEquals(2, version(api.postWithVersion("/people/" + person + "/mask", "")));
        assertEquals(2, version(api.get("/people/" + person)));
        assertEquals(3, version(api.get("/employments/" + employment)));
        assertEquals(3, version(api.get("/assignments/" + staff.get("assignment").get("id").asText())));
        assertEquals(2, version(api.get("/qualifications/" + qualification)));

        // The removal of a user ends what is open, with the lines where the user is the manager.
        String leaver = realm.user("leaver");
        JsonNode left = ok(api.post("/staff", "{\"userId\": \"" + leaver + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\"}"));
        JsonNode stays = ok(api.post("/staff", "{\"userId\": \"" + realm.user("stays") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        String managed = id(api.post("/reporting-lines", "{\"assignmentId\": \""
                + stays.get("assignment").get("id").asText() + "\", \"managerUserId\": \"" + leaver
                + "\", \"validFrom\": \"2020-04-01\"}"));
        realm.removeUser(leaver);
        assertEquals(2, version(api.get("/employments/" + left.get("employment").get("id").asText())));
        assertEquals(2, version(api.get("/assignments/" + left.get("assignment").get("id").asText())));
        assertEquals(2, version(api.get("/reporting-lines/" + managed)));
        assertEquals(1, version(api.get("/assignments/" + stays.get("assignment").get("id").asText())));

        // An import that creates a unit with its time settings creates it once. A later change of its details
        // and of its parent is one write.
        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet", "orgUnits": [
                  {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "本院"}}},
                  {"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "病棟"}}, "parentExternalId": "H", "timeZone": "Asia/Tokyo"}
                ]}"""));
        String imported = ok(api.get("/external-ids/lookup?resourceType=ORG_UNIT&source=sheet&externalId=W"))
                .get("resourceId").asText();
        assertEquals(1, version(api.get("/org-units/" + imported)));
        ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "source": "sheet", "orgUnits": [
                  {"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "東病棟"}}, "parentId": "%s"}
                ]}""".formatted(unit)));
        JsonNode changed = ok(api.get("/org-units/" + imported + "?asOf=2027-04-01"));
        assertEquals("東病棟", changed.get("name").asText());
        assertEquals(unit, changed.get("parentId").asText());
        assertEquals(2, changed.get("version").asLong());
    }

    @Test
    void everyListHasTheVersionOfEachEntry() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-lists");
        Api api = realm.api;
        String hospital = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"));
        String ward = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病棟\"}}, \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.putWithVersion("/org-units/" + ward, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"第1病棟\"}}, \"validFrom\": \"2020-04-01\"}"));
        String nurse = realm.user("nurse");
        JsonNode staff = ok(api.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"2020-04-01\"}"));
        String employment = staff.get("employment").get("id").asText();
        String assignment = staff.get("assignment").get("id").asText();
        String person = staff.get("employment").get("personId").asText();
        ok(api.putWithVersion("/employments/" + employment + "/terms", "{\"validFrom\": \"2021-04-01\", \"workPercent\": 80}"));
        ok(api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2021-04-01\", \"displayOrder\": 1}"));
        ok(api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2022-04-01\", \"displayOrder\": 2}"));
        ok(api.putWithVersion("/people/" + person, "{\"userId\": \"" + nurse + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"花子\"}}}"));
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String role = id(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + hospital + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.putWithVersion("/unit-roles/" + role + "/end", "{\"validTo\": \"2040-04-01\"}"));

        // Each list has the same version as the read of one resource.
        long unitVersion = version(api.get("/org-units/" + ward));
        assertEquals(2, unitVersion);
        String date = "asOf=2023-04-01";
        assertEquals(unitVersion, entry(ok(api.get("/org-units?" + date)), "id", ward).get("version").asLong());
        assertEquals(unitVersion, entry(ok(api.get("/org-units/" + hospital + "/children?" + date)), "id", ward)
                .get("version").asLong());
        assertEquals(unitVersion, entry(ok(api.get("/org-units/" + hospital + "/subtree?" + date)), "id", ward)
                .get("version").asLong());
        assertEquals(unitVersion, ok(api.get("/org-units/" + ward + "/path?" + date)).get(1).get("version").asLong());
        assertEquals(1, ok(api.get("/org-units/" + ward + "/path?" + date)).get(0).get("version").asLong());

        long assignmentVersion = version(api.get("/assignments/" + assignment));
        assertEquals(3, assignmentVersion);
        assertEquals(assignmentVersion, entry(ok(api.get("/org-units/" + ward + "/members?" + date)),
                "assignmentId", assignment).get("version").asLong());
        assertEquals(assignmentVersion, entry(ok(api.get("/assignments?" + date)), "assignmentId", assignment)
                .get("version").asLong());
        assertEquals(assignmentVersion, entry(ok(api.get("/users/" + nurse + "/assignments?" + date)),
                "assignmentId", assignment).get("version").asLong());
        assertEquals(assignmentVersion, entry(ok(api.get("/people/" + person + "/assignments?" + date)),
                "assignmentId", assignment).get("version").asLong());

        long employmentVersion = version(api.get("/employments/" + employment));
        assertEquals(2, employmentVersion);
        assertEquals(employmentVersion, entry(ok(api.get("/employments")), "id", employment).get("version").asLong());
        JsonNode history = ok(api.get("/users/" + nurse + "/history"));
        assertEquals(employmentVersion, history.get("employments").get(0).get("version").asLong());
        assertEquals(assignmentVersion, history.get("assignments").get(0).get("version").asLong());

        assertEquals(2, entry(ok(api.get("/people")), "id", person).get("version").asLong());
        assertEquals(2, entry(ok(api.get("/unit-roles?" + date)), "id", role).get("version").asLong());
        assertEquals(2, entry(ok(api.get("/org-units/" + hospital + "/role-holders?" + date)), "id", role)
                .get("version").asLong());
        assertEquals(2, entry(ok(api.get("/people/" + person + "/unit-roles?" + date)), "id", role)
                .get("version").asLong());
        assertEquals(1, entry(ok(api.get("/hierarchies")), "code", "default").get("version").asLong());
        ok(api.get("/ref-items?kind=UNIT_ROLE")).forEach(item -> assertEquals(1, item.get("version").asLong()));
    }

    @Test
    void aRestoreKeepsTheVersionsOfTheExport() throws Exception {
        TestRealm source = TestRealm.withEmployer("versions-export");
        Api api = source.api;
        String nurseUser = source.user("nurse");
        String bossUser = source.user("boss");
        ok(api.post("/ref-kinds", "{\"code\": \"site\", \"name\": \"拠点\"}"));
        ok(api.putWithVersion("/ref-kinds/site", "{\"name\": \"事業所\"}"));
        String item = id(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}}"));
        ok(api.putWithVersion("/ref-items/" + item, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京本社\"}}}"));
        String definition = id(api.post("/attribute-definitions",
                "{\"target\": \"PERSON\", \"name\": \"beds\", \"type\": \"NUMBER\"}"));
        ok(api.putWithVersion("/attribute-definitions/" + definition, "{\"type\": \"STRING\"}"));
        String hierarchy = id(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        ok(api.putWithVersion("/hierarchies/" + hierarchy, "{\"name\": \"プロジェクト\"}"));
        String unit = id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"));
        ok(api.putWithVersion("/org-units/" + unit, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"本院\"}}, \"validFrom\": \"2024-04-01\"}"));
        JsonNode nurse = ok(api.post("/staff", "{\"userId\": \"" + nurseUser + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2020-04-01\"}"));
        String employment = nurse.get("employment").get("id").asText();
        String assignment = nurse.get("assignment").get("id").asText();
        ok(api.putWithVersion("/employments/" + employment + "/terms", "{\"validFrom\": \"2021-04-01\", \"workPercent\": 80}"));
        ok(api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2021-04-01\", \"displayOrder\": 1}"));
        ok(api.putWithVersion("/assignments/" + assignment + "/details", "{\"validFrom\": \"2022-04-01\", \"displayOrder\": 2}"));
        String person = nurse.get("employment").get("personId").asText();
        ok(api.putWithVersion("/people/" + person, "{\"userId\": \"" + nurseUser + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"花子\"}}}"));
        String line = id(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + bossUser + "\", \"validFrom\": \"2021-04-01\"}"));
        ok(api.putWithVersion("/reporting-lines/" + line + "/end", "{\"validTo\": \"2040-04-01\"}"));
        source.refItem("UNIT_ROLE", "approver", "承認者");
        String role = id(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + unit + "\", \"role\": \"approver\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.putWithVersion("/unit-roles/" + role + "/end", "{\"validTo\": \"2040-04-01\"}"));
        source.refItem("QUALIFICATION", "license", "免許");
        String qualification = id(api.post("/qualifications", "{\"personId\": \"" + person
                + "\", \"kind\": \"license\", \"validFrom\": \"2010-04-01\"}"));
        ok(api.putWithVersion("/qualifications/" + qualification,
                "{\"kind\": \"license\", \"number\": \"9\", \"validFrom\": \"2010-04-01\"}"));
        String payroll = "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + unit
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"}";
        ok(api.putWithVersion("/external-ids", payroll.formatted("P-1")));
        ok(api.putWithVersion("/external-ids", payroll.formatted("P-2")));
        String order = id(api.post("/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}"));
        ok(api.postWithVersion("/change-orders/" + order + "/announce", ""));

        ObjectNode before = RealmExportIT.export(api);
        // The two IDs of another system wrote the unit twice more.
        assertEquals(List.of(2L, 2L, 2L, 2L, 4L, 2L, 2L, 3L, 2L, 2L, 2L, 2L, 2L),
                List.of(before.get("refKinds").get(0), entry(before.get("refItems"), "id", item),
                        entry(before.get("attributeDefinitions"), "id", definition),
                        entry(before.get("hierarchies"), "id", hierarchy), entry(before.get("orgUnits"), "id", unit),
                        entry(before.get("people"), "id", person), entry(before.get("employments"), "id", employment),
                        entry(before.get("assignments"), "assignmentId", assignment),
                        entry(before.get("reportingLines"), "id", line), entry(before.get("unitRoles"), "id", role),
                        entry(before.get("qualifications"), "id", qualification),
                        entry(before.get("externalIds"), "source", "payroll"),
                        entry(before.get("changeOrders"), "id", order))
                        .stream().map(row -> row.get("version").asLong()).toList());

        TestRealm target = TestRealm.create("versions-target");
        target.user("nurse");
        target.user("boss");
        source.remove();
        ok(target.api.post("/restore", JSON.writeValueAsString(before)));

        assertEquals(before, RealmExportIT.export(target.api));
        assertEquals(4, version(target.api.get("/org-units/" + unit)));
        assertEquals(3, version(target.api.get("/assignments/" + assignment)));
        assertEquals(2, version(target.api.get("/employments/" + employment)));
        assertEquals(2, version(target.api.get("/ref-items/" + item)));
        assertEquals(2, version(target.api.get("/change-orders/" + order)));

        // An export from before the versions has none. Its resources start at 1.
        before.get("refKinds").forEach(kind -> ((ObjectNode) kind).remove("version"));
        before.get("assignments").forEach(rows -> ((ObjectNode) rows).remove("version"));
        ok(target.api.post("/restore?replace=true", JSON.writeValueAsString(before)));
        assertEquals(1, entry(ok(target.api.get("/ref-kinds")), "code", "site").get("version").asLong());
        assertEquals(1, version(target.api.get("/assignments/" + assignment)));
        assertEquals(4, version(target.api.get("/org-units/" + unit)));
    }
}
