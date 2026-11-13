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
    void loadsOrgUnitsAndStaffFromCsvFilesWithJapaneseHeaders() throws Exception {
        TestRealm realm = TestRealm.create("csv");
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
        JsonNode tree = Api.json(api.get("/org-units?asOf=2026-04-01"));
        assertEquals(List.of("X病院", "看護部", "1病棟, 東館"), tree.findValuesAsText("name"));
        assertEquals("X Hospital", tree.get(0).get("names").get("en").asText());
        assertEquals("hospital", tree.get(0).get("unitType").asText());
        assertEquals("sheet", tree.get(0).get("source").asText());
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
        JsonNode ward = Api.json(api.get("/org-units?asOf=2027-04-01")).get(2);
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
        assertEquals(List.of("X病院"), Api.json(api.get("/org-units?asOf=2026-04-01")).findValuesAsText("name"));

        HttpResponse<String> unknown = api.postBytes("/imports/csv/org-units?effectiveDate=2026-04-01",
                "text/csv; charset=no-such-charset", "a\n1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("csv.bad_charset", Api.json(unknown).get("error").asText());
    }
}
