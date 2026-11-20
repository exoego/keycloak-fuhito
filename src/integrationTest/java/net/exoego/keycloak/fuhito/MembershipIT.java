package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One hospital with a nursing department and a ward, and a second hospital.
 * A physician works in both hospitals. A nurse goes on leave in October 2026. A clerk leaves and comes back.
 */
class MembershipIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String xHospital;
    private static String nursing;
    private static String ward;
    private static String yHospital;
    private static String physician;
    private static String nurse;
    private static String clerk;
    private static String idle;

    @BeforeAll
    static void createTheOrganization() throws Exception {
        realm = TestRealm.withEmployer("membership");
        otherRealm = TestRealm.withEmployer("membership");
        api = realm.api;
        realm.refItem("OCCUPATION", "physician", "医師");
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("JOB_TITLE", "director", "部長");
        realm.refItem("EMPLOYMENT_TYPE", "part-time", "非常勤");
        xHospital = unit("X病院", null);
        nursing = unit("看護部", xHospital);
        ward = unit("1病棟", nursing);
        yHospital = unit("Y病院", null);
        physician = realm.user("physician");
        nurse = realm.user("nurse");
        clerk = realm.user("clerk");
        idle = realm.user("idle");

        String physicianEmployment = staff(physician, xHospital, ", \"staffNumber\": \"D-1\", \"occupation\": \"physician\", \"jobTitle\": \"director\", \"head\": true");
        post("/assignments", "{\"employmentId\": \"" + physicianEmployment + "\", \"orgUnitId\": \"" + yHospital
                + "\", \"validFrom\": \"2026-04-01\", \"occupation\": \"physician\", \"allocationPercent\": 20}");
        String nurseEmployment = staff(nurse, ward, ", \"occupation\": \"nurse\", \"employmentType\": \"part-time\", \"workPercent\": 50");
        assertEquals(200, api.putWithVersion("/employments/" + nurseEmployment + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"employmentType\": \"part-time\", \"status\": \"ON_LEAVE\"}").statusCode());
        String clerkEmployment = staff(clerk, nursing, "");
        assertEquals(200, api.putWithVersion("/employments/" + clerkEmployment + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        post("/staff", "{\"userId\": \"" + clerk + "\", \"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2028-04-01\"}");
    }

    private static JsonNode post(String path, String body) throws Exception {
        HttpResponse<String> response = api.post(path, body);
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String unit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}" + parent + ", \"validFrom\": \"2026-04-01\"}").get("id").asText();
    }

    private static String staff(String userId, String orgUnitId, String more) throws Exception {
        return post("/staff", "{\"userId\": \"" + userId + "\", \"orgUnitId\": \"" + orgUnitId
                + "\", \"validFrom\": \"2026-04-01\"" + more + "}").get("employment").get("id").asText();
    }

    private static List<String> users(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("userId");
    }

    @Test
    void listsTheMembersOfAUnitOnTwoDates() throws Exception {
        assertEquals(List.of(clerk), users(api.get("/org-units/" + nursing + "/members?asOf=2026-04-01")));
        assertEquals(List.of(), users(api.get("/org-units/" + nursing + "/members?asOf=2027-04-01")));
        assertEquals(List.of(nurse), users(api.get("/org-units/" + ward + "/members?asOf=2027-04-01")));
        assertEquals(List.of(nurse, clerk), users(api.get("/org-units/" + ward + "/members?asOf=2028-04-01")));
    }

    @Test
    void listsDescendantsInTreeOrderWithEveryField() throws Exception {
        HttpResponse<String> response = api.get("/org-units/" + xHospital + "/members?includeDescendants=true&asOf=2026-04-01");

        assertEquals(List.of(physician, clerk, nurse), users(response));
        JsonNode head = Api.json(response).get(0);
        assertEquals(xHospital, head.get("orgUnitId").asText());
        assertEquals("X病院", head.get("orgUnitName").asText());
        assertEquals("D-1", head.get("staffNumber").asText());
        assertEquals("physician", head.get("occupation").asText());
        assertEquals("director", head.get("jobTitle").asText());
        assertTrue(head.get("head").asBoolean());
        assertTrue(head.get("primary").asBoolean());
        assertEquals("ACTIVE", head.get("status").asText());
        assertEquals("2026-04-01", head.get("validFrom").asText());
        assertFalse(head.has("validTo"));
        assertFalse(head.has("orgUnitPath"));
        JsonNode partTime = Api.json(response).get(2);
        assertEquals("part-time", partTime.get("employmentType").asText());
        assertEquals("50", partTime.get("workPercent").asText());
        assertEquals("1病棟", partTime.get("orgUnitName").asText());
    }

    @Test
    void filtersAndPagesTheMembers() throws Exception {
        String all = "/org-units/" + xHospital + "/members?includeDescendants=true";

        assertEquals(List.of(nurse), users(api.get(all + "&asOf=2026-04-01&occupation=nurse")));
        assertEquals(List.of(physician), users(api.get(all + "&asOf=2026-04-01&jobTitle=director")));
        assertEquals(List.of(physician, clerk), users(api.get(all + "&asOf=2026-10-01&status=ACTIVE")));
        assertEquals(List.of(nurse), users(api.get(all + "&asOf=2026-10-01&status=ON_LEAVE")));
        assertEquals(List.of(physician, clerk, nurse), users(api.get(all + "&asOf=2026-10-01")));
        assertEquals(List.of(clerk), users(api.get(all + "&asOf=2026-04-01&first=1&max=1")));
        assertEquals(List.of(nurse), users(api.get(all + "&asOf=2026-10-01&first=2&max=5")));
        assertEquals(List.of(), users(api.get(all + "&asOf=2026-10-01&status=ON_LEAVE&occupation=physician")));
        // X病院 belongs to no legal entity, so no member there is seconded.
        assertEquals(List.of(), users(api.get(all + "&asOf=2026-04-01&occupation=nurse&seconded=true")));
        assertEquals(List.of(physician), users(api.get(all + "&asOf=2026-04-01&jobTitle=director&seconded=false")));
        String direct = "/org-units/" + xHospital + "/members?asOf=2026-04-01";
        assertEquals(List.of(physician), users(api.get(direct + "&occupation=physician&jobTitle=director&status=ACTIVE")));
        assertEquals(List.of(), users(api.get(direct + "&occupation=nurse")));
        assertEquals(400, api.get(all + "&occupation=director").statusCode());
        assertEquals(400, api.get(all + "&status=GONE").statusCode());
        assertEquals(404, api.get("/org-units/" + xHospital + "/members?asOf=2026-03-31").statusCode());
        assertEquals(404, api.get("/org-units/nope/members").statusCode());
        assertEquals(404, otherRealm.api.get("/org-units/" + xHospital + "/members?asOf=2026-04-01").statusCode());
    }

    @Test
    void listsTheAssignmentsOfAUserInTwoHospitals() throws Exception {
        HttpResponse<String> response = api.get("/users/" + physician + "/assignments?asOf=2026-04-01&includePath=true");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode assignments = Api.json(response);
        assertEquals(2, assignments.size());
        assertEquals(xHospital, assignments.get(0).get("orgUnitId").asText());
        assertTrue(assignments.get(0).get("primary").asBoolean());
        assertEquals(yHospital, assignments.get(1).get("orgUnitId").asText());
        assertEquals("20", assignments.get(1).get("allocationPercent").asText());
        assertEquals(List.of("Y病院"), assignments.get(1).get("orgUnitPath").findValuesAsText("name"));

        JsonNode ofNurse = Api.json(api.get("/users/" + nurse + "/assignments?asOf=2026-10-01&includePath=true"));
        assertEquals(List.of("X病院", "看護部", "1病棟"), ofNurse.get(0).get("orgUnitPath").findValuesAsText("name"));
        assertEquals("ON_LEAVE", ofNurse.get(0).get("status").asText());
        assertEquals(0, Api.json(api.get("/users/" + clerk + "/assignments?asOf=2027-04-01")).size());
        assertEquals(0, Api.json(api.get("/users/" + idle + "/assignments")).size());
    }

    @Test
    void readsTheHistoryOfAUserWhoLeftAndCameBack() throws Exception {
        HttpResponse<String> response = api.get("/users/" + clerk + "/history");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode history = Api.json(response);
        assertEquals(clerk, history.get("userId").asText());
        assertEquals(2, history.get("employments").size());
        assertEquals("2027-04-01", history.get("employments").get(0).get("validTo").asText());
        assertEquals("2028-04-01", history.get("employments").get(1).get("validFrom").asText());
        assertEquals(List.of(nursing, ward), history.get("assignments").findValuesAsText("orgUnitId"));
        assertEquals("2027-04-01", history.get("assignments").get(0).get("validTo").asText());
        assertEquals(0, Api.json(api.get("/users/" + idle + "/history")).get("employments").size());
    }

    @Test
    void rejectsAnUnknownUserAndAMissingToken() throws Exception {
        for (String sub : List.of("assignments", "history")) {
            HttpResponse<String> unknown = api.get("/users/no-such-user/" + sub);
            assertEquals(404, unknown.statusCode());
            assertEquals("user.not_found", Api.json(unknown).get("error").asText());
            assertEquals(404, otherRealm.api.get("/users/" + physician + "/" + sub).statusCode());
            assertEquals(401, new Api(realm.name, null).get("/users/" + physician + "/" + sub).statusCode());
        }
        assertEquals(400, api.get("/users/" + physician + "/assignments?asOf=nope").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/org-units/" + ward + "/members").statusCode());
    }

    @Test
    void theLinkHeaderLeadsThroughEveryMember() throws Exception {
        TestRealm scratch = TestRealm.withEmployer("membership-cursor");
        Api scratchApi = scratch.api;
        Map<String, String> units = new LinkedHashMap<>();
        for (String[] unit : new String[][] {{"病院", null, "0"}, {"B病棟", "病院", "2"}, {"A病棟", "病院", "1"}}) {
            String parent = unit[1] == null ? "" : ", \"parentId\": \"" + units.get(unit[1]) + "\"";
            HttpResponse<String> response = scratchApi.post("/org-units", "{\"primaryLanguage\": \"ja\", "
                    + "\"names\": {\"ja\": {\"name\": \"" + unit[0] + "\"}}, \"displayOrder\": " + unit[2] + parent + ", \"validFrom\": \"2026-04-01\"}");
            assertEquals(201, response.statusCode(), response.body());
            units.put(unit[0], Api.json(response).get("id").asText());
        }
        String hospital = units.get("病院");
        String wardA = units.get("A病棟");
        // Seven people. Two share a display order, so the cursor must tell them apart by the assignment.
        int n = 0;
        for (String[] place : new String[][] {{"病院", "1"}, {"A病棟", "2"}, {"A病棟", "1"}, {"A病棟", "1"}, {"B病棟", "3"},
                {"B病棟", "1"}, {"B病棟", "2"}}) {
            HttpResponse<String> employment = scratchApi.post("/employments", "{\"userId\": \""
                    + scratch.user("member-" + n++) + "\", \"validFrom\": \"2026-04-01\"}");
            assertEquals(201, employment.statusCode(), employment.body());
            HttpResponse<String> response = scratchApi.post("/assignments", "{\"employmentId\": \""
                    + Api.json(employment).get("id").asText() + "\", \"orgUnitId\": \"" + units.get(place[0])
                    + "\", \"displayOrder\": " + place[1] + ", \"primary\": true, \"validFrom\": \"2026-04-01\"}");
            assertEquals(201, response.statusCode(), response.body());
        }
        String prefix = "/admin/realms/" + scratch.name + "/fuhito";

        for (String list : List.of("/org-units/" + hospital + "/members?includeDescendants=true&asOf=2026-04-01",
                "/org-units/" + wardA + "/members?asOf=2026-04-01")) {
            List<String> all = Api.json(scratchApi.get(list)).findValuesAsText("assignmentId");
            assertEquals(all, followed(scratch, list + "&max=2"), list);
        }
        assertEquals(7, Api.json(scratchApi.get("/org-units/" + hospital
                + "/members?includeDescendants=true&asOf=2026-04-01")).size());

        HttpResponse<String> bad = scratchApi.get("/org-units/" + hospital + "/members?after=people.v1__x");
        assertEquals(400, bad.statusCode(), bad.body());
        assertEquals("invalid.cursor", Api.json(bad).get("error").asText());

        // The ward of the cursor is no longer under the hospital on the date of the next page.
        String link = scratchApi.get("/org-units/" + hospital + "/members?includeDescendants=true&max=2&asOf=2026-04-01")
                .headers().firstValue("Link").orElseThrow();
        String next = link.substring(link.indexOf(prefix) + prefix.length(), link.indexOf('>'));
        assertTrue(next.contains("after=members.v1__" + wardA), next);
        assertEquals(200, scratchApi.putWithVersion("/org-units/" + wardA + "/parent",
                "{\"parentId\": null, \"validFrom\": \"2026-06-01\"}").statusCode());
        HttpResponse<String> gone = scratchApi.get(next.replace("asOf=2026-04-01", "asOf=2026-06-01"));
        assertEquals(400, gone.statusCode(), gone.body());
        assertEquals("invalid.cursor_unit", Api.json(gone).get("error").asText());
    }

    // The assignments of every page, from the first page to the last by the Link header.
    private static List<String> followed(TestRealm realm, String list) throws Exception {
        String prefix = "/admin/realms/" + realm.name + "/fuhito";
        List<String> followed = new ArrayList<>();
        for (String path = list; path != null;) {
            HttpResponse<String> response = realm.api.get(path);
            assertEquals(200, response.statusCode(), response.body());
            followed.addAll(Api.json(response).findValuesAsText("assignmentId"));
            String link = response.headers().firstValue("Link").orElse(null);
            path = link == null ? null : link.substring(link.indexOf(prefix) + prefix.length(), link.indexOf('>'));
        }
        return followed;
    }

    @Test
    void anAssignmentIsASecondmentWhenItsUnitBelongsToAnotherLegalEntity() throws Exception {
        TestRealm scratch = TestRealm.create("membership-secondment");
        Api s = scratch.api;
        for (String marks : List.of("company\", \"legalEntity\": true", "branch\"", "lateral\", \"lateral\": true",
                "region\", \"rollup\": true")) {
            created(s.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"" + marks
                    + ", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"種別\"}}}"));
        }
        // A holding company above company A, and company B beside them, below a group that is no legal entity.
        String group = typedUnit(s, "グループ", null, null, 0);
        String holding = typedUnit(s, "持株会社H", "company", group, 1);
        String companyA = typedUnit(s, "会社A", "company", holding, 0);
        String branchA = typedUnit(s, "A支店", "branch", companyA, 0);
        String companyB = typedUnit(s, "会社B", "company", group, 2);
        String branchB = typedUnit(s, "B支店", "branch", companyB, 0);
        String union = typedUnit(s, "労働組合", "lateral", null, 0);
        String kanto = typedUnit(s, "関東", "region", null, 0);
        // A committee is a lateral unit below a hospital of a medical corporation.
        String corporation = typedUnit(s, "医療法人M", "company", null, 0);
        String committee = typedUnit(s, "感染対策委員会", "lateral", typedUnit(s, "M病院", null, corporation, 0), 0);
        assertEquals(201, s.post("/hierarchies", "{\"code\": \"region\", \"name\": \"地域\"}").statusCode());
        for (String branch : List.of(branchA, branchB)) {
            assertEquals(200, s.putWithVersion("/org-units/" + branch + "/parent?hierarchy=region",
                    "{\"parentId\": \"" + kanto + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        }
        String staffA = scratch.user("staff-a");
        String staffH = scratch.user("staff-h");
        String staffM = scratch.user("staff-m");
        String ofA = employ(s, staffA, companyA);
        String inGroup = assign(s, ofA, group, true, 0);
        String inBranchB = assign(s, ofA, branchB, false, 0);
        String inBranchA = assign(s, ofA, branchA, false, 1);
        String holdingInBranchA = assign(s, employ(s, staffH, holding), branchA, true, 2);
        String inUnion = assign(s, ofA, union, false, 0);
        String inKanto = assign(s, ofA, kanto, false, 0);
        assign(s, employ(s, staffM, corporation), committee, true, 1);
        assign(s, ofA, committee, false, 2);
        String on = "asOf=2026-04-01";

        // A rollup unit, a lateral root, and a unit above every legal entity belong to no legal entity.
        assertEquals(Map.of(group, false, branchB, true, branchA, false, union, false, kanto, false, committee, true),
                secondedByUnit(s, staffA, on));
        // Company A is a legal entity below the holding company, so its branch belongs to company A.
        assertEquals(Map.of(branchA, true), secondedByUnit(s, staffH, on));
        // The committee belongs to the medical corporation, like the hospital above it.
        JsonNode inCommittee = Api.json(s.get("/org-units/" + committee + "/members?" + on));
        assertEquals(List.of(staffM, staffA), inCommittee.findValuesAsText("userId"));
        assertEquals(List.of("false", "true"), inCommittee.findValuesAsText("seconded"));

        // Both sides of the filter, on every page.
        String below = "/org-units/" + group + "/members?includeDescendants=true&" + on;
        assertEquals(List.of(holdingInBranchA, inBranchB), followed(scratch, below + "&seconded=true&max=1"));
        assertEquals(List.of(inGroup, inBranchA), followed(scratch, below + "&seconded=false&max=1"));
        assertEquals(List.of(inBranchB), Api.json(s.get(below + "&seconded=true&first=1&max=1"))
                .findValuesAsText("assignmentId"));
        String count = "/org-units/" + group + "/members/count?includeDescendants=true&" + on;
        assertEquals(2, s.count(count + "&seconded=true"));
        assertEquals(2, s.count(count + "&seconded=false"));
        String inA = "/org-units/" + branchA + "/members?";
        assertEquals(List.of(holdingInBranchA), followed(scratch, inA + on + "&seconded=true&max=1"));
        assertEquals(List.of(inBranchA), followed(scratch, inA + on + "&seconded=false&max=1"));
        assertEquals(List.of(), followed(scratch, "/org-units/" + union + "/members?seconded=true&" + on));
        assertEquals(List.of(inUnion), followed(scratch, "/org-units/" + union + "/members?seconded=false&" + on));
        // In the hierarchy of the regions, the branches still belong to their companies.
        String region = "/org-units/" + kanto + "/members?includeDescendants=true&hierarchy=region&" + on;
        assertEquals(List.of(holdingInBranchA, inBranchB), followed(scratch, region + "&seconded=true&max=1"));
        assertEquals(List.of(inKanto, inBranchA), followed(scratch, region + "&seconded=false&max=1"));
        assertEquals(2, s.count("/org-units/" + kanto + "/members/count?includeDescendants=true&hierarchy=region&"
                + on + "&seconded=true"));

        // The branch is a company of its own for one year. The unit type of the date decides.
        for (String[] change : new String[][] {{"company", "2027-04-01"}, {"branch", "2028-04-01"}}) {
            assertEquals(200, s.putWithVersion("/org-units/" + branchA, "{\"validFrom\": \"" + change[1]
                    + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"A支店\"}}, \"unitType\": \""
                    + change[0] + "\"}").statusCode());
        }
        List<Boolean> byDate = new ArrayList<>();
        for (String date : List.of("2027-03-31", "2027-04-01", "2028-04-01")) {
            byDate.add(secondedByUnit(s, staffA, "asOf=" + date).get(branchA));
        }
        assertEquals(List.of(false, true, false), byDate);
        assertEquals(List.of(holdingInBranchA), followed(scratch, inA + "asOf=2027-03-31&seconded=true"));
        assertEquals(List.of(inBranchA, holdingInBranchA), followed(scratch, inA + "asOf=2027-04-01&seconded=true"));
        assertEquals(List.of(holdingInBranchA), followed(scratch, inA + "asOf=2028-04-01&seconded=true"));
        assertEquals(List.of(inBranchA, holdingInBranchA, inBranchB), followed(scratch,
                "/org-units/" + group + "/members?includeDescendants=true&asOf=2027-04-01&seconded=true&max=2"));
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String typedUnit(Api api, String name, String unitType, String parentId, int displayOrder)
            throws Exception {
        return created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \""
                + name + "\"}}" + (unitType == null ? "" : ", \"unitType\": \"" + unitType + "\"")
                + (parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"") + ", \"displayOrder\": "
                + displayOrder + ", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
    }

    private static String employ(Api api, String userId, String employerId) throws Exception {
        return created(api.post("/employments", "{\"userId\": \"" + userId + "\", \"employerId\": \"" + employerId
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
    }

    private static String assign(Api api, String employmentId, String orgUnitId, boolean primary, int displayOrder)
            throws Exception {
        return created(api.post("/assignments", "{\"employmentId\": \"" + employmentId + "\", \"orgUnitId\": \""
                + orgUnitId + "\", \"primary\": " + primary + ", \"displayOrder\": " + displayOrder
                + ", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
    }

    private static Map<String, Boolean> secondedByUnit(Api api, String userId, String query) throws Exception {
        Map<String, Boolean> byUnit = new LinkedHashMap<>();
        for (JsonNode assignment : Api.json(api.get("/users/" + userId + "/assignments?" + query))) {
            byUnit.put(assignment.get("orgUnitId").asText(), assignment.get("seconded").asBoolean());
        }
        return byUnit;
    }
}
