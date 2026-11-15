package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgUnitsIT {

    private static Api api;
    private static Api otherRealmApi;

    @BeforeAll
    static void createRealms() throws Exception {
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        String token = admin.tokenManager().getAccessTokenString();
        api = new Api(createRealm(admin), token);
        otherRealmApi = new Api(createRealm(admin), token);
        assertEquals(201, api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"hospital\", \"name\": \"病院\"}").statusCode());
    }

    private static String createRealm(Keycloak admin) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-units-" + UUID.randomUUID());
        realm.setEnabled(true);
        TestRealm.withTimeZone(realm);
        admin.realms().create(realm);
        return realm.getRealm();
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
    void searchesUnitsAndCountsMembers() throws Exception {
        TestRealm realm = TestRealm.withEmployer("org-unit-search");
        Api search = realm.api;
        realm.refItem("UNIT_TYPE", "ward", "病棟");
        String hospital = created(search.post("/org-units", "{\"name\": \"X病院\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String ward = created(search.post("/org-units", "{\"code\": \"W1\", \"name\": \"1病棟\", \"names\": "
                + "{\"en\": \"Ward One\"}, \"unitType\": \"ward\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(search.post("/org-units", "{\"name\": \"50%病棟\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2026-04-01\"}"));
        String on = "&asOf=2026-05-01";

        assertEquals(List.of(ward), Api.json(search.get("/org-units/search?q=ward%20one" + on)).findValuesAsText("id"));
        assertEquals(List.of(hospital), Api.json(search.get("/org-units/search?q=ward%20one" + on))
                .findValuesAsText("parentId"));
        assertEquals(List.of("50%病棟"), Api.json(search.get("/org-units/search?q=50%25" + on))
                .findValuesAsText("name"));
        assertEquals(List.of("1病棟"), Api.json(search.get("/org-units/search?unitType=ward&code=W1" + on))
                .findValuesAsText("name"));
        HttpResponse<String> counted = search.get("/org-units/search?q=%E7%97%85%E6%A3%9F&max=1&count=true" + on);
        assertEquals(1, Api.json(counted).size());
        assertEquals("2", counted.headers().firstValue("X-Total-Count").orElseThrow());

        for (String name : List.of("nurse-a", "nurse-b")) {
            HttpResponse<String> staff = search.post("/staff", "{\"userId\": \"" + realm.user(name)
                    + "\", \"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2026-04-01\"}");
            assertEquals(201, staff.statusCode(), staff.body());
        }
        HttpResponse<String> members = search.get("/org-units/" + hospital
                + "/members?includeDescendants=true&max=1&count=true" + on);
        assertEquals(1, Api.json(members).size());
        assertEquals("2", members.headers().firstValue("X-Total-Count").orElseThrow());
        assertTrue(search.get("/org-units/" + hospital + "/members" + on.replace('&', '?')).headers()
                .firstValue("X-Total-Count").isEmpty());
    }

    @Test
    void keepsGroupsApartFromTheLineOrganization() throws Exception {
        TestRealm realm = TestRealm.create("org-unit-groups");
        Api groups = realm.api;
        realm.refItem("UNIT_TYPE", "department", "部");
        JsonNode type = created(groups.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"committee\", "
                + "\"name\": \"委員会\", \"group\": true}"));
        assertTrue(type.get("group").asBoolean());
        String hospital = created(groups.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        JsonNode committee = created(groups.post("/org-units", "{\"name\": \"医療安全委員会\", \"unitType\": "
                + "\"committee\", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2026-04-01\"}"));
        assertTrue(committee.get("group").asBoolean());
        created(groups.post("/org-units", "{\"name\": \"労働組合\", \"unitType\": \"committee\", "
                + "\"validFrom\": \"2026-04-01\"}"));
        created(groups.post("/org-units", "{\"name\": \"内科\", \"unitType\": \"department\", \"parentId\": \""
                + hospital + "\", \"validFrom\": \"2026-04-01\"}"));
        String on = "asOf=2026-05-01";

        assertEquals(List.of("労働組合", "病院"), Api.json(groups.get("/org-units?" + on)).findValuesAsText("name"));
        assertEquals(List.of("病院"), Api.json(groups.get("/org-units?group=false&" + on)).findValuesAsText("name"));
        assertEquals(List.of("労働組合"), Api.json(groups.get("/org-units?group=true&" + on)).findValuesAsText("name"));
        assertEquals(List.of("内科"), Api.json(groups.get("/org-units/" + hospital + "/children?group=false&" + on))
                .findValuesAsText("name"));
        assertEquals(List.of("医療安全委員会"), Api.json(groups.get("/org-units/" + hospital
                + "/subtree?group=true&" + on)).findValuesAsText("name"));
        assertEquals(List.of("病院", "内科"), Api.json(groups.get("/org-units/" + hospital
                + "/subtree?group=false&" + on)).findValuesAsText("name"));

        assertEquals("org_unit.line_below_group", errorOf(409, groups.post("/org-units", "{\"name\": \"分科会\", "
                + "\"parentId\": \"" + committee.get("id").asText() + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("ref_item.group_in_use", errorOf(409, groups.put("/ref-items/" + type.get("id").asText(),
                "{\"name\": \"委員会\"}")));
    }

    @Test
    void createsARootWithOnlyANameAndADate() throws Exception {
        JsonNode unit = created(api.post("/org-units", "{\"name\": \"ホゲホゲ診療所\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals(36, unit.get("id").asText().length());
        assertEquals("ホゲホゲ診療所", unit.get("name").asText());
        assertEquals("2026-04-01", unit.get("validFrom").asText());
        // Keycloak leaves a field with no value out of the response.
        assertFalse(unit.has("validTo"));
        assertFalse(unit.has("parentId"));
        assertFalse(unit.has("unitType"));
        assertEquals(0, unit.get("displayOrder").asInt());
    }

    @Test
    void createsAHospitalWithADepartmentBelowIt() throws Exception {
        JsonNode hospital = created(api.post("/org-units", """
                {"code": "x-hospital", "name": "X病院", "names": {"en": "X Hospital"}, "shortName": "X",
                 "shortNames": {"en": "X"}, "phoneticName": "えっくすびょういん", "unitType": "hospital",
                 "validFrom": "2026-04-01", "displayOrder": 1, "attributes": {"beds": 120},
                 "externalId": "H-1", "source": "hr"}
                """));
        String hospitalId = hospital.get("id").asText();
        JsonNode department = created(api.post("/org-units",
                "{\"name\": \"内科\", \"parentId\": \"" + hospitalId + "\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("hospital", hospital.get("unitType").asText());
        assertEquals("X Hospital", hospital.get("names").get("en").asText());
        assertEquals("えっくすびょういん", hospital.get("phoneticName").asText());
        assertEquals(120, hospital.get("attributes").get("beds").asInt());
        assertEquals("H-1", hospital.get("externalId").asText());
        assertEquals(hospitalId, department.get("parentId").asText());

        assertEquals(hospital, Api.json(api.get("/org-units/" + hospitalId + "?asOf=2026-04-01")));
        assertEquals(department, Api.json(api.get("/org-units/" + department.get("id").asText() + "?asOf=2030-01-01")));
    }

    @Test
    void readsAUnitOnlyFromItsFirstDate() throws Exception {
        String future = LocalDate.now(ZoneOffset.UTC).plusYears(1).toString();
        String id = created(api.post("/org-units", "{\"name\": \"Next year\", \"validFrom\": \"" + future + "\"}"))
                .get("id").asText();

        assertEquals(200, api.get("/org-units/" + id + "?asOf=" + future).statusCode());
        assertEquals("org_unit.not_valid_on", errorOf(404, api.get("/org-units/" + id)));
        assertEquals("org_unit.not_found", errorOf(404, api.get("/org-units/no-such-id")));
        assertEquals("invalid.date", errorOf(400, api.get("/org-units/" + id + "?asOf=2026-13-01")));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String hospitalId = created(api.post("/org-units", "{\"name\": \"Y病院\", \"code\": \"y-hospital\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();

        assertEquals("invalid.required", errorOf(400, api.post("/org-units", "{\"name\": \"x\"}")));
        assertEquals("invalid.name", errorOf(400, api.post("/org-units", "{\"name\": \" \", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, api.post("/org-units",
                "{\"name\": \"x\", \"unitType\": \"nope\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.parent_unknown", errorOf(400, api.post("/org-units",
                "{\"name\": \"x\", \"parentId\": \"nope\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.parent_not_valid", errorOf(409, api.post("/org-units",
                "{\"name\": \"x\", \"parentId\": \"" + hospitalId + "\", \"validFrom\": \"2026-03-31\"}")));
        assertEquals("org_unit.code_taken", errorOf(409, api.post("/org-units",
                "{\"name\": \"x\", \"code\": \"y-hospital\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void renamesAUnitOnAnEffectiveDate() throws Exception {
        String id = created(api.post("/org-units", "{\"name\": \"第一内科\", \"shortName\": \"一内\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();

        HttpResponse<String> response = api.put("/org-units/" + id, "{\"name\": \"総合内科\", \"validFrom\": \"2027-04-01\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode renamed = Api.json(response);
        assertEquals(id, renamed.get("id").asText());
        assertEquals("総合内科", renamed.get("name").asText());
        assertEquals("2027-04-01", renamed.get("validFrom").asText());
        assertFalse(renamed.has("validTo"));
        assertFalse(renamed.has("shortName"));

        JsonNode before = Api.json(api.get("/org-units/" + id + "?asOf=2027-03-31"));
        assertEquals("第一内科", before.get("name").asText());
        assertEquals("一内", before.get("shortName").asText());
        assertEquals("2026-04-01", before.get("validFrom").asText());
        assertEquals("2027-04-01", before.get("validTo").asText());
        assertEquals(renamed, Api.json(api.get("/org-units/" + id + "?asOf=2027-04-01")));
    }

    @Test
    void changesInsideAPastPeriodAndCorrectsAFirstDate() throws Exception {
        String id = created(api.post("/org-units", "{\"name\": \"A\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/org-units/" + id, "{\"name\": \"C\", \"validFrom\": \"2027-04-01\"}").statusCode());

        JsonNode middle = Api.json(api.put("/org-units/" + id, "{\"name\": \"B\", \"validFrom\": \"2026-10-01\"}"));
        assertEquals("2027-04-01", middle.get("validTo").asText());
        JsonNode corrected = Api.json(api.put("/org-units/" + id, "{\"name\": \"B2\", \"validFrom\": \"2026-10-01\"}"));
        assertEquals("2027-04-01", corrected.get("validTo").asText());

        assertEquals("A", Api.json(api.get("/org-units/" + id + "?asOf=2026-09-30")).get("name").asText());
        assertEquals("B2", Api.json(api.get("/org-units/" + id + "?asOf=2026-10-01")).get("name").asText());
        assertEquals("C", Api.json(api.get("/org-units/" + id + "?asOf=2027-04-01")).get("name").asText());
    }

    @Test
    void rejectsABadChange() throws Exception {
        String id = created(api.post("/org-units", "{\"name\": \"A\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        assertEquals("org_unit.not_valid_on", errorOf(404, api.put("/org-units/" + id, "{\"name\": \"x\", \"validFrom\": \"2026-03-31\"}")));
        assertEquals("org_unit.not_found", errorOf(404, api.put("/org-units/nope", "{\"name\": \"x\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/org-units/" + id, "{\"validFrom\": \"2026-10-01\"}")));
        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.put("/org-units/" + id, "{\"name\": \"x\", \"validFrom\": \"2026-10-01\"}")));
        assertEquals("A", Api.json(api.get("/org-units/" + id + "?asOf=2026-10-01")).get("name").asText());
    }

    private static String newUnit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return created(api.post("/org-units", "{\"name\": \"" + name + "\"" + parent + ", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
    }

    private static String move(String parentId, String validFrom) {
        String parent = parentId == null ? "" : "\"parentId\": \"" + parentId + "\", ";
        return "{" + parent + "\"validFrom\": \"" + validFrom + "\"}";
    }

    @Test
    void movesAUnitAndMakesItARoot() throws Exception {
        String a = newUnit("A部", null);
        String b = newUnit("B部", null);
        String unit = newUnit("経理課", a);

        HttpResponse<String> response = api.put("/org-units/" + unit + "/parent", move(b, "2027-04-01"));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(b, Api.json(response).get("parentId").asText());
        assertEquals(a, Api.json(api.get("/org-units/" + unit + "?asOf=2027-03-31")).get("parentId").asText());
        assertEquals(b, Api.json(api.get("/org-units/" + unit + "?asOf=2027-04-01")).get("parentId").asText());

        assertFalse(Api.json(api.put("/org-units/" + unit + "/parent", move(null, "2028-04-01"))).has("parentId"));
        assertEquals(b, Api.json(api.get("/org-units/" + unit + "?asOf=2028-03-31")).get("parentId").asText());
        // A move on the first date of the current parent corrects it.
        assertEquals(a, Api.json(api.put("/org-units/" + unit + "/parent", move(a, "2027-04-01"))).get("parentId").asText());
        assertEquals(a, Api.json(api.get("/org-units/" + unit + "?asOf=2028-03-31")).get("parentId").asText());
        assertFalse(Api.json(api.get("/org-units/" + unit + "?asOf=2028-04-01")).has("parentId"));
    }

    @Test
    void rejectsAMoveBelowItselfAndOtherBadMoves() throws Exception {
        String a = newUnit("A", null);
        String b = newUnit("B", a);
        String c = newUnit("C", b);

        assertEquals("org_unit.cycle", errorOf(409, api.put("/org-units/" + a + "/parent", move(c, "2026-10-01"))));
        assertEquals("org_unit.cycle", errorOf(409, api.put("/org-units/" + a + "/parent", move(a, "2026-10-01"))));
        assertEquals("org_unit.parent_unknown", errorOf(400, api.put("/org-units/" + c + "/parent", move("nope", "2026-10-01"))));
        assertEquals("org_unit.not_valid_on", errorOf(404, api.put("/org-units/" + c + "/parent", move(a, "2026-03-31"))));
        assertEquals("invalid.required", errorOf(400, api.put("/org-units/" + c + "/parent", "{\"parentId\": \"" + a + "\"}")));
        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.put("/org-units/" + c + "/parent", move(null, "2026-10-01"))));
        assertEquals(b, Api.json(api.get("/org-units/" + c + "?asOf=2030-01-01")).get("parentId").asText());
    }

    @Test
    void endsALeafAndKeepsItsHistory() throws Exception {
        String parent = newUnit("親", null);
        String unit = newUnit("廃止予定", parent);

        HttpResponse<String> response = api.put("/org-units/" + unit + "/end", "{\"validTo\": \"2027-04-01\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode last = Api.json(response);
        assertEquals("2027-04-01", last.get("validTo").asText());
        assertEquals(parent, last.get("parentId").asText());
        assertEquals(last, Api.json(api.get("/org-units/" + unit + "?asOf=2027-03-31")));
        assertEquals("org_unit.not_valid_on", errorOf(404, api.get("/org-units/" + unit + "?asOf=2027-04-01")));
        // The end moves, and can go away again.
        assertEquals("2028-04-01", Api.json(api.put("/org-units/" + unit + "/end", "{\"validTo\": \"2028-04-01\"}"))
                .get("validTo").asText());
        HttpResponse<String> reopened = api.delete("/org-units/" + unit + "/end");
        assertEquals(200, reopened.statusCode(), reopened.body());
        assertFalse(Api.json(reopened).has("validTo"));
        assertEquals(200, api.put("/org-units/" + unit + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        // The parent can end once its last child is gone.
        assertEquals("org_unit.has_children", errorOf(409, api.put("/org-units/" + parent + "/end", "{\"validTo\": \"2027-03-31\"}")));
        assertEquals(200, api.put("/org-units/" + parent + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
    }

    @Test
    void rejectsABadEnd() throws Exception {
        String parent = newUnit("P", null);
        newUnit("C1", parent);
        newUnit("C2", parent);
        String renamed = newUnit("R", null);
        assertEquals(200, api.put("/org-units/" + renamed, "{\"name\": \"R2\", \"validFrom\": \"2027-04-01\"}").statusCode());

        HttpResponse<String> response = api.withAcceptLanguage("ja").put("/org-units/" + parent + "/end", "{\"validTo\": \"2027-04-01\"}");
        assertEquals("org_unit.has_children", errorOf(409, response));
        assertEquals("2", Api.json(response).get("params").get("count").asText());
        assertTrue(Api.json(response).get("message").asText().contains("2 件の子の組織単位"));
        assertEquals("org_unit.later_changes_exist", errorOf(409, api.put("/org-units/" + renamed + "/end", "{\"validTo\": \"2027-01-01\"}")));
        assertEquals("org_unit.not_valid_on", errorOf(404, api.put("/org-units/" + renamed + "/end", "{\"validTo\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.put("/org-units/" + renamed + "/end", "{}")));
        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.put("/org-units/" + renamed + "/end", "{\"validTo\": \"2028-04-01\"}")));
    }

    @Test
    void aUnitTypeInUseCannotBeDeleted() throws Exception {
        String typeId = created(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"in-use\", \"name\": \"In use\"}")).get("id").asText();
        created(api.post("/org-units", "{\"name\": \"x\", \"unitType\": \"in-use\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("ref_item.in_use", errorOf(409, api.delete("/ref-items/" + typeId)));
        assertEquals(200, api.put("/ref-items/" + typeId, "{\"name\": \"In use\", \"active\": false}").statusCode());
        assertEquals("org_unit.unit_type_unknown", errorOf(400, api.post("/org-units",
                "{\"name\": \"y\", \"unitType\": \"in-use\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void oneRealmDoesNotSeeTheUnitsOfAnother() throws Exception {
        String body = "{\"name\": \"Z病院\", \"code\": \"z-hospital\", \"validFrom\": \"2026-04-01\"}";
        String id = created(api.post("/org-units", body)).get("id").asText();

        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.get("/org-units/" + id + "?asOf=2026-04-01")));
        assertEquals("org_unit.parent_unknown", errorOf(400, otherRealmApi.post("/org-units",
                "{\"name\": \"x\", \"parentId\": \"" + id + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, otherRealmApi.post("/org-units",
                "{\"name\": \"x\", \"unitType\": \"hospital\", \"validFrom\": \"2026-04-01\"}")));
        created(otherRealmApi.post("/org-units", body));
    }

    @Test
    void eachRealmGetsItsOwnDefaultHierarchy() throws Exception {
        String parentId = created(api.post("/org-units", "{\"name\": \"P\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(api.post("/org-units", "{\"name\": \"C\", \"parentId\": \"" + parentId + "\", \"validFrom\": \"2026-04-01\"}"));

        JsonNode hierarchies = Api.json(api.get("/hierarchies"));
        assertEquals(1, hierarchies.size());
        assertEquals("default", hierarchies.get(0).get("code").asText());
    }

    @Test
    void rejectsARequestWithoutAToken() throws Exception {
        var anonymous = new Api("master", null);
        assertEquals(401, anonymous.post("/org-units", "{}").statusCode());
        assertEquals(401, anonymous.get("/org-units/x").statusCode());
    }

    @Test
    void aUnitCanLoseItsParentOnTheDateItGotIt() throws Exception {
        TestRealm realm = TestRealm.create("org-units-unparent");
        String root = Api.json(realm.api.post("/org-units", "{\"name\": \"Root\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String branch = Api.json(realm.api.post("/org-units", "{\"name\": \"Branch\", \"parentId\": \"" + root
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        assertEquals(1, Api.json(realm.api.get("/org-units/" + branch + "/history")).get("parents").size());

        // The link starts on this date, so it never held and is removed.
        HttpResponse<String> moved = realm.api.put("/org-units/" + branch + "/parent", "{\"validFrom\": \"2026-04-01\"}");

        assertEquals(200, moved.statusCode(), moved.body());
        assertFalse(Api.json(moved).has("parentId"));
        assertEquals(0, Api.json(realm.api.get("/org-units/" + branch + "/history")).get("parents").size());
        assertEquals(2, Api.json(realm.api.get("/org-units?asOf=2026-04-01")).size());
    }

    @Test
    void aUnitHasOneParentInEachHierarchy() throws Exception {
        TestRealm realm = TestRealm.create("org-units-matrix");
        Api matrixApi = realm.api;
        String matrix = created(matrixApi.post("/hierarchies", "{\"code\": \"matrix\", \"name\": \"Matrix\"}")).get("id").asText();
        String line = created(matrixApi.post("/org-units", "{\"name\": \"Line\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String project = created(matrixApi.post("/org-units", "{\"name\": \"Project\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String team = created(matrixApi.post("/org-units", "{\"name\": \"Team\", \"parentId\": \"" + line
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        HttpResponse<String> moved = matrixApi.put("/org-units/" + team + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + project + "\", \"validFrom\": \"2026-10-01\"}");

        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(project, Api.json(moved).get("parentId").asText());
        assertEquals(line, Api.json(matrixApi.get("/org-units/" + team + "?asOf=2026-10-01")).get("parentId").asText());
        JsonNode parents = Api.json(matrixApi.get("/org-units/" + team + "/history")).get("parents");
        assertEquals(List.of("default", "matrix"), parents.findValuesAsText("hierarchy"));
        assertEquals(List.of(line, project), parents.findValuesAsText("parentId"));

        // The project is the parent of the line in the matrix, though the team is below the line by default.
        assertEquals(200, matrixApi.put("/org-units/" + line + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + project + "\", \"validFrom\": \"2026-10-01\"}").statusCode());
        HttpResponse<String> cycle = matrixApi.put("/org-units/" + project + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + team + "\", \"validFrom\": \"2026-10-01\"}");
        assertEquals(409, cycle.statusCode(), cycle.body());
        assertEquals("org_unit.cycle", Api.json(cycle).get("error").asText());

        HttpResponse<String> unknown = matrixApi.put("/org-units/" + team + "/parent?hierarchy=nope",
                "{\"validFrom\": \"2026-10-01\"}");
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("hierarchy.unknown", Api.json(unknown).get("error").asText());

        // A hierarchy with links stays. A child in the matrix keeps the project from ending.
        assertEquals(409, matrixApi.delete("/hierarchies/" + matrix).statusCode());
        assertEquals("org_unit.has_children", Api.json(matrixApi.put("/org-units/" + project + "/end",
                "{\"validTo\": \"2027-04-01\"}")).get("error").asText());
        assertEquals(200, matrixApi.put("/org-units/" + team + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        assertEquals(List.of("2027-04-01", "2027-04-01"),
                Api.json(matrixApi.get("/org-units/" + team + "/history")).get("parents").findValuesAsText("validTo"));
    }

    private static List<String> namesOf(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response).findValuesAsText("name");
    }

    @Test
    void readsTheTreeAndTheMembersOfASecondHierarchy() throws Exception {
        TestRealm realm = TestRealm.create("org-units-matrix-reads");
        Api matrixApi = realm.api;
        created(matrixApi.post("/hierarchies", "{\"code\": \"matrix\", \"name\": \"Matrix\"}"));
        // The line is the company, so that the realm can store the employment below.
        created(matrixApi.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"company\", \"name\": \"Company\", \"legalEntity\": true}"));
        String line = created(matrixApi.post("/org-units",
                "{\"name\": \"Line\", \"unitType\": \"company\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String project = created(matrixApi.post("/org-units", "{\"name\": \"Project\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(matrixApi.post("/org-units", "{\"name\": \"Alone\", \"validFrom\": \"2026-04-01\"}"));
        String team = created(matrixApi.post("/org-units", "{\"name\": \"Team\", \"parentId\": \"" + line
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(matrixApi.post("/org-units", "{\"name\": \"Squad\", \"parentId\": \"" + team
                + "\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals(200, matrixApi.put("/org-units/" + team + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + project + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        String member = realm.user("member");
        created(matrixApi.post("/staff", "{\"userId\": \"" + member + "\", \"orgUnitId\": \"" + team
                + "\", \"validFrom\": \"2026-04-01\"}"));
        String on = "asOf=2026-04-01";

        // Only the units with a parent or a child in the matrix are in it.
        assertEquals(List.of("Project"), namesOf(matrixApi.get("/org-units?hierarchy=matrix&" + on)));
        assertEquals(List.of("Project", "Team"), matrixApi.tree("hierarchy=matrix&" + on).findValuesAsText("name"));
        assertEquals(List.of("Team"), namesOf(matrixApi.get("/org-units/" + project
                + "/subtree?hierarchy=matrix&first=1&max=1&" + on)));
        assertEquals(List.of("Alone", "Line", "Team", "Squad", "Project"), matrixApi.tree(on).findValuesAsText("name"));
        assertEquals(List.of("Alone", "Line", "Project"), namesOf(matrixApi.get("/org-units?hierarchy=default&" + on)));

        assertEquals(List.of("Team"), namesOf(matrixApi.get("/org-units/" + project + "/children?hierarchy=matrix&" + on)));
        assertEquals(List.of(), namesOf(matrixApi.get("/org-units/" + project + "/children?" + on)));
        assertEquals(List.of("Project", "Team"), namesOf(matrixApi.get("/org-units/" + project + "/subtree?hierarchy=matrix&" + on)));
        assertEquals(List.of("Project", "Team"), namesOf(matrixApi.get("/org-units/" + team + "/path?hierarchy=matrix&" + on)));
        assertEquals(List.of("Line", "Team"), namesOf(matrixApi.get("/org-units/" + team + "/path?" + on)));
        assertEquals(project, Api.json(matrixApi.get("/org-units/" + team + "?hierarchy=matrix&" + on)).get("parentId").asText());
        assertEquals(line, Api.json(matrixApi.get("/org-units/" + team + "?" + on)).get("parentId").asText());

        // The member of the team is below the project only in the matrix.
        String members = "/org-units/" + project + "/members?includeDescendants=true&" + on;
        assertEquals(List.of(member), Api.json(matrixApi.get(members + "&hierarchy=matrix")).findValuesAsText("userId"));
        assertEquals(List.of(), Api.json(matrixApi.get(members)).findValuesAsText("userId"));

        for (String path : List.of("/org-units?", "/org-units/" + team + "?", "/org-units/" + team + "/children?",
                "/org-units/" + team + "/subtree?", "/org-units/" + team + "/path?", "/org-units/" + team + "/members?")) {
            HttpResponse<String> unknown = matrixApi.get(path + "hierarchy=nope&" + on);
            assertEquals(400, unknown.statusCode(), path + " " + unknown.body());
            assertEquals("hierarchy.unknown", Api.json(unknown).get("error").asText());
        }
    }

    @Test
    void listsTheLegalEntitiesOfADate() throws Exception {
        TestRealm realm = TestRealm.create("org-units-legal-entity");
        Api entityApi = realm.api;
        JsonNode company = created(entityApi.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"company\", \"name\": \"株式会社\", \"legalEntity\": true}"));
        assertTrue(company.get("legalEntity").asBoolean());
        JsonNode department = created(entityApi.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"department\", \"name\": \"部\"}"));
        assertFalse(department.has("legalEntity"));
        HttpResponse<String> wrongKind = entityApi.post("/ref-items",
                "{\"kind\": \"GRADE\", \"code\": \"g1\", \"name\": \"G1\", \"legalEntity\": true}");
        assertEquals(400, wrongKind.statusCode(), wrongKind.body());
        assertEquals("ref_item.legal_entity_kind", Api.json(wrongKind).get("error").asText());

        String on = "\"validFrom\": \"2026-04-01\"}";
        String holding = created(entityApi.post("/org-units", "{\"name\": \"Holding\", \"unitType\": \"company\", "
                + "\"displayOrder\": 2, " + on)).get("id").asText();
        JsonNode subsidiary = created(entityApi.post("/org-units", "{\"name\": \"Subsidiary\", \"unitType\": \"company\", "
                + "\"displayOrder\": 1, \"parentId\": \"" + holding + "\", " + on));
        String sales = created(entityApi.post("/org-units", "{\"name\": \"Sales\", \"unitType\": \"department\", "
                + "\"parentId\": \"" + holding + "\", " + on)).get("id").asText();
        created(entityApi.post("/org-units", "{\"name\": \"Untyped\", " + on));

        assertTrue(subsidiary.get("legalEntity").asBoolean());
        assertFalse(Api.json(entityApi.get("/org-units/" + sales + "?asOf=2026-04-01")).has("legalEntity"));
        JsonNode entities = Api.json(entityApi.get("/org-units?legalEntity=true&asOf=2026-04-01"));
        assertEquals(List.of("Subsidiary", "Holding"), entities.findValuesAsText("name"));
        assertEquals(holding, entities.get(0).get("parentId").asText());
        assertEquals(List.of("Holding"),
                namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2026-04-01&first=1&max=1")));
        assertEquals(List.of(), namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2026-03-31")));

        // The sales department becomes a company of its own in 2027.
        assertEquals(200, entityApi.put("/org-units/" + sales, "{\"name\": \"Sales Inc.\", \"unitType\": \"company\", "
                + "\"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(List.of("Subsidiary", "Holding"), namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2027-03-31")));
        assertEquals(List.of("Sales Inc.", "Subsidiary", "Holding"),
                namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2027-04-01")));

        // A change that leaves the mark out takes it away.
        String companyId = company.get("id").asText();
        assertFalse(Api.json(entityApi.put("/ref-items/" + companyId, "{\"name\": \"株式会社\"}")).has("legalEntity"));
        assertEquals(List.of(), namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2027-04-01")));
    }
}
