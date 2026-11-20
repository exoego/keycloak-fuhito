package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
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
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"hospital\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}}").statusCode());
    }

    private static String createRealm(Keycloak admin) {
        var realm = new RealmRepresentation();
        realm.setRealm("org-units-" + UUID.randomUUID());
        realm.setEnabled(true);
        TestRealm.withTimeZone(realm);
        TestRealm.createRealm(admin, realm);
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
    void filtersTheListOfUnitsAndCountsMembers() throws Exception {
        TestRealm realm = TestRealm.withEmployer("org-unit-search");
        Api search = realm.api;
        realm.refItem("UNIT_TYPE", "ward", "病棟");
        String hospital = created(search.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X病院\"}}, \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String ward = created(search.post("/org-units", "{\"code\": \"W1\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"1病棟\"}, \"en\": {\"name\": \"Ward One\", \"shortName\": \"W-1\", "
                + "\"phoneticName\": \"wardo wan\"}}, \"unitType\": \"ward\", \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(search.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"50%病棟\"}}, \"parentId\": \"" + hospital
                + "\", \"validFrom\": \"2026-04-01\"}"));
        String on = "&asOf=2026-05-01";

        // With no filter, the list has every unit of the date at any depth.
        assertEquals(List.of("1病棟", "50%病棟", "X病院", "法人"), Api.names(Api.json(search.get("/org-units?" + on))));
        assertEquals(List.of("X病院", "法人"), Api.names(Api.json(search.get("/org-units?roots=true" + on))));
        assertEquals(List.of(ward), Api.json(search.get("/org-units?q=ward%20one" + on)).findValuesAsText("id"));
        // A search matches the short name and the phonetic name of any language too.
        assertEquals(List.of(ward), Api.json(search.get("/org-units?q=w-1" + on)).findValuesAsText("id"));
        assertEquals(List.of(ward), Api.json(search.get("/org-units?q=WARDO" + on)).findValuesAsText("id"));
        assertEquals(List.of(hospital), Api.json(search.get("/org-units?q=ward%20one" + on))
                .findValuesAsText("parentId"));
        assertEquals(List.of("50%病棟"), Api.names(Api.json(search.get("/org-units?q=50%25" + on))));
        // The search reads the names, not the language tags.
        for (String structure : List.of("en", "%22", "%7B", "%3A")) {
            assertEquals(List.of(), Api.json(search.get("/org-units?q=" + structure + on)).findValuesAsText("id"),
                    structure);
        }
        assertEquals(List.of("1病棟"), Api.names(Api.json(search.get("/org-units?unitType=ward&code=W1" + on))));
        assertEquals(1, Api.json(search.get("/org-units?q=%E7%97%85%E6%A3%9F&max=1" + on)).size());
        assertEquals(2, search.count("/org-units/count?q=%E7%97%85%E6%A3%9F" + on));
        // The filters apply together, also to the count.
        assertEquals(List.of("X病院"), Api.names(Api.json(search.get("/org-units?roots=true&legalEntity=false" + on))));
        assertEquals(List.of("法人"), Api.names(Api.json(search.get("/org-units?roots=true&legalEntity=true&lateral=false" + on))));
        assertEquals(0, Api.json(search.get("/org-units?roots=true&q=%E7%97%85%E6%A3%9F&unitType=ward" + on)).size());
        assertEquals(3, search.count("/org-units/count?legalEntity=false" + on));
        assertEquals(1, search.count("/org-units/count?roots=true&legalEntity=false" + on));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, search.get("/org-units?unitType=room" + on)));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, search.get("/org-units/count?unitType=room" + on)));

        for (String name : List.of("nurse-a", "nurse-b")) {
            HttpResponse<String> staff = search.post("/staff", "{\"userId\": \"" + realm.user(name)
                    + "\", \"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2026-04-01\"}");
            assertEquals(201, staff.statusCode(), staff.body());
        }
        HttpResponse<String> members = search.get("/org-units/" + hospital
                + "/members?includeDescendants=true&max=1&count=true" + on);
        assertEquals(1, Api.json(members).size());
        // The list no longer counts. The count is a read of its own.
        assertTrue(members.headers().firstValue("X-Total-Count").isEmpty());
        assertEquals(2, search.count("/org-units/" + hospital + "/members/count?includeDescendants=true" + on));
        assertEquals(0, search.count("/org-units/" + hospital + "/members/count" + on.replace('&', '?')));
        assertEquals("ref_item.unknown", errorOf(400, search.get("/org-units/" + hospital
                + "/members/count?jobTitle=none" + on)));
        assertEquals("org_unit.not_found", errorOf(404, search.get("/org-units/none/members/count")));
    }

    @Test
    void keepsLateralUnitsApartFromTheLineOrganization() throws Exception {
        TestRealm realm = TestRealm.create("org-unit-lateral");
        Api units = realm.api;
        realm.refItem("UNIT_TYPE", "department", "部");
        JsonNode type = created(units.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"committee\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"委員会\"}}, \"lateral\": true}"));
        assertTrue(type.get("lateral").asBoolean());
        String hospital = created(units.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        JsonNode committee = created(units.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"医療安全委員会\"}}, \"unitType\": "
                + "\"committee\", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2026-04-01\"}"));
        assertTrue(committee.get("lateral").asBoolean());
        created(units.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"労働組合\"}}, \"unitType\": \"committee\", "
                + "\"validFrom\": \"2026-04-01\"}"));
        created(units.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"内科\"}}, \"unitType\": \"department\", \"parentId\": \""
                + hospital + "\", \"validFrom\": \"2026-04-01\"}"));
        String on = "asOf=2026-05-01";

        assertEquals(List.of("労働組合", "病院"), Api.names(Api.json(units.get("/org-units?roots=true&" + on))));
        assertEquals(List.of("病院"), Api.names(Api.json(units.get("/org-units?roots=true&lateral=false&" + on))));
        assertEquals(List.of("労働組合"), Api.names(Api.json(units.get("/org-units?roots=true&lateral=true&" + on))));
        assertEquals(List.of("内科"), Api.names(Api.json(units.get("/org-units/" + hospital + "/children?lateral=false&" + on))));
        assertEquals(List.of("医療安全委員会"), Api.names(Api.json(units.get("/org-units/" + hospital
                + "/subtree?lateral=true&" + on))));
        assertEquals(List.of("病院", "内科"), Api.names(Api.json(units.get("/org-units/" + hospital
                + "/subtree?lateral=false&" + on))));
        assertEquals(1, units.count("/org-units/" + hospital + "/children/count?lateral=false&" + on));
        assertEquals(2, units.count("/org-units/" + hospital + "/children/count?" + on));
        assertEquals(1, units.count("/org-units/" + hospital + "/subtree/count?lateral=true&" + on));
        assertEquals(2, units.count("/org-units/" + hospital + "/subtree/count?lateral=false&" + on));
        assertEquals(3, units.count("/org-units/" + hospital + "/subtree/count?" + on));
        assertEquals("org_unit.not_valid_on", errorOf(404, units.get("/org-units/" + hospital
                + "/children/count?asOf=2026-03-31")));
        assertEquals("org_unit.not_valid_on", errorOf(404, units.get("/org-units/" + hospital
                + "/subtree/count?asOf=2026-03-31")));
        assertEquals(List.of("内科"), Api.names(Api.json(units.get("/org-units?unitType=department&lateral=false&" + on))));
        assertEquals(List.of(), Api.names(Api.json(units.get("/org-units?unitType=department&lateral=true&" + on))));
        assertEquals(List.of("内科", "病院"), Api.names(Api.json(units.get("/org-units?lateral=false&" + on))));

        assertEquals("org_unit.line_below_lateral", errorOf(409, units.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"分科会\"}}, "
                + "\"parentId\": \"" + committee.get("id").asText() + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("ref_item.lateral_in_use", errorOf(409, units.putWithVersion("/ref-items/" + type.get("id").asText(),
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"委員会\"}}}")));
    }

    @Test
    void createsARootWithOnlyANameAndADate() throws Exception {
        JsonNode unit = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"ホゲホゲ診療所\"}}, \"validFrom\": \"2026-04-01\"}"));

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
        created(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", \"type\": \"NUMBER\"}"));
        JsonNode hospital = created(api.post("/org-units", """
                {"code": "x-hospital", "primaryLanguage": "ja",
                 "names": {"ja": {"name": "X病院", "shortName": "X", "phoneticName": "えっくすびょういん"},
                           "en": {"name": "X Hospital", "shortName": "X"}}, "unitType": "hospital",
                 "validFrom": "2026-04-01", "displayOrder": 1, "attributes": {"beds": 120},
                 "externalId": "H-1", "source": "hr"}
                """));
        String hospitalId = hospital.get("id").asText();
        JsonNode department = created(api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"内科\"}}, \"parentId\": \"" + hospitalId + "\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("hospital", hospital.get("unitType").asText());
        assertEquals("X病院", hospital.get("name").asText());
        assertEquals("ja", hospital.get("primaryLanguage").asText());
        assertEquals("X Hospital", hospital.get("names").get("en").get("name").asText());
        assertEquals("えっくすびょういん", hospital.get("phoneticName").asText());
        assertEquals(120, hospital.get("attributes").get("beds").asInt());
        assertEquals(List.of("hr=H-1"), Api.externalIds(hospital));
        assertEquals(hospitalId, department.get("parentId").asText());

        assertEquals(hospital, Api.json(api.get("/org-units/" + hospitalId + "?asOf=2026-04-01")));
        assertEquals(department, Api.json(api.get("/org-units/" + department.get("id").asText() + "?asOf=2030-01-01")));
    }

    @Test
    void readsAUnitOnlyFromItsFirstDate() throws Exception {
        String future = LocalDate.now(ZoneOffset.UTC).plusYears(1).toString();
        String id = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Next year\"}}, \"validFrom\": \"" + future + "\"}"))
                .get("id").asText();

        assertEquals(200, api.get("/org-units/" + id + "?asOf=" + future).statusCode());
        assertEquals("org_unit.not_valid_on", errorOf(404, api.get("/org-units/" + id)));
        assertEquals("org_unit.not_found", errorOf(404, api.get("/org-units/no-such-id")));
        assertEquals("invalid.date", errorOf(400, api.get("/org-units/" + id + "?asOf=2026-13-01")));
    }

    @Test
    void rejectsBadRequests() throws Exception {
        String hospitalId = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Y病院\"}}, \"code\": \"y-hospital\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();

        assertEquals("invalid.required", errorOf(400, api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}}")));
        assertEquals("invalid.blank", errorOf(400, api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \" \"}}, \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"unitType\": \"nope\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.parent_unknown", errorOf(400, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"parentId\": \"nope\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.parent_not_valid", errorOf(409, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"parentId\": \"" + hospitalId + "\", \"validFrom\": \"2026-03-31\"}")));
        assertEquals("org_unit.code_taken", errorOf(409, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"code\": \"y-hospital\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void anEmptyTextFailsInEveryFieldOfAWrite() throws Exception {
        String unit = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"%s}}, \"validFrom\": \"2026-04-01\"%s}";
        String[][] cases = {
            {", \"shortName\": \"\"", "", "names.ja.shortName"},
            {", \"phoneticName\": \" \"", "", "names.ja.phoneticName"},
            {"", ", \"code\": \"\"", "code"},
            {"", ", \"unitType\": \" \"", "unitType"},
            {"", ", \"parentId\": \"\"", "parentId"},
            {"", ", \"externalId\": \"\", \"source\": \"hr\"", "externalId"}};
        for (String[] bad : cases) {
            HttpResponse<String> response = api.post("/org-units", unit.formatted(bad[0], bad[1]));
            assertEquals("invalid.blank", errorOf(400, response), bad[2]);
            assertEquals(bad[2], Api.json(response).get("params").get("field").asText());
        }
        String id = created(api.post("/org-units", unit.formatted("", ", \"code\": \"empty-texts\""))).get("id").asText();
        HttpResponse<String> change = api.putWithVersion("/org-units/" + id, unit.formatted(", \"shortName\": \" \"", ""));
        assertEquals("invalid.blank", errorOf(400, change));
        assertEquals("invalid.blank", errorOf(400, api.putWithVersion("/org-units/" + id + "/parent",
                "{\"parentId\": \"\", \"validFrom\": \"2026-05-01\"}")));
        assertEquals("invalid.blank", errorOf(400, api.putWithVersion("/org-units/" + id + "/end",
                "{\"validTo\": \" \"}")));
        assertEquals("empty-texts", Api.json(api.get("/org-units/" + id + "?asOf=2026-04-01")).get("code").asText());
    }

    @Test
    void anEmptyListFailsInEveryFieldOfAWrite() throws Exception {
        TestRealm realm = TestRealm.create("org-unit-empty-lists");
        realm.textList("ORG_UNIT", "shifts");
        String unit = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2026-04-01\"%s}";
        HttpResponse<String> attribute = realm.api.post("/org-units", unit.formatted(", \"attributes\": {\"shifts\": []}"));
        assertEquals("invalid.blank", errorOf(400, attribute));
        assertEquals("attributes.shifts", Api.json(attribute).get("params").get("field").asText());

        // A field that only a response fills fails too.
        JsonNode stored = created(realm.api.post("/org-units", unit.formatted("")));
        assertFalse(stored.has("externalIds"));
        String path = "/org-units/" + stored.get("id").asText();
        HttpResponse<String> copy = realm.api.putWithVersion(path, unit.formatted(", \"code\": \"copied\", \"externalIds\": []"));
        assertEquals("invalid.blank", errorOf(400, copy));
        assertEquals("externalIds", Api.json(copy).get("params").get("field").asText());
        assertFalse(Api.json(realm.api.get(path)).has("code"));
    }

    @Test
    void aReadGivesTheNamesInTheLanguageOfTheRequest() throws Exception {
        TestRealm realm = TestRealm.withEmployer("org-unit-languages");
        Api ja = realm.api;
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        JsonNode sales = created(ja.post("/org-units", """
                {"primaryLanguage": "ja", "validFrom": "2026-04-01", "names": {
                  "ja": {"name": "営業部", "shortName": "営業", "phoneticName": "えいぎょうぶ"},
                  "en": {"name": "Sales Division", "shortName": "Sales"},
                  "EN-gb": {"name": "Sales Department"}}}
                """));
        String id = sales.get("id").asText();
        String on = "?asOf=2026-04-01";

        // With no Accept-Language, a read gives the primary names.
        assertEquals(List.of("営業部", "営業", "えいぎょうぶ"), List.of(sales.get("name").asText(),
                sales.get("shortName").asText(), sales.get("phoneticName").asText()));
        assertEquals("ja", sales.get("primaryLanguage").asText());
        assertEquals(3, sales.get("names").size());
        assertEquals("Sales Department", sales.get("names").get("en-GB").get("name").asText());
        // The first language of Accept-Language gives the names of that tag, else of that language with no region.
        Api en = ja.withAcceptLanguage("en-US, ja;q=0.5");
        JsonNode english = Api.json(en.get("/org-units/" + id + on));
        assertEquals("Sales Division", english.get("name").asText());
        assertEquals("Sales", english.get("shortName").asText());
        assertFalse(english.has("phoneticName"));
        assertEquals(sales.get("names"), english.get("names"));
        assertEquals("Sales Department", Api.json(ja.withAcceptLanguage("en-GB").get("/org-units/" + id + on))
                .get("name").asText());
        assertEquals("営業部", Api.json(ja.withAcceptLanguage("fr").get("/org-units/" + id + on)).get("name").asText());
        assertEquals(List.of("Sales Division"), Api.names(Api.json(en.get("/org-units" + on + "&q=sales"))));

        // Other resources give the name of the unit in the language of the request too.
        String user = realm.user("seller");
        JsonNode staff = created(ja.post("/staff", "{\"userId\": \"" + user + "\", \"orgUnitId\": \"" + id
                + "\", \"validFrom\": \"2026-04-01\"}"));
        JsonNode member = Api.json(en.get("/users/" + user + "/assignments" + on + "&includePath=true")).get(0);
        assertEquals("Sales Division", member.get("orgUnitName").asText());
        assertEquals(List.of("Sales Division"), Api.names(member.get("orgUnitPath")));
        assertEquals("営業部", Api.json(ja.get("/users/" + user + "/assignments" + on)).get(0).get("orgUnitName")
                .asText());
        created(ja.post("/unit-roles", "{\"employmentId\": \"" + staff.get("employment").get("id").asText()
                + "\", \"orgUnitId\": \"" + id + "\", \"role\": \"approver\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals("Sales Division", Api.json(en.get("/unit-roles" + on)).get(0).get("orgUnitName").asText());

        // A write needs a primary language with names.
        assertEquals("invalid.primary_language", errorOf(400, ja.post("/org-units",
                "{\"primaryLanguage\": \"en\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, ja.post("/org-units",
                "{\"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, ja.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {}}, \"validFrom\": \"2026-04-01\"}")));
        // A change from a date can change the primary language.
        JsonNode changed = Api.json(ja.putWithVersion("/org-units/" + id, """
                {"primaryLanguage": "en", "validFrom": "2027-04-01", "names": {
                  "ja": {"name": "営業本部"}, "en": {"name": "Sales Division"}}}
                """));
        assertEquals("Sales Division", changed.get("name").asText());
        assertEquals("営業本部", Api.json(ja.withAcceptLanguage("ja").get("/org-units/" + id + "?asOf=2027-04-01"))
                .get("name").asText());
        assertEquals("営業部", Api.json(ja.get("/org-units/" + id + on)).get("name").asText());
    }

    @Test
    void renamesAUnitOnAnEffectiveDate() throws Exception {
        String id = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"第一内科\", \"shortName\": \"一内\"}}, \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();

        HttpResponse<String> response = api.putWithVersion("/org-units/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"総合内科\"}}, \"validFrom\": \"2027-04-01\"}");

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
        String id = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"A\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        assertEquals(200, api.putWithVersion("/org-units/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"C\"}}, \"validFrom\": \"2027-04-01\"}").statusCode());

        JsonNode middle = Api.json(api.putWithVersion("/org-units/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"B\"}}, \"validFrom\": \"2026-10-01\"}"));
        assertEquals("2027-04-01", middle.get("validTo").asText());
        JsonNode corrected = Api.json(api.putWithVersion("/org-units/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"B2\"}}, \"validFrom\": \"2026-10-01\"}"));
        assertEquals("2027-04-01", corrected.get("validTo").asText());

        assertEquals("A", Api.json(api.get("/org-units/" + id + "?asOf=2026-09-30")).get("name").asText());
        assertEquals("B2", Api.json(api.get("/org-units/" + id + "?asOf=2026-10-01")).get("name").asText());
        assertEquals("C", Api.json(api.get("/org-units/" + id + "?asOf=2027-04-01")).get("name").asText());
    }

    @Test
    void rejectsABadChange() throws Exception {
        String id = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"A\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        assertEquals("org_unit.not_valid_on", errorOf(404, api.putWithVersion("/org-units/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2026-03-31\"}")));
        assertEquals("org_unit.not_found", errorOf(404, api.putWithVersion("/org-units/nope", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.putWithVersion("/org-units/" + id, "{\"validFrom\": \"2026-10-01\"}")));
        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.putWithVersion("/org-units/" + id, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"validFrom\": \"2026-10-01\"}")));
        assertEquals("A", Api.json(api.get("/org-units/" + id + "?asOf=2026-10-01")).get("name").asText());
    }

    private static String newUnit(String name, String parentId) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        return created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}" + parent + ", \"validFrom\": \"2026-04-01\"}"))
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

        HttpResponse<String> response = api.putWithVersion("/org-units/" + unit + "/parent", move(b, "2027-04-01"));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(b, Api.json(response).get("parentId").asText());
        assertEquals(a, Api.json(api.get("/org-units/" + unit + "?asOf=2027-03-31")).get("parentId").asText());
        assertEquals(b, Api.json(api.get("/org-units/" + unit + "?asOf=2027-04-01")).get("parentId").asText());

        assertFalse(Api.json(api.putWithVersion("/org-units/" + unit + "/parent", move(null, "2028-04-01"))).has("parentId"));
        assertEquals(b, Api.json(api.get("/org-units/" + unit + "?asOf=2028-03-31")).get("parentId").asText());
        // A move on the first date of the current parent corrects it.
        assertEquals(a, Api.json(api.putWithVersion("/org-units/" + unit + "/parent", move(a, "2027-04-01"))).get("parentId").asText());
        assertEquals(a, Api.json(api.get("/org-units/" + unit + "?asOf=2028-03-31")).get("parentId").asText());
        assertFalse(Api.json(api.get("/org-units/" + unit + "?asOf=2028-04-01")).has("parentId"));
    }

    @Test
    void rejectsAMoveBelowItselfAndOtherBadMoves() throws Exception {
        String a = newUnit("A", null);
        String b = newUnit("B", a);
        String c = newUnit("C", b);

        assertEquals("org_unit.cycle", errorOf(409, api.putWithVersion("/org-units/" + a + "/parent", move(c, "2026-10-01"))));
        assertEquals("org_unit.cycle", errorOf(409, api.putWithVersion("/org-units/" + a + "/parent", move(a, "2026-10-01"))));
        assertEquals("org_unit.parent_unknown", errorOf(400, api.putWithVersion("/org-units/" + c + "/parent", move("nope", "2026-10-01"))));
        assertEquals("org_unit.not_valid_on", errorOf(404, api.putWithVersion("/org-units/" + c + "/parent", move(a, "2026-03-31"))));
        assertEquals("invalid.required", errorOf(400, api.putWithVersion("/org-units/" + c + "/parent", "{\"parentId\": \"" + a + "\"}")));
        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.putWithVersion("/org-units/" + c + "/parent", move(null, "2026-10-01"))));
        assertEquals(b, Api.json(api.get("/org-units/" + c + "?asOf=2030-01-01")).get("parentId").asText());
    }

    @Test
    void endsALeafAndKeepsItsHistory() throws Exception {
        String parent = newUnit("親", null);
        String unit = newUnit("廃止予定", parent);

        HttpResponse<String> response = api.putWithVersion("/org-units/" + unit + "/end", "{\"validTo\": \"2027-04-01\"}");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode last = Api.json(response);
        assertEquals("2027-04-01", last.get("validTo").asText());
        assertEquals(parent, last.get("parentId").asText());
        assertEquals(last, Api.json(api.get("/org-units/" + unit + "?asOf=2027-03-31")));
        assertEquals("org_unit.not_valid_on", errorOf(404, api.get("/org-units/" + unit + "?asOf=2027-04-01")));
        // The end moves, and can go away again.
        assertEquals("2028-04-01", Api.json(api.putWithVersion("/org-units/" + unit + "/end", "{\"validTo\": \"2028-04-01\"}"))
                .get("validTo").asText());
        HttpResponse<String> reopened = api.deleteWithVersion("/org-units/" + unit + "/end");
        assertEquals(200, reopened.statusCode(), reopened.body());
        assertFalse(Api.json(reopened).has("validTo"));
        assertEquals(200, api.putWithVersion("/org-units/" + unit + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        // The parent can end once its last child is gone.
        assertEquals("org_unit.has_children", errorOf(409, api.putWithVersion("/org-units/" + parent + "/end", "{\"validTo\": \"2027-03-31\"}")));
        assertEquals(200, api.putWithVersion("/org-units/" + parent + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
    }

    @Test
    void rejectsABadEnd() throws Exception {
        String parent = newUnit("P", null);
        newUnit("C1", parent);
        newUnit("C2", parent);
        String renamed = newUnit("R", null);
        assertEquals(200, api.putWithVersion("/org-units/" + renamed, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"R2\"}}, \"validFrom\": \"2027-04-01\"}").statusCode());

        HttpResponse<String> response = api.withAcceptLanguage("ja").putWithVersion("/org-units/" + parent + "/end", "{\"validTo\": \"2027-04-01\"}");
        assertEquals("org_unit.has_children", errorOf(409, response));
        assertEquals("2", Api.json(response).get("params").get("count").asText());
        assertTrue(Api.json(response).get("message").asText().contains("2 件の子の組織単位"));
        assertEquals("org_unit.later_changes_exist", errorOf(409, api.putWithVersion("/org-units/" + renamed + "/end", "{\"validTo\": \"2027-01-01\"}")));
        assertEquals("org_unit.not_valid_on", errorOf(404, api.putWithVersion("/org-units/" + renamed + "/end", "{\"validTo\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.putWithVersion("/org-units/" + renamed + "/end", "{}")));
        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.putWithVersion("/org-units/" + renamed + "/end", "{\"validTo\": \"2028-04-01\"}")));
    }

    @Test
    void aUnitTypeInUseCannotBeDeleted() throws Exception {
        String typeId = created(api.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"in-use\", \"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"In use\"}}}")).get("id").asText();
        created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"unitType\": \"in-use\", \"validFrom\": \"2026-04-01\"}"));

        assertEquals("ref_item.in_use", errorOf(409, api.deleteWithVersion("/ref-items/" + typeId)));
        assertEquals(200, api.putWithVersion("/ref-items/" + typeId, "{\"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"In use\"}}, \"active\": false}").statusCode());
        assertEquals("org_unit.unit_type_unknown", errorOf(400, api.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"y\"}}, \"unitType\": \"in-use\", \"validFrom\": \"2026-04-01\"}")));
    }

    @Test
    void oneRealmDoesNotSeeTheUnitsOfAnother() throws Exception {
        String body = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Z病院\"}}, \"code\": \"z-hospital\", \"validFrom\": \"2026-04-01\"}";
        String id = created(api.post("/org-units", body)).get("id").asText();

        assertEquals("org_unit.not_found", errorOf(404, otherRealmApi.get("/org-units/" + id + "?asOf=2026-04-01")));
        assertEquals("org_unit.parent_unknown", errorOf(400, otherRealmApi.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"parentId\": \"" + id + "\", \"validFrom\": \"2026-04-01\"}")));
        assertEquals("org_unit.unit_type_unknown", errorOf(400, otherRealmApi.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"x\"}}, \"unitType\": \"hospital\", \"validFrom\": \"2026-04-01\"}")));
        created(otherRealmApi.post("/org-units", body));
    }

    @Test
    void eachRealmGetsItsOwnDefaultHierarchy() throws Exception {
        String parentId = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"P\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"C\"}}, \"parentId\": \"" + parentId + "\", \"validFrom\": \"2026-04-01\"}"));

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
        String root = Api.json(realm.api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Root\"}}, \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String branch = Api.json(realm.api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Branch\"}}, \"parentId\": \"" + root
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        assertEquals(1, Api.json(realm.api.get("/org-units/" + branch + "/history")).get("parents").size());

        // The link starts on this date, so it never held and is removed.
        HttpResponse<String> moved = realm.api.putWithVersion("/org-units/" + branch + "/parent", "{\"validFrom\": \"2026-04-01\"}");

        assertEquals(200, moved.statusCode(), moved.body());
        assertFalse(Api.json(moved).has("parentId"));
        assertEquals(0, Api.json(realm.api.get("/org-units/" + branch + "/history")).get("parents").size());
        assertEquals(2, Api.json(realm.api.get("/org-units?roots=true&asOf=2026-04-01")).size());
    }

    @Test
    void aUnitHasOneParentInEachHierarchy() throws Exception {
        TestRealm realm = TestRealm.create("org-units-matrix");
        Api matrixApi = realm.api;
        String matrix = created(matrixApi.post("/hierarchies", "{\"code\": \"matrix\", \"name\": \"Matrix\"}")).get("id").asText();
        String line = created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Line\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String project = created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Project\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String team = created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Team\"}}, \"parentId\": \"" + line
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();

        HttpResponse<String> moved = matrixApi.putWithVersion("/org-units/" + team + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + project + "\", \"validFrom\": \"2026-10-01\"}");

        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(project, Api.json(moved).get("parentId").asText());
        assertEquals(line, Api.json(matrixApi.get("/org-units/" + team + "?asOf=2026-10-01")).get("parentId").asText());
        JsonNode parents = Api.json(matrixApi.get("/org-units/" + team + "/history")).get("parents");
        assertEquals(List.of("default", "matrix"), parents.findValuesAsText("hierarchy"));
        assertEquals(List.of(line, project), parents.findValuesAsText("parentId"));

        // The project is the parent of the line in the matrix, though the team is below the line by default.
        assertEquals(200, matrixApi.putWithVersion("/org-units/" + line + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + project + "\", \"validFrom\": \"2026-10-01\"}").statusCode());
        HttpResponse<String> cycle = matrixApi.putWithVersion("/org-units/" + project + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + team + "\", \"validFrom\": \"2026-10-01\"}");
        assertEquals(409, cycle.statusCode(), cycle.body());
        assertEquals("org_unit.cycle", Api.json(cycle).get("error").asText());

        HttpResponse<String> unknown = matrixApi.putWithVersion("/org-units/" + team + "/parent?hierarchy=nope",
                "{\"validFrom\": \"2026-10-01\"}");
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("hierarchy.unknown", Api.json(unknown).get("error").asText());

        // A hierarchy with links stays. A child in the matrix keeps the project from ending.
        assertEquals(409, matrixApi.deleteWithVersion("/hierarchies/" + matrix).statusCode());
        assertEquals("org_unit.has_children", Api.json(matrixApi.putWithVersion("/org-units/" + project + "/end",
                "{\"validTo\": \"2027-04-01\"}")).get("error").asText());
        assertEquals(200, matrixApi.putWithVersion("/org-units/" + team + "/end", "{\"validTo\": \"2027-04-01\"}").statusCode());
        assertEquals(List.of("2027-04-01", "2027-04-01"),
                Api.json(matrixApi.get("/org-units/" + team + "/history")).get("parents").findValuesAsText("validTo"));
    }

    private static List<String> namesOf(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.names(Api.json(response));
    }

    @Test
    void readsTheTreeAndTheMembersOfASecondHierarchy() throws Exception {
        TestRealm realm = TestRealm.create("org-units-matrix-reads");
        Api matrixApi = realm.api;
        created(matrixApi.post("/hierarchies", "{\"code\": \"matrix\", \"name\": \"Matrix\"}"));
        // The line is the company, so that the realm can store the employment below.
        created(matrixApi.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"company\", \"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"Company\"}}, \"legalEntity\": true}"));
        String line = created(matrixApi.post("/org-units",
                "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Line\"}}, \"unitType\": \"company\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String project = created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Project\"}}, \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Alone\"}}, \"validFrom\": \"2026-04-01\"}"));
        String team = created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Team\"}}, \"parentId\": \"" + line
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        created(matrixApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Squad\"}}, \"parentId\": \"" + team
                + "\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals(200, matrixApi.putWithVersion("/org-units/" + team + "/parent?hierarchy=matrix",
                "{\"parentId\": \"" + project + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        String member = realm.user("member");
        created(matrixApi.post("/staff", "{\"userId\": \"" + member + "\", \"orgUnitId\": \"" + team
                + "\", \"validFrom\": \"2026-04-01\"}"));
        String on = "asOf=2026-04-01";

        // Only the units with a parent or a child in the matrix are in it.
        assertEquals(List.of("Project"), namesOf(matrixApi.get("/org-units?roots=true&hierarchy=matrix&" + on)));
        assertEquals(1, matrixApi.count("/org-units/count?roots=true&hierarchy=matrix&" + on));
        // Every unit is in the list, each with its parent in the hierarchy of the request.
        JsonNode inMatrix = Api.json(matrixApi.get("/org-units?hierarchy=matrix&" + on));
        assertEquals(List.of("Alone", "Line", "Project", "Squad", "Team"), Api.names(inMatrix));
        assertEquals(List.of(project), inMatrix.findValuesAsText("parentId"));
        assertEquals(List.of(team, line), Api.json(matrixApi.get("/org-units?" + on)).findValuesAsText("parentId"));
        assertEquals(List.of("Project", "Team"), Api.names(matrixApi.tree("hierarchy=matrix&" + on)));
        assertEquals(List.of("Team"), namesOf(matrixApi.get("/org-units/" + project
                + "/subtree?hierarchy=matrix&first=1&max=1&" + on)));
        assertEquals(List.of("Alone", "Line", "Team", "Squad", "Project"), Api.names(matrixApi.tree(on)));
        assertEquals(List.of("Alone", "Line", "Project"),
                namesOf(matrixApi.get("/org-units?roots=true&hierarchy=default&" + on)));

        assertEquals(List.of("Team"), namesOf(matrixApi.get("/org-units/" + project + "/children?hierarchy=matrix&" + on)));
        assertEquals(List.of(), namesOf(matrixApi.get("/org-units/" + project + "/children?" + on)));
        assertEquals(List.of("Project", "Team"), namesOf(matrixApi.get("/org-units/" + project + "/subtree?hierarchy=matrix&" + on)));
        assertEquals(1, matrixApi.count("/org-units/" + project + "/children/count?hierarchy=matrix&" + on));
        assertEquals(0, matrixApi.count("/org-units/" + project + "/children/count?" + on));
        assertEquals(2, matrixApi.count("/org-units/" + project + "/subtree/count?hierarchy=matrix&" + on));
        assertEquals(List.of("Project", "Team"), namesOf(matrixApi.get("/org-units/" + team + "/path?hierarchy=matrix&" + on)));
        assertEquals(List.of("Line", "Team"), namesOf(matrixApi.get("/org-units/" + team + "/path?" + on)));
        assertEquals(project, Api.json(matrixApi.get("/org-units/" + team + "?hierarchy=matrix&" + on)).get("parentId").asText());
        assertEquals(line, Api.json(matrixApi.get("/org-units/" + team + "?" + on)).get("parentId").asText());

        // The member of the team is below the project only in the matrix.
        String members = "/org-units/" + project + "/members?includeDescendants=true&" + on;
        assertEquals(List.of(member), Api.json(matrixApi.get(members + "&hierarchy=matrix")).findValuesAsText("userId"));
        assertEquals(List.of(), Api.json(matrixApi.get(members)).findValuesAsText("userId"));
        String counted = "/org-units/" + project + "/members/count?includeDescendants=true&" + on;
        assertEquals(1, matrixApi.count(counted + "&hierarchy=matrix"));
        assertEquals(0, matrixApi.count(counted));

        for (String path : List.of("/org-units?", "/org-units/" + team + "?", "/org-units/" + team + "/children?",
                "/org-units/" + team + "/subtree?", "/org-units/" + team + "/path?", "/org-units/" + team + "/members?",
                "/org-units/count?", "/org-units/" + team + "/children/count?", "/org-units/" + team + "/subtree/count?",
                "/org-units/" + team + "/members/count?")) {
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
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"company\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"株式会社\"}}, \"legalEntity\": true}"));
        assertTrue(company.get("legalEntity").asBoolean());
        JsonNode department = created(entityApi.post("/ref-items",
                "{\"kind\": \"UNIT_TYPE\", \"code\": \"department\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"部\"}}}"));
        assertFalse(department.has("legalEntity"));
        HttpResponse<String> wrongKind = entityApi.post("/ref-items",
                "{\"kind\": \"GRADE\", \"code\": \"g1\", \"primaryLanguage\": \"en\", \"names\": {\"en\": {\"name\": \"G1\"}}, \"legalEntity\": true}");
        assertEquals(400, wrongKind.statusCode(), wrongKind.body());
        assertEquals("ref_item.legal_entity_kind", Api.json(wrongKind).get("error").asText());

        String on = "\"validFrom\": \"2026-04-01\"}";
        String holding = created(entityApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Holding\"}}, \"unitType\": \"company\", "
                + "\"displayOrder\": 2, " + on)).get("id").asText();
        JsonNode subsidiary = created(entityApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Subsidiary\"}}, \"unitType\": \"company\", "
                + "\"displayOrder\": 1, \"parentId\": \"" + holding + "\", " + on));
        String sales = created(entityApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Sales\"}}, \"unitType\": \"department\", "
                + "\"parentId\": \"" + holding + "\", " + on)).get("id").asText();
        created(entityApi.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Untyped\"}}, " + on));

        assertTrue(subsidiary.get("legalEntity").asBoolean());
        assertFalse(Api.json(entityApi.get("/org-units/" + sales + "?asOf=2026-04-01")).has("legalEntity"));
        JsonNode entities = Api.json(entityApi.get("/org-units?legalEntity=true&asOf=2026-04-01"));
        assertEquals(List.of("Subsidiary", "Holding"), Api.names(entities));
        assertEquals(holding, entities.get(0).get("parentId").asText());
        assertEquals(List.of("Holding"),
                namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2026-04-01&first=1&max=1")));
        assertEquals(List.of(), namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2026-03-31")));
        // A unit with no unit type is not a legal entity.
        assertEquals(List.of("Sales", "Untyped"), namesOf(entityApi.get("/org-units?legalEntity=false&asOf=2026-04-01")));

        // The sales department becomes a company of its own in 2027.
        assertEquals(200, entityApi.putWithVersion("/org-units/" + sales, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Sales Inc.\"}}, \"unitType\": \"company\", "
                + "\"validFrom\": \"2027-04-01\"}").statusCode());
        assertEquals(List.of("Subsidiary", "Holding"), namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2027-03-31")));
        assertEquals(List.of("Sales Inc.", "Subsidiary", "Holding"),
                namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2027-04-01")));

        // A change that leaves the mark out takes it away.
        String companyId = company.get("id").asText();
        assertFalse(Api.json(entityApi.putWithVersion("/ref-items/" + companyId, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"株式会社\"}}}")).has("legalEntity"));
        assertEquals(List.of(), namesOf(entityApi.get("/org-units?legalEntity=true&asOf=2027-04-01")));
    }

    @Test
    void listsEveryUnitInPagesThatGoOnAfterACursor() throws Exception {
        TestRealm realm = TestRealm.create("org-units-cursor");
        Api api = realm.api;
        String root = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Root\"}}, \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        // Some units tie on the display order, the phonetic name, and the name. Some have no phonetic name.
        for (String unit : List.of(
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"外科\", \"phoneticName\": \"げか\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"内科\", \"phoneticName\": \"ないか\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"同名\", \"phoneticName\": \"どうめい\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"同名\", \"phoneticName\": \"どうめい\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Twin\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Twin\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Alpha\"}}, \"displayOrder\": 1",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"総務\", \"phoneticName\": \"そうむ\"}}",
                "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Zebra\"}}, \"displayOrder\": 2")) {
            created(api.post("/org-units", "{" + unit + ", \"parentId\": \"" + root
                    + "\", \"validFrom\": \"2026-04-01\"}"));
        }
        String later = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Later\"}}, \"validFrom\": \"2026-06-01\"}"))
                .get("id").asText();
        String on = "asOf=2026-05-01";
        JsonNode every = Api.json(api.get("/org-units?" + on));
        assertEquals(List.of("総務", "Root", "外科", "同名", "同名", "内科", "Alpha", "Twin", "Twin", "Zebra"),
                Api.names(every));

        List<String> followed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String path = "/org-units?max=2&" + on;
        for (int first = 0; path != null; first += 2) {
            HttpResponse<String> page = api.get(path);
            assertEquals(200, page.statusCode(), page.body());
            followed.addAll(Api.json(page).findValuesAsText("id"));
            skipped.addAll(Api.json(api.get("/org-units?max=2&first=" + first + "&" + on)).findValuesAsText("id"));
            path = page.headers().firstValue("Link")
                    .map(link -> link.substring(link.indexOf("/fuhito") + "/fuhito".length(), link.indexOf('>')))
                    .orElse(null);
        }
        assertEquals(every.findValuesAsText("id"), followed);
        assertEquals(followed, skipped);

        assertEquals("invalid.cursor_with_first",
                errorOf(400, api.get("/org-units?first=2&after=org-units.v1__" + root + "&" + on)));
        assertEquals("invalid.cursor", errorOf(400, api.get("/org-units?after=people.v1__" + root + "&" + on)));
        assertEquals("invalid.cursor_unit", errorOf(400, api.get("/org-units?after=org-units.v1__" + later + "&" + on)));
    }
}
