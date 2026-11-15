package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImportIT {

    private static final String ORG_UNITS = """
            外部ID,名称,名称:en,よみがな,組織種別,親の外部ID,表示順
            H,X病院,X Hospital,えっくすびょういん,hospital,,1
            N,看護部,Nursing,かんごぶ,,H,2
            W1,"1病棟, 東館",,いちびょうとう,,N,1
            """;

    private static JsonNode load(Api api, String list, String query, String csv) throws Exception {
        HttpResponse<String> response = api.postBytes("/imports/csv/" + list + "?" + query, "text/csv; charset=UTF-8",
                csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertEquals(0, report.get("errors").size(), response.body());
        return report;
    }

    @Test
    void anExportOfOrgUnitsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export");
        Api api = realm.api;
        realm.refItem("UNIT_TYPE", "ward", "病棟");
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"}").statusCode());
        // One unit made through REST has no external ID, and one comes from another system.
        String hospital = Api.json(api.post("/org-units", "{\"name\": \"X病院, 本院\", \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        assertEquals(201, api.post("/org-units", "{\"name\": \"1病棟\", \"names\": {\"en\": \"Ward One\"}, "
                + "\"shortName\": \"1W\", \"unitType\": \"ward\", \"parentId\": \"" + hospital + "\", "
                + "\"attributes\": {\"beds\": 30}, \"externalId\": \"W1\", \"source\": \"hr\", "
                + "\"validFrom\": \"2020-04-01\"}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/org-units?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
            assertTrue(exported.body().startsWith("\uFEFF" + (header.equals("en") ? "id,externalId,source,code,name,"
                    + "name:en,shortName" : "ID,外部ID,ソース,コード,名称,名称:en,略称")), exported.body());
            assertTrue(exported.body().contains("\"X病院, 本院\""), exported.body());

            JsonNode again = load(api, "org-units", "effectiveDate=2026-05-01", exported.body());

            JsonNode counts = again.get("counts").get("orgUnits");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), again.toString());
        }
        // A change in the file is a change of the unit, also of one with no external ID.
        String changed = api.get("/imports/csv/org-units?asOf=2026-05-01").body().replace("X病院, 本院", "X病院");
        assertEquals(1, load(api, "org-units", "effectiveDate=2026-06-01", changed).get("counts").get("orgUnits")
                .get("changed").asInt());
        assertEquals("X病院", Api.json(api.get("/org-units/" + hospital + "?asOf=2026-06-01")).get("name").asText());
        assertEquals(400, api.get("/imports/csv/nope").statusCode());
    }

    @Test
    void anExportOfKindsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-kinds");
        Api api = realm.api;
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"site\", \"name\": \"拠点\", "
                + "\"names\": {\"en\": \"Site\"}}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/ref-kinds?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en") ? "code,name,name:en" : "コード,名称,名称:en")
                    + "\r\nsite,拠点,Site\r\n", exported.body());
            JsonNode counts = load(api, "ref-kinds", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("refKinds");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        // A file with no column of a language keeps the stored names in it.
        load(api, "ref-kinds", "effectiveDate=2026-05-01", "code,name\r\nsite,勤務地\r\ncost-center,原価センター\r\n");
        JsonNode kinds = Api.json(api.get("/ref-kinds"));
        JsonNode site = kinds.get(kinds.findValuesAsText("code").indexOf("site"));
        assertEquals("勤務地", site.get("name").asText());
        assertEquals("Site", site.get("names").get("en").asText());
        assertTrue(kinds.findValuesAsText("code").contains("cost-center"));
        assertEquals(200, api.put("/ref-kinds/site", "{\"name\": \"拠点\"}").statusCode());
        assertEquals(404, api.put("/ref-kinds/UNIT_TYPE", "{\"name\": \"組織種別\"}").statusCode());
    }

    @Test
    void anExportOfReferenceItemsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-items");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"REF_ITEM\", \"name\": \"note\", "
                + "\"type\": \"STRING\"}").statusCode());
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"site\", \"name\": \"拠点\"}").statusCode());
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"name\": \"東京\", "
                + "\"names\": {\"en\": \"Tokyo\"}, \"displayOrder\": 2, \"attributes\": {\"note\": \"1F, west\"}}")
                .statusCode());
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"committee\", "
                + "\"name\": \"委員会\", \"group\": true}").statusCode());
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"ASSIGNMENT_KIND\", \"code\": \"acting\", "
                + "\"name\": \"代行\", \"actingHead\": true}").statusCode());
        String old = realm.refItem("JOB_TITLE", "old", "旧役職");
        assertEquals(200, api.put("/ref-items/" + old, "{\"name\": \"旧役職\", \"active\": false}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/ref-items?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en") ? "kind,code,name,name:en,"
                    + "displayOrder,active,legalEntity,actingHead,group,attributes.note"
                    : "種類,コード,名称,名称:en,表示順,有効,法人,代行,グループ,attributes.note")), exported.body());
            assertTrue(exported.body().contains("site,tokyo,東京,Tokyo,2,true,,,,\"1F, west\""), exported.body());
            assertTrue(exported.body().contains("JOB_TITLE,old,旧役職,,0,false,,,,"), exported.body());

            JsonNode counts = load(api, "ref-items", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("refItems");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String renamed = api.get("/imports/csv/ref-items").body().replace("東京", "東京本社");
        load(api, "ref-items", "effectiveDate=2026-06-01", renamed);
        assertEquals("東京本社", Api.json(api.get("/ref-items?kind=site")).get(0).get("name").asText());
    }

    @Test
    void anExportOfEmploymentsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-employments");
        Api api = realm.api;
        realm.refItem("GRADE", "g1", "1級");
        realm.refItem("GRADE", "g2", "2級");
        realm.refItem("LEAVE_REASON", "parental", "育児休業");
        realm.refItem("END_REASON", "resignation", "退職");
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"EMPLOYMENT_TERMS\", \"name\": \"shift\", "
                + "\"type\": \"STRING\"}").statusCode());
        // People and employments made through REST have no external ID.
        String hanako = Api.json(api.post("/people", "{\"name\": \"山田 花子\"}")).get("id").asText();
        String taro = Api.json(api.post("/people", "{\"name\": \"Taro\"}")).get("id").asText();
        String employment = Api.json(api.post("/employments", "{\"personId\": \"" + hanako + "\", \"staffNumber\": "
                + "\"N-1\", \"grade\": \"g1\", \"status\": \"ON_LEAVE\", \"leaveReason\": \"parental\", "
                + "\"workPercent\": 80.5, \"attributes\": {\"shift\": \"day, night\"}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String ending = Api.json(api.post("/employments", "{\"personId\": \"" + taro + "\", \"externalId\": \"E-1\", "
                + "\"source\": \"hr\", \"validFrom\": \"2021-04-01\"}")).get("id").asText();
        assertEquals(200, api.put("/employments/" + ending + "/end", "{\"validTo\": \"2027-04-01\", "
                + "\"endReason\": \"resignation\"}").statusCode());
        // Neither holds on the date of the export.
        assertEquals(201, api.post("/employments", "{\"personId\": \"" + taro + "\", \"validFrom\": \"2015-04-01\", "
                + "\"validTo\": \"2020-04-01\"}").statusCode());
        assertEquals(201, api.post("/employments", "{\"personId\": \"" + hanako + "\", \"validFrom\": \"2027-04-01\"}")
                .statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/employments?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en") ? "id,externalId,source,personId,"
                    + "staffNumber,employerId" : "ID,外部ID,ソース,人のID,職員番号,雇用主のID")), exported.body());
            assertEquals(3, exported.body().split("\r\n").length, exported.body());
            assertTrue(exported.body().contains(",g1,ON_LEAVE,parental,80.5,2020-04-01,,,\"day, night\""),
                    exported.body());
            assertTrue(exported.body().contains(",ACTIVE,,,2021-04-01,2027-04-01,resignation,"), exported.body());

            JsonNode counts = load(api, "employments", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("employments");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String promoted = api.get("/imports/csv/employments?asOf=2026-05-01").body().replace(",g1,", ",g2,");
        assertEquals(1, load(api, "employments", "effectiveDate=2026-06-01", promoted).get("counts")
                .get("employments").get("changed").asInt());
        JsonNode terms = Api.json(api.get("/employments/" + employment)).get("terms");
        assertEquals("g2", terms.get(terms.size() - 1).get("grade").asText());
    }

    @Test
    void anExportOfAssignmentsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-assignments");
        Api api = realm.api;
        realm.refItem("JOB_TITLE", "chief", "主任");
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("ASSIGNMENT_KIND", "concurrent", "兼務");
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"ASSIGNMENT\", \"name\": \"room\", "
                + "\"type\": \"STRING\"}").statusCode());
        String ward = Api.json(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String lab = Api.json(api.post("/org-units", "{\"name\": \"検査室\", \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String hanako = Api.json(api.post("/people", "{\"name\": \"山田 花子\"}")).get("id").asText();
        String employment = Api.json(api.post("/employments", "{\"personId\": \"" + hanako + "\", "
                + "\"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String main = Api.json(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2020-04-01\", \"primary\": true, \"occupation\": \"nurse\", "
                + "\"allocationPercent\": 70, \"attributes\": {\"room\": \"3F, east\"}}")).get("id").asText();
        // A change after the date of the export ends a row, not the assignment.
        assertEquals(200, api.put("/assignments/" + main + "/details", "{\"validFrom\": \"2026-07-01\", "
                + "\"jobTitle\": \"chief\", \"primary\": true}").statusCode());
        assertEquals(201, api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + lab + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2021-04-01\"}").statusCode());
        String concurrent = Api.json(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", "
                + "\"orgUnitId\": \"" + lab + "\", \"validFrom\": \"2021-04-01\", \"kind\": \"concurrent\", "
                + "\"allocationPercent\": 30, \"externalId\": \"A-2\", \"source\": \"hr\"}")).get("id").asText();
        assertEquals(200, api.put("/assignments/" + concurrent + "/end", "{\"validTo\": \"2027-04-01\"}")
                .statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/assignments?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en") ? "id,externalId,source,"
                    + "employmentId,orgUnitId" : "ID,外部ID,ソース,雇用のID,組織のID")), exported.body());
            assertEquals(3, exported.body().split("\r\n").length, exported.body());
            assertTrue(exported.body().contains(",nurse,,false,true,70,0,2020-04-01,,\"3F, east\""), exported.body());
            assertTrue(exported.body().contains(",A-2,hr,"), exported.body());
            assertTrue(exported.body().contains(",concurrent,false,false,30,0,2021-04-01,2027-04-01,"),
                    exported.body());

            JsonNode counts = load(api, "assignments", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("assignments");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String more = api.get("/imports/csv/assignments?asOf=2026-05-01").body().replace(",30,", ",40,");
        assertEquals(1, load(api, "assignments", "effectiveDate=2026-06-01", more).get("counts")
                .get("assignments").get("changed").asInt());
        JsonNode periods = Api.json(api.get("/assignments/" + concurrent)).get("periods");
        assertEquals(40, periods.get(periods.size() - 1).get("allocationPercent").asInt());
    }

    @Test
    void anExportOfPeopleLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-people");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"desk\", "
                + "\"type\": \"STRING\"}").statusCode());
        String hanako = Api.json(api.post("/people", "{\"name\": \"山田 花子\", \"names\": {\"en\": \"Hanako Yamada\"}, "
                + "\"phoneticName\": \"やまだ はなこ\", \"attributes\": {\"desk\": \"3F, east\"}}")).get("id").asText();
        assertEquals(201, api.post("/people", "{\"name\": \"Taro\", \"externalId\": \"P-1\", \"source\": \"hr\"}")
                .statusCode());
        // A person who takes the name of the Keycloak user, and keeps the link.
        String user = realm.user("linked", "Ichiro", "Suzuki");
        assertEquals(201, api.post("/people", "{\"userId\": \"" + user + "\"}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/people?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().contains("\"3F, east\""), exported.body());

            JsonNode counts = load(api, "people", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("people");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String renamed = api.get("/imports/csv/people").body().replace("山田 花子", "山田 はな子");
        load(api, "people", "effectiveDate=2026-06-01", renamed);
        assertEquals("山田 はな子", Api.json(api.get("/people/" + hanako)).get("name").asText());
        assertEquals(user, Api.json(api.get("/people?userId=" + user)).get(0).get("userId").asText());
    }

    @Test
    void loadsOrgUnitsAndStaffFromCsvFilesWithJapaneseHeaders() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv");
        Api api = realm.api;
        String nurse = realm.user("nurse");

        load(api, "ref-items", "effectiveDate=2026-04-01", """
                ﻿種類,コード,名称
                UNIT_TYPE,hospital,病院
                OCCUPATION,nurse,看護師
                EMPLOYMENT_TYPE,part-time,非常勤
                """);
        JsonNode units = load(api, "org-units", "effectiveDate=2026-04-01&source=sheet", ORG_UNITS);
        load(api, "employments", "effectiveDate=2026-04-01&source=sheet", """
                外部ID,ユーザー名,職員番号,雇用形態,勤務割合
                E1,nurse,N-001,part-time,50
                """);
        load(api, "assignments", "effectiveDate=2026-04-01&source=sheet", """
                外部ID,雇用の外部ID,組織の外部ID,職種,主務
                A1,E1,W1,nurse,はい
                """);

        assertTrue(units.get("applied").asBoolean());
        assertEquals(3, units.get("counts").get("orgUnits").get("created").asInt());
        JsonNode tree = api.tree("asOf=2026-04-01");
        // The legal entity of the realm is a root of its own.
        assertEquals(List.of("法人", "X病院", "看護部", "1病棟, 東館"), tree.findValuesAsText("name"));
        assertEquals("X Hospital", tree.get(1).get("names").get("en").asText());
        assertEquals("hospital", tree.get(1).get("unitType").asText());
        assertEquals("sheet", tree.get(1).get("source").asText());
        JsonNode member = Api.json(api.get("/users/" + nurse + "/assignments?asOf=2026-04-01&includePath=true")).get(0);
        assertEquals(List.of("X病院", "看護部", "1病棟, 東館"), member.get("orgUnitPath").findValuesAsText("name"));
        assertEquals("nurse", member.get("occupation").asText());
        assertEquals("part-time", member.get("employmentType").asText());
        assertEquals("N-001", member.get("staffNumber").asText());
        assertTrue(member.get("primary").asBoolean());

        // The same file again changes nothing. A file with one cell changes only that value.
        assertEquals(3, load(api, "org-units", "effectiveDate=2026-04-01&source=sheet", ORG_UNITS)
                .get("counts").get("orgUnits").get("unchanged").asInt());
        JsonNode renamed = load(api, "org-units", "effectiveDate=2027-04-01&source=sheet", "外部ID,名称\nW1,東病棟\n");
        assertEquals(1, renamed.get("counts").get("orgUnits").get("changed").asInt());
        JsonNode ward = api.tree("asOf=2027-04-01").get(3);
        assertEquals("東病棟", ward.get("name").asText());
        assertEquals("いちびょうとう", ward.get("phoneticName").asText());
    }

    @Test
    void aDryRunAndABadFileWriteNothing() throws Exception {
        TestRealm realm = TestRealm.create("csv-errors");
        Api api = realm.api;

        JsonNode dryRun = load(api, "org-units", "effectiveDate=2026-04-01&dryRun=true", "外部ID,名称\nH,X病院\n");
        assertTrue(dryRun.get("dryRun").asBoolean());
        assertFalse(dryRun.get("applied").asBoolean());
        assertEquals(1, dryRun.get("counts").get("orgUnits").get("created").asInt());

        HttpResponse<String> bad = api.withAcceptLanguage("ja").postBytes(
                "/imports/csv/org-units?effectiveDate=2026-04-01", "text/csv; charset=UTF-8", """
                        外部ID,名称,表示順,親の外部ID
                        H,X病院,1,

                        A,A課,多い,H
                        B,B課,2,NOPE
                        """.getBytes(StandardCharsets.UTF_8));
        assertEquals(400, bad.statusCode(), bad.body());
        JsonNode report = Api.json(bad);
        assertEquals(List.of("4", "5"), report.get("errors").findValuesAsText("line"));
        assertEquals(List.of("csv.bad_number", "import.parent_unknown"), report.get("errors").findValuesAsText("error"));
        assertEquals("表示順 は数値にしてください。指定された値は「多い」です。".replace("表示順", "displayOrder"),
                report.get("errors").get(0).get("message").asText());

        HttpResponse<String> badColumn = api.postBytes("/imports/csv/org-units?effectiveDate=2026-04-01",
                "text/csv; charset=UTF-8", "外部ID,名前\nH,x\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, badColumn.statusCode());
        assertEquals("csv.bad_column", Api.json(badColumn).get("error").asText());
        assertEquals(400, api.postBytes("/imports/csv/people?effectiveDate=2026-04-01", "text/csv",
                "a\n1\n".getBytes(StandardCharsets.UTF_8)).statusCode());
        assertEquals(400, api.postBytes("/imports/csv/org-units", "text/csv",
                "外部ID,名称\nH,x\n".getBytes(StandardCharsets.UTF_8)).statusCode());

        assertEquals("0", KeycloakEnvironment.sql(
                "select count(*) from fuhito_org_unit where realm_id = '" + realm.id() + "'"));
    }

    @Test
    void aFileInShiftJisLoadsWhenTheRequestNamesTheCharset() throws Exception {
        TestRealm realm = TestRealm.create("csv-sjis");
        Api api = realm.api;

        HttpResponse<String> response = api.postBytes("/imports/csv/org-units?effectiveDate=2026-04-01",
                "text/csv; charset=Shift_JIS", "外部ID,名称\nH,X病院\n".getBytes(Charset.forName("Shift_JIS")));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("X病院"), api.tree("asOf=2026-04-01").findValuesAsText("name"));

        HttpResponse<String> unknown = api.postBytes("/imports/csv/org-units?effectiveDate=2026-04-01",
                "text/csv; charset=no-such-charset", "a\n1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("csv.bad_charset", Api.json(unknown).get("error").asText());
    }

    @Test
    void aCsvFileNamesTheEmployerAndMarksAUnitTypeAsALegalEntity() throws Exception {
        TestRealm realm = TestRealm.create("csv-employers");
        Api api = realm.api;
        String nurse = realm.user("nurse");

        load(api, "ref-items", "effectiveDate=2026-04-01", """
                種類,コード,名称,法人
                UNIT_TYPE,company,会社,はい
                UNIT_TYPE,ward,病棟,
                """);
        load(api, "org-units", "effectiveDate=2026-04-01&source=sheet", """
                外部ID,名称,組織種別
                X,医療法人X会,company
                Y,株式会社Yサービス,company
                """);
        JsonNode loaded = load(api, "employments", "effectiveDate=2026-04-01&source=sheet", """
                外部ID,ユーザー名,雇用主の外部ID
                E1,nurse,Y
                """);

        assertTrue(loaded.get("applied").asBoolean());
        assertEquals(2, Api.json(api.get("/org-units?legalEntity=true&asOf=2026-04-01")).size());
        String employer = Api.json(api.get("/users/" + nurse + "/history")).get("employments").get(0).get("employerId").asText();
        assertEquals("株式会社Yサービス", Api.json(api.get("/org-units/" + employer + "?asOf=2026-04-01")).get("name").asText());
    }
}
