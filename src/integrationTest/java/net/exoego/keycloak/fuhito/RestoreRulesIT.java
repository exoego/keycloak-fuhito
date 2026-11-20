package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreRulesIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}";

    private static final String TERMS = "{\"id\": \"t-2\", \"validFrom\": \"2022-04-01\", \"status\": \"ACTIVE\", "
            + "\"attributes\": {}}";

    /**
     * A change of a document at a JSON pointer, as {@link RestoreKeysIT#change} makes it.
     *
     * @param at the JSON pointer
     * @param row the row or the fields
     */
    private record Change(String at, String row) {
    }

    /**
     * Changes of the document of {@link RestoreKeysIT} and the row of the error that they give.
     *
     * @param list the list of the error
     * @param id the ID of the row of the error
     * @param field the field of the error
     * @param rule the error of a write for the rule, or null when a write has none
     * @param changes the changes
     */
    private record Case(String list, String id, String field, String rule, List<Change> changes) {

        Map<String, String> params() {
            Map<String, String> params = new HashMap<>(Map.of("list", list, "id", id, "field", field));
            if (rule != null) {
                params.put("rule", rule);
            }
            return params;
        }
    }

    private static Change at(String at, String row) {
        return new Change(at, row);
    }

    private static Case breaks(String list, String id, String field, String rule, Change... changes) {
        return new Case(list, id, field, rule, List.of(changes));
    }

    private static String link(String parentId) {
        return "{\"id\": \"l-2\", \"hierarchy\": \"default\", \"parentId\": \"" + parentId + "\", "
                + "\"validFrom\": \"2020-04-01\"}";
    }

    private static String item(String id, String kind, String marks) {
        return "{\"id\": \"" + id + "\", \"kind\": \"" + kind + "\", \"code\": \"" + id + "\", " + marks
                + "\"attributes\": {}, \"versions\": [{\"id\": \"n-" + id + "\", " + NAMES + "}]}";
    }

    // Each case fails before anything is written. So the document restores into the realm at the end.
    private static void assertEachBreaks(String prefix, List<Case> cases) throws Exception {
        TestRealm target = RestoreKeysIT.target(prefix);
        try {
            assertAll(cases.stream().map(rule -> (Executable) () -> {
                ObjectNode document = RestoreKeysIT.document();
                for (Change change : rule.changes()) {
                    RestoreKeysIT.change(document, change.at(), change.row());
                }
                HttpResponse<String> response = target.api.post("/restore", JSON.writeValueAsString(document));
                assertEquals(400, response.statusCode(), rule + " " + response.body());
                JsonNode error = Api.json(response);
                assertEquals("restore.bad_value", error.get("error").asText(), response.body());
                assertEquals(JSON.valueToTree(rule.params()), error.get("params"), response.body());
            }));
            HttpResponse<String> restored = target.api.post("/restore",
                    JSON.writeValueAsString(RestoreKeysIT.document()));
            assertEquals(200, restored.statusCode(), restored.body());
        } finally {
            target.remove();
        }
    }

    @Test
    void anEmploymentOrTermsThatBreakARuleOfAWriteFailTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-employments", List.of(
                breaks("employments", "e-1", "endReasonId", "employment.end_reason_without_end",
                        at("/refItems", item("quit", "END_REASON", "")),
                        at("/employments/0", "{\"endReasonId\": \"quit\"}")),
                breaks("employments", "e-1", "validFrom", "employment.employer_unknown",
                        at("/employments/0", "{\"validFrom\": \"2019-04-01\"}"),
                        at("/employments/0/terms/0", "{\"validFrom\": \"2019-04-01\"}")),
                breaks("employments", "e-1", "employerId", "employment.employer_not_legal_entity",
                        at("/orgUnits/0/versions/0", "{\"unitTypeId\": null}")),
                breaks("employments", "e-1", "validTo", "employment.employer_ends",
                        at("/orgUnits/2/versions/0", "{\"unitTypeId\": \"corp\"}"),
                        at("/employments/0", "{\"employerId\": \"u-3\"}")),
                breaks("employments", "e-1", "validTo", "employment.continues_holds",
                        at("/employments", "{\"id\": \"e-2\", \"personId\": \"p-1\", \"employerId\": \"u-1\", "
                                + "\"validFrom\": \"2022-04-01\", \"continuesEmploymentId\": \"e-1\", "
                                + "\"terms\": [" + TERMS + "]}")),
                breaks("employments", "e-1", "terms", null, at("/employments/0", "{\"terms\": []}")),
                breaks("employment-terms", "t-1", "validFrom", null,
                        at("/employments/0/terms/0", "{\"validFrom\": \"2019-04-01\"}")),
                breaks("employment-terms", "t-2", "validFrom", null,
                        at("/employments/0/terms/0", "{\"validTo\": \"2021-04-01\"}"),
                        at("/employments/0/terms", TERMS)),
                breaks("employment-terms", "t-1", "validTo", null,
                        at("/employments/0/terms/0", "{\"validTo\": \"2021-04-01\"}")),
                breaks("employment-terms", "t-1", "leaveReasonId", "employment.leave_reason_not_on_leave",
                        at("/refItems", item("sick", "LEAVE_REASON", "")),
                        at("/employments/0/terms/0", "{\"leaveReasonId\": \"sick\"}")),
                breaks("employment-terms", "t-1", "contractEndDate", "employment.contract_end_before_terms",
                        at("/employments/0/terms/0", "{\"contractEndDate\": \"2019-04-01\"}"))));
    }

    @Test
    void anAssignmentALineOrARoleThatBreaksARuleOfAWriteFailsTheRestoreAndNothingIsWritten() throws Exception {
        assertEachBreaks("restore-assignments", List.of(
                breaks("assignments", "a-1", "periods", null, at("/assignments/0", "{\"periods\": []}")),
                breaks("assignments", "r-2", "validFrom", null,
                        at("/assignments/0/periods/0", "{\"validTo\": \"2021-04-01\"}"),
                        at("/assignments/0/periods", "{\"rowId\": \"r-2\", \"employmentId\": \"e-1\", "
                                + "\"orgUnitId\": \"u-1\", \"validFrom\": \"2022-04-01\", \"attributes\": {}}")),
                breaks("assignments", "r-1", "validTo", "assignment.outside_employment",
                        at("/employments/0", "{\"validTo\": \"2021-04-01\"}"),
                        at("/employments/0/terms/0", "{\"validTo\": \"2021-04-01\"}")),
                breaks("assignments", "r-1", "validTo", "assignment.org_unit_not_valid",
                        at("/assignments/0/periods/0", "{\"orgUnitId\": \"u-3\"}")),
                breaks("assignments", "r-1", "validTo", "assignment.continues_holds",
                        at("/assignments", "{\"assignmentId\": \"a-2\", \"periods\": [{\"rowId\": \"r-2\", "
                                + "\"employmentId\": \"e-1\", \"orgUnitId\": \"u-2\", \"validFrom\": \"2022-04-01\", "
                                + "\"continuesAssignmentId\": \"a-1\", \"attributes\": {}}]}")),
                breaks("reporting-lines", "rl-1", "validFrom", "reporting_line.outside_assignment",
                        at("/people", "{\"id\": \"p-2\", \"attributes\": {}}"),
                        at("", "{\"reportingLines\": [{\"id\": \"rl-1\", \"assignmentId\": \"a-1\", "
                                + "\"managerPersonId\": \"p-2\", \"kind\": \"SOLID\", "
                                + "\"validFrom\": \"2019-04-01\"}]}")),
                breaks("unit-roles", "ur-1", "validFrom", "unit_role.outside_employment",
                        at("/unitRoles/0", "{\"validFrom\": \"2019-04-01\"}")),
                breaks("unit-roles", "ur-1", "validTo", "unit_role.org_unit_not_valid",
                        at("/unitRoles/0", "{\"orgUnitId\": \"u-3\"}"))));
    }

    @Test
    void anOrgUnitThatBreaksARuleOfAWriteFailsTheRestoreAndNothingIsWritten() throws Exception {
        String region = item("region", "UNIT_TYPE", "\"rollup\": true, ");
        assertEachBreaks("restore-units", List.of(
                breaks("org-units", "u-2", "versions", null, at("/orgUnits/1", "{\"versions\": []}")),
                breaks("org-unit-versions", "v-4", "validFrom", null,
                        at("/orgUnits/1/versions/0", "{\"validTo\": \"2021-04-01\"}"),
                        at("/orgUnits/1/versions", "{\"id\": \"v-4\", \"validFrom\": \"2022-04-01\", " + NAMES
                                + ", \"attributes\": {}}")),
                breaks("org-unit-parents", "l-2", "validTo", null, at("/orgUnits/2/parents", link("u-1"))),
                breaks("org-unit-parents", "l-1", "validFrom", "org_unit.parent_not_valid",
                        at("/orgUnits/1/versions/0", "{\"validFrom\": \"2019-04-01\"}"),
                        at("/orgUnits/1/parents/0", "{\"validFrom\": \"2019-04-01\"}")),
                breaks("org-unit-parents", "l-2", "parentId", "org_unit.cycle", at("/orgUnits/0/parents", link("u-2"))),
                breaks("org-unit-parents", "l-1", "parentId", "org_unit.rollup_has_parent", at("/refItems", region),
                        at("/orgUnits/1/versions/0", "{\"unitTypeId\": \"region\"}")),
                breaks("org-unit-parents", "l-1", "parentId", "org_unit.rollup_has_child", at("/refItems", region),
                        at("/orgUnits/0/versions/0", "{\"unitTypeId\": \"region\"}")),
                breaks("org-unit-parents", "l-1", "parentId", "org_unit.line_below_lateral",
                        at("/refItems", item("team", "UNIT_TYPE", "\"lateral\": true, ")),
                        at("/orgUnits/0/versions/0", "{\"unitTypeId\": \"team\"}")),
                breaks("org-unit-versions", "v-9", "validTo", "org_unit.has_successors",
                        at("/orgUnits/2/versions/0", "{\"validTo\": null}")),
                breaks("org-unit-successions", "s-1", "effectiveDate", "org_unit.has_successors",
                        at("/orgUnits/2/successors/0", "{\"effectiveDate\": \"2022-04-01\"}")),
                breaks("org-unit-successions", "s-1", "effectiveDate", "org_unit.follows_others",
                        at("/orgUnits/2/successors/0", "{\"successorId\": \"u-3\"}"))));
    }

    private static void ok(HttpResponse<String> response) {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
    }

    private static String id(HttpResponse<String> response) throws Exception {
        ok(response);
        return Api.json(response).get("id").asText();
    }

    private static String unit(Api api, String name, String parentId, String unitType) throws Exception {
        return id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name
                + "\"}}, \"parentId\": \"" + parentId + "\", \"validFrom\": \"2020-04-01\""
                + (unitType == null ? "" : ", \"unitType\": \"" + unitType + "\"") + "}"));
    }

    @Test
    void anExportWithMovesTransfersAMergerAndLateralUnitsRestoresAsItWas() throws Exception {
        TestRealm source = TestRealm.create("restore-rules-export");
        Api api = source.api;
        String first = source.legalEntity("第一法人");
        String second = source.legalEntity("第二法人");
        String nurse = source.user("nurse");
        String doctor = source.user("doctor");
        source.refItem("END_REASON", "transfer", "転籍");
        source.refItem("LEAVE_REASON", "parental", "育児休業");
        source.refItem("UNIT_ROLE", "chair", "委員長");
        ok(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"committee\", \"lateral\": true, " + NAMES
                + "}"));
        String ward = unit(api, "病棟", first, null);
        String clinic = unit(api, "外来", first, null);
        String old = unit(api, "旧病棟", first, null);
        String committee = unit(api, "委員会", first, "committee");
        unit(api, "部会", committee, "committee");
        ok(api.postWithVersion("/reorganizations", "{\"effectiveDate\": \"2023-04-01\", \"steps\": [{\"op\": "
                + "\"succeed\", \"id\": \"" + old + "\", \"successorIds\": [\"" + ward + "\"]}]}"));
        JsonNode hired = Api.json(api.post("/staff", "{\"userId\": \"" + nurse + "\", \"orgUnitId\": \"" + ward
                + "\", \"employerId\": \"" + first + "\", \"validFrom\": \"2020-04-01\", "
                + "\"contractEndDate\": \"2030-03-31\"}"));
        String employment = hired.get("employment").get("id").asText();
        String assignment = hired.get("assignment").get("id").asText();
        JsonNode boss = Api.json(api.post("/staff", "{\"userId\": \"" + doctor + "\", \"orgUnitId\": \"" + clinic
                + "\", \"employerId\": \"" + first + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment + "\", \"managerUserId\": \"" + doctor
                + "\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.post("/unit-roles", "{\"employmentId\": \"" + boss.get("employment").get("id").asText()
                + "\", \"orgUnitId\": \"" + committee + "\", \"role\": \"chair\", \"validFrom\": \"2020-04-01\"}"));
        ok(api.postWithVersion("/employments/" + employment + "/leave", "{\"validFrom\": \"2021-04-01\", "
                + "\"validTo\": \"2021-10-01\", \"reason\": \"parental\"}"));
        ok(api.postWithVersion("/assignments/" + assignment + "/move", "{\"orgUnitId\": \"" + clinic
                + "\", \"validFrom\": \"2022-04-01\"}"));
        ok(api.postWithVersion("/employments/" + employment + "/transfer", "{\"employerId\": \"" + second
                + "\", \"validFrom\": \"2024-04-01\", \"endReason\": \"transfer\"}"));
        ObjectNode before = RealmExportIT.export(api);
        assertTrue(before.get("employments").findValuesAsText("continuesEmploymentId").contains(employment),
                before.toString());
        assertTrue(before.get("assignments").findValuesAsText("continuesAssignmentId").contains(assignment),
                before.toString());

        TestRealm target = TestRealm.create("restore-rules-target");
        target.user("nurse");
        target.user("doctor");
        source.remove();
        try {
            ok(target.api.post("/restore", JSON.writeValueAsString(before)));
            assertEquals(before, RealmExportIT.export(target.api));
        } finally {
            target.remove();
        }
    }
}
