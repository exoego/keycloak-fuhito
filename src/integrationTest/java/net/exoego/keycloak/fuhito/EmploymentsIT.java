package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmploymentsIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String nurse;
    private static String doctor;
    private static String fullTimeId;

    @BeforeAll
    static void createRealms() throws Exception {
        realm = TestRealm.withEmployer("employments");
        otherRealm = TestRealm.withEmployer("employments");
        api = realm.api;
        nurse = realm.user("nurse");
        doctor = realm.user("doctor");
        fullTimeId = realm.refItem("EMPLOYMENT_TYPE", "full-time", "常勤");
        realm.refItem("EMPLOYMENT_TYPE", "temporary", "臨時職員");
        realm.refItem("GRADE", "g3", "3等級");
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void startsAnEmploymentWithOnlyAUserAndADate() throws Exception {
        JsonNode employment = created(api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals(nurse, employment.get("userId").asText());
        assertEquals("2026-04-01", employment.get("validFrom").asText());
        assertFalse(employment.has("validTo"));
        assertFalse(employment.has("staffNumber"));
        assertEquals(1, employment.get("terms").size());
        JsonNode terms = employment.get("terms").get(0);
        assertEquals("ACTIVE", terms.get("status").asText());
        assertEquals("2026-04-01", terms.get("validFrom").asText());
        assertFalse(terms.has("employmentType"));
        assertEquals(employment, Api.json(api.get("/employments/" + employment.get("id").asText())));
    }

    @Test
    void startsATemporaryPartTimeEmployment() throws Exception {
        JsonNode employment = created(api.post("/employments", "{\"userId\": \"" + doctor + "\", "
                + "\"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\", \"staffNumber\": \"000777\", "
                + "\"employmentType\": \"temporary\", \"grade\": \"g3\", \"workPercent\": 60.5, "
                + "\"attributes\": {\"note\": \"週3日\"}, \"externalId\": \"E-777\", \"source\": \"hr\"}"));

        assertEquals("2027-04-01", employment.get("validTo").asText());
        assertEquals("000777", employment.get("staffNumber").asText());
        assertEquals("E-777", employment.get("externalId").asText());
        JsonNode terms = employment.get("terms").get(0);
        assertEquals("temporary", terms.get("employmentType").asText());
        assertEquals("g3", terms.get("grade").asText());
        assertEquals(60.5, terms.get("workPercent").asDouble());
        assertEquals("週3日", terms.get("attributes").get("note").asText());
        assertEquals("2027-04-01", terms.get("validTo").asText());

        // The same person may be rehired with the same staff number. Another person may not take it.
        created(api.post("/employments", "{\"userId\": \"" + doctor
                + "\", \"validFrom\": \"2028-04-01\", \"staffNumber\": \"000777\"}"));
        assertEquals("employment.staff_number_taken", errorOf(409, api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\", \"staffNumber\": \"000777\"}")));
    }

    @Test
    void changesTermsForALeaveAReturnAndANewEmploymentType() throws Exception {
        String id = created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"temporary\", \"workPercent\": 50}"))
                .get("id").asText();

        // Parental leave from October, back in April as full-time staff.
        HttpResponse<String> leave = api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"employmentType\": \"temporary\", \"status\": \"ON_LEAVE\", \"workPercent\": 50}");
        assertEquals(200, leave.statusCode(), leave.body());
        JsonNode employment = Api.json(api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2027-04-01\", \"employmentType\": \"full-time\", \"grade\": \"g3\", \"workPercent\": 100}"));

        JsonNode terms = employment.get("terms");
        assertEquals(3, terms.size());
        assertEquals("ACTIVE", terms.get(0).get("status").asText());
        assertEquals("2026-10-01", terms.get(0).get("validTo").asText());
        assertEquals("ON_LEAVE", terms.get(1).get("status").asText());
        assertEquals("temporary", terms.get(1).get("employmentType").asText());
        assertEquals("2027-04-01", terms.get(1).get("validTo").asText());
        assertEquals("ACTIVE", terms.get(2).get("status").asText());
        assertEquals("full-time", terms.get(2).get("employmentType").asText());
        assertEquals("g3", terms.get(2).get("grade").asText());
        assertFalse(terms.get(2).has("validTo"));
        assertFalse(employment.has("validTo"));
        assertEquals(employment, Api.json(api.get("/employments/" + id)));

        // A change on the first date of stored terms corrects them.
        JsonNode corrected = Api.json(api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"status\": \"SUSPENDED\"}"));
        assertEquals(3, corrected.get("terms").size());
        assertEquals("SUSPENDED", corrected.get("terms").get(1).get("status").asText());
        assertFalse(corrected.get("terms").get(1).has("employmentType"));
    }

    @Test
    void rejectsABadChangeOfTerms() throws Exception {
        String id = created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\"}")).get("id").asText();

        assertEquals("employment.not_valid_on", errorOf(404, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2027-04-01\"}")));
        assertEquals("employment.not_valid_on", errorOf(404, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-03-31\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/employments/" + id + "/terms", "{}")));
        assertEquals("invalid.choice", errorOf(400, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-10-01\", \"status\": \"x\"}")));
        assertEquals("employment.not_found", errorOf(404, api.put("/employments/nope/terms", "{\"validFrom\": \"2026-10-01\"}")));
        assertEquals("employment.not_found", errorOf(404, otherRealm.api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-10-01\"}")));
        assertEquals(1, Api.json(api.get("/employments/" + id)).get("terms").size());
    }

    @Test
    void leavingEndsTheTermsAndTheAssignments() throws Exception {
        realm.refItem("END_REASON", "resignation-" + System.nanoTime(), "退職");
        String reason = "retirement-" + System.nanoTime();
        realm.refItem("END_REASON", reason, "定年");
        String user = realm.user("leaver-" + System.nanoTime());
        String id = created(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String unit = created(api.post("/org-units", "{\"name\": \"総務課\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String assignment = "{\"employmentId\": \"" + id + "\", \"orgUnitId\": \"" + unit + "\", \"validFrom\": \"";
        String open = created(api.post("/assignments", assignment + "2026-04-01\"}")).get("id").asText();
        String future = created(api.post("/assignments", assignment + "2028-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2028-04-01\", \"status\": \"ON_LEAVE\"}").statusCode());

        HttpResponse<String> response = api.put("/employments/" + id + "/end",
                "{\"validTo\": \"2027-04-01\", \"endReason\": \"" + reason + "\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode ended = Api.json(response);
        assertEquals("2027-04-01", ended.get("validTo").asText());
        assertEquals(reason, ended.get("endReason").asText());
        assertEquals(1, ended.get("terms").size());
        assertEquals("2027-04-01", ended.get("terms").get(0).get("validTo").asText());
        assertEquals("2027-04-01", Api.json(api.get("/assignments/" + open)).get("validTo").asText());
        assertEquals("assignment.not_found", errorOf(404, api.get("/assignments/" + future)));

        // A rehire is a new employment of the same user. The old one stays readable.
        String rehire = created(api.post("/employments", "{\"userId\": \"" + user + "\", \"validFrom\": \"2029-04-01\"}"))
                .get("id").asText();
        assertEquals(ended, Api.json(api.get("/employments/" + id)));
        assertFalse(Api.json(api.get("/employments/" + rehire)).has("validTo"));

        // The resignation is taken back.
        JsonNode reopened = Api.json(api.delete("/employments/" + id + "/end"));
        assertFalse(reopened.has("validTo"));
        assertFalse(reopened.has("endReason"));
        assertFalse(reopened.get("terms").get(0).has("validTo"));
    }

    @Test
    void extendsAFixedTermEmploymentAndRejectsABadEnd() throws Exception {
        String user = realm.user("contract-" + System.nanoTime());
        String id = created(api.post("/employments", "{\"userId\": \"" + user
                + "\", \"validFrom\": \"2026-04-01\", \"validTo\": \"2027-04-01\", \"employmentType\": \"temporary\"}")).get("id").asText();

        JsonNode extended = Api.json(api.put("/employments/" + id + "/end", "{\"validTo\": \"2028-04-01\"}"));

        assertEquals("2028-04-01", extended.get("validTo").asText());
        assertEquals("2028-04-01", extended.get("terms").get(0).get("validTo").asText());
        assertEquals("temporary", extended.get("terms").get(0).get("employmentType").asText());
        assertEquals("employment.not_valid_on", errorOf(404, api.put("/employments/" + id + "/end", "{\"validTo\": \"2026-04-01\"}")));
        assertEquals("ref_item.unknown", errorOf(400, api.put("/employments/" + id + "/end", "{\"validTo\": \"2027-04-01\", \"endReason\": \"nope\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/employments/" + id + "/end", "{}")));
        assertEquals("employment.not_found", errorOf(404, otherRealm.api.put("/employments/" + id + "/end", "{\"validTo\": \"2027-04-01\"}")));
        assertEquals("employment.not_found", errorOf(404, otherRealm.api.delete("/employments/" + id + "/end")));
        assertEquals(extended, Api.json(api.get("/employments/" + id)));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String user = "\"userId\": \"" + nurse + "\"";
        assertEquals("person.user_unknown", errorOf(400, api.post("/employments",
                "{\"userId\": \"no-such-user\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.post("/employments", "{" + user + "}")));
        assertEquals("invalid.period", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"validTo\": \"2026-03-01\"}")));
        assertEquals("ref_item.unknown", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"employmentType\": \"g3\"}")));
        assertEquals("invalid.choice", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"status\": \"GONE\"}")));
        assertEquals("invalid.percent", errorOf(400, api.post("/employments",
                "{" + user + ", \"validFrom\": \"2026-04-01\", \"workPercent\": 0}")));
        assertEquals("employment.not_found", errorOf(404, api.get("/employments/no-such-id")));
        assertEquals(401, new Api(realm.name, null).post("/employments", "{}").statusCode());
    }

    @Test
    void oneRealmDoesNotSeeTheUsersOrEmploymentsOfAnother() throws Exception {
        String id = created(api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        assertEquals("employment.not_found", errorOf(404, otherRealm.api.get("/employments/" + id)));
        assertEquals("person.user_unknown", errorOf(400, otherRealm.api.post("/employments",
                "{\"userId\": \"" + nurse + "\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void anEmploymentTypeInUseCannotBeDeleted() throws Exception {
        created(api.post("/employments", "{\"userId\": \"" + nurse
                + "\", \"validFrom\": \"2026-04-01\", \"employmentType\": \"full-time\"}"));

        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + fullTimeId)));
    }

    @Test
    void anEmploymentNamesItsEmployerWhenTheRealmHasSeveralLegalEntities() throws Exception {
        TestRealm group = TestRealm.create("employments-group");
        Api groupApi = group.api;
        String taro = group.user("taro");
        String jiro = group.user("jiro");
        String start = "\"validFrom\": \"2026-04-01\"";

        // With no legal entity, nobody can be employed.
        assertEquals("employment.no_legal_entity",
                errorOf(400, groupApi.post("/employments", "{\"userId\": \"" + taro + "\", " + start + "}")));

        // With one, the request need not name it.
        String corporation = group.legalEntity("医療法人X会");
        JsonNode first = created(groupApi.post("/employments",
                "{\"userId\": \"" + taro + "\", \"staffNumber\": \"1001\", " + start + "}"));
        assertEquals(corporation, first.get("employerId").asText());
        assertEquals(corporation, Api.json(groupApi.get("/employments/" + first.get("id").asText())).get("employerId").asText());

        // With two, it must.
        String company = group.legalEntity("株式会社Yサービス");
        String ward = created(groupApi.post("/org-units", "{\"name\": \"1病棟\", \"parentId\": \"" + corporation
                + "\", " + start + "}")).get("id").asText();
        assertEquals("employment.employer_required",
                errorOf(400, groupApi.post("/employments", "{\"userId\": \"" + jiro + "\", " + start + "}")));
        assertEquals("employment.employer_required", errorOf(400, groupApi.post("/staff",
                "{\"userId\": \"" + jiro + "\", \"orgUnitId\": \"" + ward + "\", " + start + "}")));
        assertEquals("employment.employer_not_legal_entity", errorOf(400, groupApi.post("/employments",
                "{\"userId\": \"" + jiro + "\", \"employerId\": \"" + ward + "\", " + start + "}")));
        assertEquals("employment.employer_unknown", errorOf(400, groupApi.post("/employments",
                "{\"userId\": \"" + jiro + "\", \"employerId\": \"nope\", " + start + "}")));

        // Each legal entity gives its own staff numbers. The ward is where the person works, not who employs.
        JsonNode seconded = created(groupApi.post("/staff", "{\"userId\": \"" + jiro + "\", \"employerId\": \"" + company
                + "\", \"orgUnitId\": \"" + ward + "\", \"staffNumber\": \"1001\", " + start + "}"));
        assertEquals(company, seconded.get("employment").get("employerId").asText());
        assertEquals("employment.staff_number_taken", errorOf(409, groupApi.post("/employments",
                "{\"userId\": \"" + jiro + "\", \"employerId\": \"" + corporation + "\", \"staffNumber\": \"1001\", "
                        + "\"validFrom\": \"2027-04-01\"}")));
        // Jiro works outside the employer, so the assignment is a secondment. Taro works for the corporation.
        created(groupApi.post("/assignments", "{\"employmentId\": \"" + first.get("id").asText() + "\", \"orgUnitId\": \""
                + ward + "\", \"primary\": true, \"displayOrder\": 1, " + start + "}"));
        JsonNode members = Api.json(groupApi.get("/org-units/" + ward + "/members?asOf=2026-04-01"));
        assertEquals(List.of(jiro, taro), members.findValuesAsText("userId"));
        assertEquals(List.of(company, corporation), members.findValuesAsText("employerId"));
        assertTrue(members.get(0).get("seconded").asBoolean());
        assertFalse(members.get(1).get("seconded").asBoolean());
        assertTrue(Api.json(groupApi.get("/users/" + jiro + "/assignments?asOf=2026-04-01")).get(0).get("seconded").asBoolean());
        // The people below the corporation are both there, the guest among them.
        assertEquals(2, Api.json(groupApi.get("/org-units/" + corporation
                + "/members?includeDescendants=true&asOf=2026-04-01")).size());

        // An end does not change the employer.
        assertEquals(company, Api.json(groupApi.put("/employments/" + seconded.get("employment").get("id").asText() + "/end",
                "{\"validTo\": \"2030-04-01\"}")).get("employerId").asText());
    }

    @Test
    void anEmployerStaysALegalEntityWhileAnEmploymentNamesIt() throws Exception {
        TestRealm scratch = TestRealm.create("employments-keep-entity");
        Api scratchApi = scratch.api;
        String company = scratch.legalEntity("株式会社Z");
        String type = Api.json(scratchApi.get("/ref-items?kind=UNIT_TYPE")).get(0).get("id").asText();
        scratch.refItem("UNIT_TYPE", "department", "部");
        String employment = created(scratchApi.post("/employments", "{\"userId\": \"" + scratch.user("taro")
                + "\", \"validFrom\": \"2026-04-01\", \"validTo\": \"2028-04-01\"}")).get("id").asText();

        assertEquals("ref_item.legal_entity_in_use", errorOf(409, scratchApi.put("/ref-items/" + type, "{\"name\": \"法人\"}")));
        assertEquals(200, scratchApi.put("/ref-items/" + type, "{\"name\": \"会社\", \"legalEntity\": true}").statusCode());
        assertEquals("org_unit.is_employer", errorOf(409, scratchApi.put("/org-units/" + company,
                "{\"name\": \"株式会社Z\", \"unitType\": \"department\", \"validFrom\": \"2026-01-01\"}")));
        assertEquals("org_unit.is_employer", errorOf(409, scratchApi.put("/org-units/" + company,
                "{\"name\": \"株式会社Z\", \"validFrom\": \"2026-01-01\"}")));
        assertEquals(200, scratchApi.put("/org-units/" + company,
                "{\"name\": \"Z社\", \"unitType\": \"legal-entity\", \"validFrom\": \"2026-01-01\"}").statusCode());
        assertEquals("org_unit.has_employments",
                errorOf(409, scratchApi.put("/org-units/" + company + "/end", "{\"validTo\": \"2028-03-31\"}")));
        assertEquals(company, Api.json(scratchApi.get("/employments/" + employment)).get("employerId").asText());

        // The company can end on the day after the last day of the employment.
        assertEquals(200, scratchApi.put("/org-units/" + company + "/end", "{\"validTo\": \"2028-04-01\"}").statusCode());
    }

    @Test
    void aTransferMovesAPersonToAnotherEmployerInOneWrite() throws Exception {
        TestRealm group = TestRealm.create("employments-transfer");
        Api groupApi = group.api;
        String corporation = group.legalEntity("医療法人X会");
        String company = group.legalEntity("株式会社Yサービス");
        group.refItem("END_REASON", "transfer", "転籍");
        String kitchen = created(groupApi.post("/org-units", "{\"name\": \"栄養科\", \"parentId\": \"" + corporation
                + "\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String suzuki = group.user("suzuki");
        String chief = group.user("chief");
        JsonNode hired = created(groupApi.post("/staff", "{\"userId\": \"" + suzuki + "\", \"employerId\": \"" + company
                + "\", \"orgUnitId\": \"" + kitchen + "\", \"staffNumber\": \"Y-1001\", \"validFrom\": \"2020-04-01\"}"));
        String old = hired.get("employment").get("id").asText();
        String oldAssignment = hired.get("assignment").get("id").asText();
        created(groupApi.post("/reporting-lines", "{\"assignmentId\": \"" + oldAssignment + "\", \"managerUserId\": \""
                + chief + "\", \"validFrom\": \"2020-04-01\"}"));
        // A leave that is planned for 2029 must not get lost.
        assertEquals(200, groupApi.put("/employments/" + old + "/terms",
                "{\"validFrom\": \"2029-04-01\", \"status\": \"ON_LEAVE\"}").statusCode());

        String transferPath = "/employments/" + old + "/transfer";
        assertEquals("employment.same_employer", errorOf(400, groupApi.post(transferPath,
                "{\"employerId\": \"" + company + "\", \"validFrom\": \"2027-04-01\"}")));
        assertEquals("employment.employer_not_legal_entity", errorOf(400, groupApi.post(transferPath,
                "{\"employerId\": \"" + kitchen + "\", \"validFrom\": \"2027-04-01\"}")));
        assertEquals("employment.not_valid_on", errorOf(404, groupApi.post(transferPath,
                "{\"employerId\": \"" + corporation + "\", \"validFrom\": \"2020-04-01\"}")));
        assertEquals(400, groupApi.post(transferPath, "{\"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(404, groupApi.post("/employments/nope/transfer",
                "{\"employerId\": \"" + corporation + "\", \"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(1, Api.json(groupApi.get("/users/" + suzuki + "/history")).get("employments").size());

        JsonNode transfer = created(groupApi.post(transferPath, "{\"employerId\": \"" + corporation
                + "\", \"validFrom\": \"2027-04-01\", \"staffNumber\": \"X-2040\", \"endReason\": \"transfer\"}"));

        JsonNode ended = transfer.get("ended");
        assertEquals("2027-04-01", ended.get("validTo").asText());
        assertEquals("transfer", ended.get("endReason").asText());
        assertEquals(company, ended.get("employerId").asText());
        JsonNode started = transfer.get("started");
        String next = started.get("id").asText();
        assertEquals(corporation, started.get("employerId").asText());
        assertEquals("X-2040", started.get("staffNumber").asText());
        assertEquals(old, started.get("continuesEmploymentId").asText());
        assertEquals("2027-04-01", started.get("validFrom").asText());
        assertEquals(List.of("ACTIVE", "ON_LEAVE"), started.get("terms").findValuesAsText("status"));
        assertEquals(List.of("2027-04-01", "2029-04-01"), started.get("terms").findValuesAsText("validFrom"));

        // The person still works in the same place, under the same manager, with no day between.
        JsonNode assignment = transfer.get("assignments").get(0);
        assertEquals(1, transfer.get("assignments").size());
        assertEquals(next, assignment.get("employmentId").asText());
        assertEquals(kitchen, assignment.get("orgUnitId").asText());
        assertEquals("2027-04-01", assignment.get("validFrom").asText());
        assertEquals(1, transfer.get("reportingLines").size());
        assertEquals(assignment.get("id").asText(), transfer.get("reportingLines").get(0).get("assignmentId").asText());
        assertEquals(1, Api.json(groupApi.get("/users/" + suzuki + "/assignments?asOf=2027-03-31")).size());
        assertEquals(1, Api.json(groupApi.get("/users/" + suzuki + "/assignments?asOf=2027-04-01")).size());
        assertEquals(List.of(chief),
                Api.json(groupApi.get("/users/" + suzuki + "/managers?asOf=2027-04-01")).findValuesAsText("userId"));
        JsonNode history = Api.json(groupApi.get("/users/" + suzuki + "/history"));
        assertEquals(List.of(old, next), history.get("employments").findValuesAsText("id").subList(0, 2));
        assertEquals(2, history.get("employments").size());

        // Every part of the transfer is in the change log.
        assertEquals(List.of("CREATE", "UPDATE", "UPDATE"), Api.json(groupApi.get(
                "/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + old)).findValuesAsText("operation"));
        assertEquals(List.of("CREATE"), Api.json(groupApi.get(
                "/change-log?resourceType=FUHITO_EMPLOYMENT&resourceId=" + next)).findValuesAsText("operation"));
        assertEquals(List.of("CREATE"), Api.json(groupApi.get("/change-log?resourceType=FUHITO_ASSIGNMENT&resourceId="
                + assignment.get("id").asText())).findValuesAsText("operation"));
        assertEquals(List.of("CREATE"), Api.json(groupApi.get("/change-log?resourceType=FUHITO_REPORTING_LINE&resourceId="
                + transfer.get("reportingLines").get(0).get("id").asText())).findValuesAsText("operation"));
    }
}
