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
            外部ID,主言語,名称,名称:en,よみがな,組織種別,親の外部ID,表示順
            H,ja,X病院,X Hospital,えっくすびょういん,hospital,,1
            N,ja,看護部,Nursing,かんごぶ,,H,2
            W1,ja,"1病棟, 東館",,いちびょうとう,,N,1
            """;

    private static JsonNode load(Api api, String list, String query, String csv) throws Exception {
        HttpResponse<String> response = api.postBytes("/imports/csv/" + list + "?" + query, "text/csv; charset=UTF-8",
                csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertEquals(0, report.get("errors").size(), response.body());
        return report;
    }

    // The code of the error of the first bad row of a file that fails as a whole.
    private static String failure(Api api, String list, String query, String csv) throws Exception {
        HttpResponse<String> response = api.postBytes("/imports/csv/" + list + "?" + query, "text/csv; charset=UTF-8",
                csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(400, response.statusCode(), response.body());
        return Api.json(response).get("errors").get(0).get("error").asText();
    }

    @Test
    void anExportOfOrgUnitsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export");
        Api api = realm.api;
        realm.refItem("UNIT_TYPE", "ward", "病棟");
        realm.refItem("WORK_LOCATION", "tokyo", "東京");
        realm.refItem("COST_CENTER", "cc-1", "第1病棟");
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"}").statusCode());
        // One unit made through REST has no external ID, and one comes from another system.
        String hospital = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X病院, 本院\"}}, \"workLocation\": \"tokyo\", "
                + "\"validFrom\": \"2020-04-01\"}")).get("id").asText();
        assertEquals(201, api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"1病棟\", \"shortName\": \"1W\"}, \"en\": {\"name\": \"Ward One\"}}, \"unitType\": \"ward\", \"costCenter\": \"cc-1\", \"parentId\": \""
                + hospital + "\", \"attributes\": {\"beds\": 30}, \"externalId\": \"W1\", \"source\": \"hr\", "
                + "\"validFrom\": \"2020-04-01\"}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/org-units?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
            assertTrue(exported.body().startsWith("\uFEFF" + (header.equals("en")
                    ? "id,externalId,source,code,primaryLanguage,name,shortName,phoneticName,name:en,shortName:en,"
                            + "phoneticName:en,unitType"
                    : "ID,外部ID,ソース,コード,主言語,名称,略称,よみがな,名称:en,略称:en,よみがな:en,組織種別")),
                    exported.body());
            assertTrue(exported.body().contains("\"X病院, 本院\""), exported.body());
            // The columns of the source and the external ID hold the system that made the unit and its ID there.
            assertTrue(exported.body().contains(",W1,hr,,ja,1病棟,1W,,Ward One,,,ward,"), exported.body());
            assertTrue(exported.body().contains(header.equals("en") ? ",unitType,workLocation,costCenter,parentId,"
                    : ",組織種別,勤務地,コストセンター,親のID,"), exported.body());
            // The ward takes the work location of the hospital, and the file has only its own values.
            assertTrue(exported.body().contains(",,tokyo,,,0,"), exported.body());
            assertTrue(exported.body().contains(",ward,,cc-1," + hospital + ",0,"), exported.body());

            JsonNode again = load(api, "org-units", "effectiveDate=2026-05-01", exported.body());

            JsonNode counts = again.get("counts").get("orgUnits");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), again.toString());
        }
        // A change in the file is a change of the unit, also of one with no external ID.
        String changed = api.get("/imports/csv/org-units?asOf=2026-05-01").body().replace("X病院, 本院", "X病院");
        assertEquals(1, load(api, "org-units", "effectiveDate=2026-06-01", changed).get("counts").get("orgUnits")
                .get("changed").asInt());
        assertEquals("X病院", Api.json(api.get("/org-units/" + hospital + "?asOf=2026-06-01")).get("name").asText());
        // A file can give the start of a stored unit. It is the first date of the unit, not of its details of the date.
        assertEquals(0, load(api, "org-units", "effectiveDate=2026-06-01", "id,validFrom\r\n" + hospital
                + ",2020-04-01\r\n").get("counts").get("orgUnits").get("changed").asInt());
        assertEquals("import.value_fixed", failure(api, "org-units", "effectiveDate=2026-06-01", "id,validFrom\r\n"
                + hospital + ",2026-06-01\r\n"));
        // A cell of a code that is no item fails.
        HttpResponse<String> unknown = api.postBytes("/imports/csv/org-units?effectiveDate=2026-06-01",
                "text/csv; charset=UTF-8", ("ID,名称,勤務地\r\n" + hospital + ",X病院,osaka\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("ref_item.unknown", Api.json(unknown).get("errors").get(0).get("error").asText());
        assertEquals(400, api.get("/imports/csv/nope").statusCode());
    }

    @Test
    void aCsvFileEndsAUnitWithItsSuccessors() throws Exception {
        TestRealm realm = TestRealm.create("csv-successors");
        Api api = realm.api;
        String old = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"旧病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String first = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String second = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"2病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();

        JsonNode report = load(api, "org-units", "effectiveDate=2026-05-01", "id,validTo,successorIds\r\n" + old
                + ",2027-04-01," + first + "|" + second + "\r\n");

        assertEquals(1, report.get("counts").get("orgUnits").get("changed").asInt(), report.toString());
        assertEquals(List.of(first, second).stream().sorted().toList(), Api.json(api.get("/org-units/" + old
                + "/succession")).get("successors").findValuesAsText("orgUnitId").stream().sorted().toList());
        String exported = api.get("/imports/csv/org-units?asOf=2026-05-01").body();
        assertTrue(exported.contains(",2027-04-01," + String.join("|", Api.json(api.get("/org-units/" + old
                + "/succession")).get("successors").findValuesAsText("orgUnitId")) + ",,,\r\n"), exported);
        JsonNode again = load(api, "org-units", "effectiveDate=2026-05-01", exported).get("counts").get("orgUnits");
        assertEquals(0, again.get("created").asInt() + again.get("changed").asInt(), again.toString());
    }

    @Test
    void aCsvFileLinksAUnitToAnOrganizationByItsAlias() throws Exception {
        TestRealm realm = TestRealm.create("csv-organizations");
        Api api = realm.api;
        String organization = realm.organization("x-hospital");
        String hospital = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X病院\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();

        JsonNode linked = load(api, "org-units", "effectiveDate=2026-05-01", "id,organizationAlias\r\n" + hospital
                + ",x-hospital\r\n");

        assertEquals(1, linked.get("counts").get("orgUnits").get("changed").asInt(), linked.toString());
        assertEquals(organization, Api.json(api.get("/org-units/" + hospital + "?asOf=2026-05-01"))
                .get("keycloakOrganizationId").asText());
        String exported = api.get("/imports/csv/org-units?asOf=2026-05-01&header=ja").body();
        assertTrue(exported.contains("Organizationの別名") && exported.contains(",x-hospital"), exported);
        JsonNode again = load(api, "org-units", "effectiveDate=2026-05-01", exported).get("counts").get("orgUnits");
        assertEquals(0, again.get("created").asInt() + again.get("changed").asInt(), again.toString());
        load(api, "org-units", "effectiveDate=2026-05-01", "id,organizationAlias\r\n" + hospital + ",#CLEAR\r\n");
        load(api, "org-units", "effectiveDate=2026-05-01", "id,タイムゾーン,日の始まり\r\n" + hospital
                + ",Asia/Tokyo,05:00\r\n");
        assertTrue(api.get("/imports/csv/org-units?asOf=2026-05-01").body().contains(",Asia/Tokyo,05:00\r\n"));
        assertTrue(Api.json(api.get("/org-units/" + hospital + "?asOf=2026-05-01")).path("keycloakOrganizationId")
                .isMissingNode());
    }

    @Test
    void anExportOfReportingLinesLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-lines");
        Api api = realm.api;
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String assignment = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", "
                + "\"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2020-04-01\"}")).get("assignment").get("id")
                .asText();
        String line = Api.json(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + realm.user("doctor") + "\", \"validFrom\": \"2021-04-01\"}"))
                .get("id").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/reporting-lines?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en")
                    ? "id,externalId,source,assignmentId,managerPersonId,kind,validFrom,validTo\r\n"
                    : "ID,外部ID,ソース,所属のID,上司の人のID,種類,開始日,終了日\r\n") + line + ",,," + assignment + ","),
                    exported.body());
            assertTrue(exported.body().endsWith(",SOLID,2021-04-01,\r\n"), exported.body());
            JsonNode counts = load(api, "reporting-lines", "effectiveDate=2026-05-01", exported.body())
                    .get("counts").get("reportingLines");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        assertEquals("import.value_fixed", failure(api, "reporting-lines", "effectiveDate=2026-06-01",
                "id,validFrom\r\n" + line + ",2022-04-01\r\n"));
        // A new solid manager does not end the stored line. The file ends it in a row of its own.
        realm.user("chief");
        assertEquals("reporting_line.solid_exists", failure(api, "reporting-lines", "effectiveDate=2026-06-01",
                "externalId,assignmentId,managerUsername\r\nL-2," + assignment + ",chief\r\n"));
        // The manager of a stored line does not change. A different line needs a different external ID.
        assertEquals("import.value_fixed", failure(api, "reporting-lines", "effectiveDate=2026-06-01",
                "id,managerUsername\r\n" + line + ",chief\r\n"));
        load(api, "reporting-lines", "effectiveDate=2026-06-01", "id,externalId,assignmentId,managerUsername,validTo"
                + "\r\n,L-2," + assignment + ",chief,\r\n" + line + ",,,,2026-06-01\r\n");
        assertTrue(api.get("/imports/csv/reporting-lines?asOf=2026-05-31").body().contains(line + ","));
        assertTrue(api.get("/imports/csv/reporting-lines?asOf=2026-05-31").body().endsWith(",2026-06-01\r\n"));
        String next = api.get("/imports/csv/reporting-lines?asOf=2026-06-01").body();
        assertFalse(next.contains(line + ","), next);
        assertTrue(next.contains(",L-2,import," + assignment + ","), next);
        JsonNode again = load(api, "reporting-lines", "effectiveDate=2026-06-01", next).get("counts")
                .get("reportingLines");
        assertEquals(0, again.get("created").asInt() + again.get("changed").asInt(), again.toString());
    }

    @Test
    void anExportOfRolesOverOrgUnitsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-roles");
        Api api = realm.api;
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String employment = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", "
                + "\"orgUnitId\": \"" + ward + "\", \"validFrom\": \"2020-04-01\"}")).get("employment").get("id")
                .asText();
        String role = Api.json(api.post("/unit-roles", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + ward + "\", \"role\": \"approver\", \"validFrom\": \"2021-04-01\"}")).get("id").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/unit-roles?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en")
                    ? "id,externalId,source,employmentId,orgUnitId,role,includesDescendants,validFrom,validTo"
                    : "ID,外部ID,ソース,雇用のID,組織のID,ロール,下位の組織も含む,開始日,終了日") + "\r\n" + role + ",,,"
                    + employment + "," + ward + ",approver,false,2021-04-01,\r\n", exported.body());
            JsonNode counts = load(api, "unit-roles", "effectiveDate=2026-05-01", exported.body())
                    .get("counts").get("unitRoles");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        assertEquals("import.value_fixed", failure(api, "unit-roles", "effectiveDate=2026-06-01",
                "id,validFrom\r\n" + role + ",2022-04-01\r\n"));
        // Whether the role covers the units below does not change. The file ends it and adds a new role.
        assertEquals("import.value_fixed", failure(api, "unit-roles", "effectiveDate=2026-06-01",
                "id,includesDescendants\r\n" + role + ",true\r\n"));
        load(api, "unit-roles", "effectiveDate=2026-06-01", "id,externalId,employmentId,orgUnitId,role,"
                + "includesDescendants,validTo\r\n,R-2," + employment + "," + ward + ",approver,true,\r\n" + role
                + ",,,,,,2026-06-01\r\n");
        assertTrue(api.get("/imports/csv/unit-roles?asOf=2026-05-31").body().endsWith(",false,2021-04-01,2026-06-01\r\n"));
        String next = api.get("/imports/csv/unit-roles?asOf=2026-06-01").body();
        assertTrue(next.endsWith(",R-2,import," + employment + "," + ward + ",approver,true,2026-06-01,\r\n"), next);
        JsonNode again = load(api, "unit-roles", "effectiveDate=2026-06-01", next).get("counts").get("unitRoles");
        assertEquals(0, again.get("created").asInt() + again.get("changed").asInt(), again.toString());
    }

    @Test
    void anExportOfQualificationsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-qualifications");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"QUALIFICATION\", \"name\": \"office\", "
                + "\"type\": \"STRING\"}").statusCode());
        String nurse = realm.user("nurse");
        String license = Api.json(api.post("/qualifications", "{\"userId\": \"" + nurse + "\", \"kind\": \"license\", "
                + "\"number\": \"123\", \"validFrom\": \"2010-04-01\", \"validTo\": \"2020-04-01\", "
                + "\"attributes\": {\"office\": \"東京\"}}")).get("id").asText();
        String person = Api.json(api.get("/qualifications/" + license)).get("personId").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/qualifications?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en")
                    ? "id,externalId,source,personId,kind,number,validFrom,validTo,attributes.office"
                    : "ID,外部ID,ソース,人のID,種類,番号,開始日,終了日,attributes.office") + "\r\n" + license + ",,,"
                    + person + ",license,123,2010-04-01,2020-04-01,東京\r\n", exported.body());
            JsonNode counts = load(api, "qualifications", "effectiveDate=2026-05-01", exported.body())
                    .get("counts").get("qualifications");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        // A renewal is a change of the stored qualification. A new one names its person by username.
        load(api, "qualifications", "effectiveDate=2026-05-01", "id,validTo\r\n" + license + ",2030-04-01\r\n");
        load(api, "qualifications", "effectiveDate=2026-05-01", "externalId,source,username,kind\r\nQ-9,hr,nurse,license\r\n");
        JsonNode stored = Api.json(api.get("/users/" + nurse + "/qualifications?all=true"));
        assertEquals(2, stored.size(), stored.toString());
        assertEquals(List.of("2030-04-01"), stored.findValuesAsText("validTo"), stored.toString());
        assertTrue(api.get("/imports/csv/qualifications?asOf=2026-05-01").body().contains(",2026-05-01,"));
    }

    @Test
    void anExportOfIdsOfOtherSystemsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-external-ids");
        Api api = realm.api;
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        assertEquals(200, api.putWithVersion("/external-ids", "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"P-1\"}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/external-ids?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en") ? "resourceType,resourceId,source,externalId"
                    : "リソースの種類,リソースのID,ソース,外部ID") + "\r\nORG_UNIT," + ward + ",payroll,P-1\r\n",
                    exported.body());
            JsonNode counts = load(api, "external-ids", "effectiveDate=2026-05-01", exported.body())
                    .get("counts").get("externalIds");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        load(api, "external-ids", "effectiveDate=2026-05-01", "resourceType,resourceId,source,externalId\r\n"
                + "ORG_UNIT," + ward + ",payroll,#CLEAR\r\nORG_UNIT," + ward + ",ledger,L-1\r\n");
        assertEquals("﻿resourceType,resourceId,source,externalId\r\nORG_UNIT," + ward + ",ledger,L-1\r\n",
                api.get("/imports/csv/external-ids").body());
    }

    @Test
    void anExportOfChangeOrdersLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-orders");
        Api api = realm.api;
        String chief = realm.user("chief");
        String order = Api.json(api.post("/change-orders", "{\"number\": \"2026-01\", \"title\": \"4月の異動, 本院\", "
                + "\"effectiveDate\": \"2026-04-01\", \"approverUserId\": \"" + chief + "\"}")).get("id").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/change-orders?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en")
                    ? "id,externalId,source,number,kind,title,note,orderDate,effectiveDate,approverUserId,"
                            + "requesterUserId,externalReference"
                    : "ID,外部ID,ソース,番号,種類,件名,備考,発令日,発効日,承認者のユーザーID,申請者のユーザーID,外部の参照")
                    + "\r\n" + order + ",,,2026-01,,\"4月の異動, 本院\",,,2026-04-01," + chief + ",,\r\n",
                    exported.body());
            JsonNode counts = load(api, "change-orders", "effectiveDate=2026-05-01", exported.body())
                    .get("counts").get("changeOrders");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        load(api, "change-orders", "effectiveDate=2026-10-01", String.join("\r\n",
                "id,externalId,number,title,requesterUsername", order + ",,2026-01,4月の異動,chief",
                ",O-2,2026-02,10月の異動,"));
        JsonNode orders = Api.json(api.get("/change-orders"));
        assertEquals(List.of("2026-02", "2026-01"), orders.findValuesAsText("number"));
        assertEquals(List.of("2026-10-01", "2026-04-01"), orders.findValuesAsText("effectiveDate"));
        assertEquals(chief, orders.get(1).get("requesterUserId").asText());
        // The number is a value, so a new order with a taken number fails.
        assertEquals("change_order.number_taken", failure(api, "change-orders", "effectiveDate=2026-10-01",
                "externalId,number,title\r\nO-3,2026-02,10月の異動\r\n"));
        assertTrue(api.get("/imports/csv/change-orders").body().contains(",O-2,import,2026-02,"));
    }

    @Test
    void anExportOfKindsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-kinds");
        Api api = realm.api;
        String site = Api.json(api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"拠点\"}, \"en\": {\"name\": \"Site\"}}}")).get("id").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/ref-kinds?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en") ? "id,externalId,source,code,primaryLanguage,name,name:en"
                    : "ID,外部ID,ソース,コード,主言語,名称,名称:en") + "\r\n" + site + ",,,site,ja,拠点,Site\r\n",
                    exported.body());
            JsonNode counts = load(api, "ref-kinds", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("refKinds");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        // A file with no column of a language keeps the stored names in it. A blank primary language keeps the
        // stored one.
        load(api, "ref-kinds", "effectiveDate=2026-05-01", "id,externalId,code,primaryLanguage,name\r\n" + site
                + ",,,,勤務地\r\n,CC,cost-center,ja,原価センター\r\n");
        // The code is a value. It does not name a stored kind, and it does not change.
        assertEquals("ref_kind.code_taken", failure(api, "ref-kinds", "effectiveDate=2026-05-01",
                "externalId,code,primaryLanguage,name\r\nS,site,ja,拠点\r\n"));
        assertEquals("import.value_fixed", failure(api, "ref-kinds", "effectiveDate=2026-05-01",
                "externalId,code\r\nCC,cost\r\n"));
        JsonNode kinds = Api.json(api.get("/ref-kinds"));
        JsonNode renamed = kinds.get(kinds.findValuesAsText("code").indexOf("site"));
        assertEquals("勤務地", renamed.get("name").asText());
        assertEquals("ja", renamed.get("primaryLanguage").asText());
        assertEquals("Site", renamed.get("names").get("en").get("name").asText());
        assertTrue(kinds.findValuesAsText("code").contains("cost-center"));
        assertEquals(200, api.putWithVersion("/ref-kinds/site", "{\"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"拠点\"}}}").statusCode());
        assertEquals(404, api.putWithVersion("/ref-kinds/UNIT_TYPE", "{\"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"組織種別\"}}}").statusCode());
        assertEquals(204, api.deleteWithVersion("/ref-kinds/cost-center").statusCode());
    }

    @Test
    void anExportOfAttributeDefinitionsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-definitions");
        Api api = realm.api;
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}}}").statusCode());
        String site = Api.json(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"site\", "
                + "\"type\": \"REF_ITEM\", \"refKind\": \"site\"}")).get("id").asText();
        String beds = Api.json(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\", \"source\": \"hr\", \"externalId\": \"BEDS\"}")).get("id").asText();
        String tags = Api.json(api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"tags\", "
                + "\"type\": \"STRING\", \"multiple\": true}")).get("id").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/attribute-definitions?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en") ? "id,externalId,source,target,name,type,refKind,multiple,required"
                    : "ID,外部ID,ソース,対象,名称,型,参照の種類,複数,必須") + "\r\n" + beds
                    + ",BEDS,hr,ORG_UNIT,beds,NUMBER,,false,false\r\n" + tags + ",,,ORG_UNIT,tags,STRING,,true,false\r\n"
                    + site + ",,,PERSON,site,REF_ITEM,site,false,false\r\n", exported.body());
            JsonNode counts = load(api, "attribute-definitions", "effectiveDate=2026-05-01", exported.body())
                    .get("counts").get("attributeDefinitions");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        // A blank cell keeps the stored value.
        load(api, "attribute-definitions", "effectiveDate=2026-05-01&source=hr", "externalId,required\r\nBEDS,true\r\n");
        JsonNode kept = Api.json(api.get("/attribute-definitions?target=ORG_UNIT")).get(0);
        assertEquals("NUMBER", kept.get("type").asText());
        assertTrue(kept.get("required").asBoolean());
        assertEquals(1, load(api, "attribute-definitions", "effectiveDate=2026-05-01",
                "id,multiple\r\n" + beds + ",true\r\n").get("counts").get("attributeDefinitions")
                .get("changed").asInt());
        assertTrue(Api.json(api.get("/attribute-definitions?target=ORG_UNIT")).get(0).get("multiple").asBoolean());
    }

    @Test
    void anExportOfHierarchiesLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-hierarchies");
        Api api = realm.api;
        String project = Api.json(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"プロジェクト\"}"))
                .get("id").asText();
        String standard = Api.json(api.get("/hierarchies")).get(0).get("id").asText();

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/hierarchies?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en") ? "id,externalId,source,code,name" : "ID,外部ID,ソース,コード,名称")
                    + "\r\n" + standard + ",,,default,Default\r\n" + project + ",,,project,プロジェクト\r\n",
                    exported.body());
            JsonNode counts = load(api, "hierarchies", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("hierarchies");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        load(api, "hierarchies", "effectiveDate=2026-05-01", "id,externalId,code,name\r\n" + standard
                + ",,,組織図\r\n,FIN,finance,管理会計\r\n");
        assertEquals("hierarchy.code_taken", failure(api, "hierarchies", "effectiveDate=2026-05-01",
                "externalId,code,name\r\nD,default,組織図\r\n"));
        assertEquals(List.of("default", "finance", "project"),
                Api.json(api.get("/hierarchies")).findValuesAsText("code"));
        assertEquals(List.of("組織図", "管理会計", "プロジェクト"),
                Api.json(api.get("/hierarchies")).findValuesAsText("name"));
    }

    @Test
    void anExportOfParentsInHierarchiesLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.create("csv-export-parents");
        Api api = realm.api;
        assertEquals(201, api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"プロジェクト\"}")
                .statusCode());
        String hospital = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"病院\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String first = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String second = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"2病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        assertEquals(200, api.putWithVersion("/org-units/" + first + "/parent?hierarchy=project", "{\"parentId\": \""
                + hospital + "\", \"validFrom\": \"2021-04-01\"}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/org-unit-parents?asOf=2026-04-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertEquals("﻿" + (header.equals("en") ? "hierarchy,orgUnitId,parentId" : "階層,組織のID,親のID")
                    + "\r\nproject," + first + "," + hospital + "\r\n", exported.body());
            JsonNode counts = load(api, "org-unit-parents", "effectiveDate=2026-04-01", exported.body())
                    .get("counts").get("orgUnitParents");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        // The cell #CLEAR takes a unit out of the hierarchy.
        load(api, "org-unit-parents", "effectiveDate=2026-05-01", "hierarchy,orgUnitId,parentId\r\nproject,"
                + second + "," + hospital + "\r\nproject," + first + ",#CLEAR\r\n");
        assertEquals(List.of(second), Api.json(api.get("/org-units/" + hospital
                + "/children?asOf=2026-05-01&hierarchy=project")).findValuesAsText("id"));
        assertEquals(List.of(first), Api.json(api.get("/org-units/" + hospital
                + "/children?asOf=2026-04-30&hierarchy=project")).findValuesAsText("id"));
    }

    @Test
    void anExportOfReferenceItemsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-items");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"REF_ITEM\", \"name\": \"note\", "
                + "\"type\": \"STRING\"}").statusCode());
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"site\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}}}").statusCode());
        String tokyo = Api.json(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"東京\"}, \"en\": {\"name\": \"Tokyo\"}}, \"displayOrder\": 2, \"attributes\": {\"note\": \"1F, west\"}, "
                + "\"source\": \"hr\", \"externalId\": \"T-1\"}")).get("id").asText();
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"committee\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"委員会\"}}, \"lateral\": true}").statusCode());
        assertEquals(201, api.post("/ref-items", "{\"kind\": \"ASSIGNMENT_KIND\", \"code\": \"acting\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"代行\"}}, \"actingHead\": true}").statusCode());
        String old = realm.refItem("JOB_TITLE", "old", "旧役職");
        assertEquals(200, api.putWithVersion("/ref-items/" + old, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"旧役職\"}}, \"active\": false}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/ref-items?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en") ? "id,externalId,source,kind,code,"
                    + "primaryLanguage,name,name:en,displayOrder,active,legalEntity,actingHead,lateral,attributes.note"
                    : "ID,外部ID,ソース,種類,コード,主言語,名称,名称:en,表示順,有効,法人,代行,横断,attributes.note")),
                    exported.body());
            assertTrue(exported.body().contains(tokyo + ",T-1,hr,site,tokyo,ja,東京,Tokyo,2,true,,,,\"1F, west\""),
                    exported.body());
            assertTrue(exported.body().contains(old + ",,,JOB_TITLE,old,ja,旧役職,,0,false,,,,"), exported.body());

            JsonNode counts = load(api, "ref-items", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("refItems");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String renamed = api.get("/imports/csv/ref-items").body().replace("東京", "東京本社");
        load(api, "ref-items", "effectiveDate=2026-06-01", renamed);
        assertEquals("東京本社", Api.json(api.get("/ref-items?kind=site")).get(0).get("name").asText());

        // The new name holds from the effective date. The file of an earlier date has the old one.
        assertEquals("東京", Api.json(api.get("/ref-items?kind=site&asOf=2026-05-31")).get(0).get("name").asText());
        assertTrue(api.get("/imports/csv/ref-items?asOf=2026-05-31").body().contains("site,tokyo,ja,東京,Tokyo,"));
        assertTrue(api.get("/imports/csv/ref-items?asOf=2026-06-01").body().contains("site,tokyo,ja,東京本社,Tokyo,"));

        // A row can name the first date of its names.
        load(api, "ref-items", "effectiveDate=2026-06-01", "id,name,validFrom\n" + tokyo + ",東京本店,2030-04-01\n");
        assertEquals("東京本社", Api.json(api.get("/ref-items?kind=site&asOf=2030-03-31")).get(0).get("name").asText());
        assertEquals("東京本店", Api.json(api.get("/ref-items?kind=site&asOf=2030-04-01")).get(0).get("name").asText());
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
        String hanako = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}}")).get("id").asText();
        String taro = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Taro\"}}}")).get("id").asText();
        String employment = Api.json(api.post("/employments", "{\"personId\": \"" + hanako + "\", \"staffNumber\": "
                + "\"N-1\", \"grade\": \"g1\", \"status\": \"ON_LEAVE\", \"leaveReason\": \"parental\", "
                + "\"workPercent\": 80.5, \"contractEndDate\": \"2027-03-31\", "
                + "\"attributes\": {\"shift\": \"day, night\"}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String ending = Api.json(api.post("/employments", "{\"personId\": \"" + taro + "\", \"externalId\": \"E-1\", "
                + "\"source\": \"hr\", \"validFrom\": \"2021-04-01\"}")).get("id").asText();
        assertEquals(200, api.putWithVersion("/employments/" + ending + "/end", "{\"validTo\": \"2027-04-01\", "
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
            assertTrue(exported.body().contains(",g1,ON_LEAVE,parental,80.5,2027-03-31,2020-04-01,,,,"
                    + "\"day, night\""), exported.body());
            assertTrue(exported.body().contains(",ACTIVE,,,,2021-04-01,2027-04-01,resignation,"), exported.body());

            JsonNode counts = load(api, "employments", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("employments");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String promoted = api.get("/imports/csv/employments?asOf=2026-05-01").body().replace(",g1,", ",g2,");
        assertEquals(1, load(api, "employments", "effectiveDate=2026-06-01", promoted).get("counts")
                .get("employments").get("changed").asInt());
        JsonNode terms = Api.json(api.get("/employments/" + employment)).get("terms");
        assertEquals("g2", terms.get(terms.size() - 1).get("grade").asText());
        // The file has the start of the employment, not of its terms of the date. So it loads again after a change.
        String next = api.get("/imports/csv/employments?asOf=2026-06-01").body();
        assertTrue(next.contains(",g2,ON_LEAVE,parental,80.5,2027-03-31,2020-04-01,,,,"), next);
        JsonNode again = load(api, "employments", "effectiveDate=2026-06-01", next).get("counts").get("employments");
        assertEquals(0, again.get("created").asInt() + again.get("changed").asInt(), again.toString());
        assertEquals("import.value_fixed", failure(api, "employments", "effectiveDate=2026-06-01",
                "id,validFrom\r\n" + employment + ",2026-06-01\r\n"));
    }

    @Test
    void anExportOfAssignmentsLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-assignments");
        Api api = realm.api;
        realm.refItem("JOB_TITLE", "chief", "主任");
        realm.refItem("OCCUPATION", "nurse", "看護師");
        realm.refItem("ASSIGNMENT_KIND", "concurrent", "兼務");
        realm.refItem("WORK_LOCATION", "home", "在宅");
        realm.refItem("WORK_LOCATION", "tokyo", "東京");
        realm.refItem("COST_CENTER", "cc-lab", "検査室");
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"ASSIGNMENT\", \"name\": \"room\", "
                + "\"type\": \"STRING\"}").statusCode());
        String ward = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"1病棟\"}}, \"validFrom\": \"2020-04-01\"}"))
                .get("id").asText();
        String lab = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"検査室\"}}, \"workLocation\": \"tokyo\", "
                + "\"costCenter\": \"cc-lab\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String hanako = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"山田 花子\"}}}")).get("id").asText();
        String employment = Api.json(api.post("/employments", "{\"personId\": \"" + hanako + "\", "
                + "\"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String main = Api.json(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + ward + "\", \"validFrom\": \"2020-04-01\", \"primary\": true, \"occupation\": \"nurse\", "
                + "\"allocationPercent\": 70, \"attributes\": {\"room\": \"3F, east\"}}")).get("id").asText();
        // A change after the date of the export ends a row, not the assignment.
        assertEquals(200, api.putWithVersion("/assignments/" + main + "/details", "{\"validFrom\": \"2026-07-01\", "
                + "\"jobTitle\": \"chief\", \"primary\": true}").statusCode());
        assertEquals(201, api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + lab + "\", \"validFrom\": \"2020-04-01\", \"validTo\": \"2021-04-01\"}").statusCode());
        String concurrent = Api.json(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", "
                + "\"orgUnitId\": \"" + lab + "\", \"validFrom\": \"2021-04-01\", \"kind\": \"concurrent\", "
                + "\"workLocation\": \"home\", \"allocationPercent\": 30, \"externalId\": \"A-2\", "
                + "\"source\": \"hr\"}")).get("id").asText();
        assertEquals(200, api.putWithVersion("/assignments/" + concurrent + "/end", "{\"validTo\": \"2027-04-01\"}")
                .statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/assignments?asOf=2026-05-01&header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en") ? "id,externalId,source,"
                    + "employmentId,orgUnitId" : "ID,外部ID,ソース,雇用のID,組織のID")), exported.body());
            assertEquals(3, exported.body().split("\r\n").length, exported.body());
            assertTrue(exported.body().contains(header.equals("en") ? ",assignmentKind,workLocation,costCenter,head,"
                    : ",所属区分,勤務地,コストセンター,長,"), exported.body());
            assertTrue(exported.body().contains(",nurse,,,,false,true,70,0,2020-04-01,,,\"3F, east\""),
                    exported.body());
            assertTrue(exported.body().contains(",A-2,hr,"), exported.body());
            // The file has the work location of the assignment, and not the cost center of the lab.
            assertTrue(exported.body().contains(",concurrent,home,,false,false,30,0,2021-04-01,2027-04-01,"),
                    exported.body());

            JsonNode counts = load(api, "assignments", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("assignments");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String more = api.get("/imports/csv/assignments?asOf=2026-05-01&header=ja").body().replace(",30,", ",40,")
                .replace(",home,", ",#CLEAR,");
        assertEquals(1, load(api, "assignments", "effectiveDate=2026-06-01", more).get("counts")
                .get("assignments").get("changed").asInt());
        JsonNode periods = Api.json(api.get("/assignments/" + concurrent)).get("periods");
        JsonNode last = periods.get(periods.size() - 1);
        assertEquals(40, last.get("allocationPercent").asInt());
        // With no work location of its own, the assignment takes the one of the lab.
        assertTrue(last.path("workLocation").isMissingNode(), last.toString());
        assertEquals("tokyo", last.get("effectiveWorkLocation").asText());
        assertEquals("home", periods.get(0).get("workLocation").asText());
        // The file has the start of the whole assignment, not of its details of the date. So it loads again after
        // a change.
        String next = api.get("/imports/csv/assignments?asOf=2026-07-01").body();
        assertTrue(next.contains(",concurrent,,,false,false,40,0,2021-04-01,2027-04-01,"), next);
        JsonNode again = load(api, "assignments", "effectiveDate=2026-07-01", next).get("counts").get("assignments");
        assertEquals(0, again.get("created").asInt() + again.get("changed").asInt(), again.toString());
        assertEquals("import.value_fixed", failure(api, "assignments", "effectiveDate=2026-07-01",
                "id,validFrom\r\n" + concurrent + ",2026-06-01\r\n"));
    }

    @Test
    void anExportOfPeopleLoadsAgainWithNoChange() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv-export-people");
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"desk\", "
                + "\"type\": \"STRING\"}").statusCode());
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"languages\", "
                + "\"type\": \"STRING\", \"multiple\": true}").statusCode());
        String hanako = Api.json(api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"山田 花子\", \"phoneticName\": \"やまだ はなこ\"}, \"en\": {\"name\": \"Hanako Yamada\", "
                + "\"phoneticName\": \"HY\"}}, \"attributes\": {\"desk\": \"3F, east\", "
                + "\"languages\": [\"ja\", \"en\"]}}")).get("id").asText();
        assertEquals(201, api.post("/people", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"Taro\"}}, \"externalId\": \"P-1\", \"source\": \"hr\"}")
                .statusCode());
        // A person who takes the name of the Keycloak user, and keeps the link.
        String user = realm.user("linked", "Ichiro", "Suzuki");
        assertEquals(201, api.post("/people", "{\"userId\": \"" + user + "\"}").statusCode());

        for (String header : List.of("en", "ja")) {
            HttpResponse<String> exported = api.get("/imports/csv/people?header=" + header);
            assertEquals(200, exported.statusCode(), exported.body());
            assertTrue(exported.body().startsWith("﻿" + (header.equals("en")
                    ? "id,externalId,source,primaryLanguage,name,phoneticName,name:en,phoneticName:en,attributes."
                    : "ID,外部ID,ソース,主言語,名称,よみがな,名称:en,よみがな:en,attributes.")), exported.body());
            assertTrue(exported.body().contains(",ja,山田 花子,やまだ はなこ,Hanako Yamada,HY,"), exported.body());
            assertTrue(exported.body().contains("\"3F, east\""), exported.body());
            assertTrue(exported.body().contains("\"[\"\"ja\"\",\"\"en\"\"]\""), exported.body());

            JsonNode counts = load(api, "people", "effectiveDate=2026-05-01", exported.body()).get("counts")
                    .get("people");
            assertEquals(0, counts.get("created").asInt() + counts.get("changed").asInt(), counts.toString());
        }
        String renamed = api.get("/imports/csv/people").body().replace("山田 花子", "山田 はな子");
        load(api, "people", "effectiveDate=2026-06-01", renamed);
        assertEquals("山田 はな子", Api.json(api.get("/people/" + hanako)).get("name").asText());
        assertEquals(user, Api.json(api.get("/people?userId=" + user)).get(0).get("userId").asText());
        // A new person cannot take the user of a stored person.
        assertEquals("import.user_taken", failure(api, "people", "effectiveDate=2026-06-01",
                "externalId,userId\nP-9," + user + "\n"));
    }

    @Test
    void loadsOrgUnitsAndStaffFromCsvFilesWithJapaneseHeaders() throws Exception {
        TestRealm realm = TestRealm.withEmployer("csv");
        Api api = realm.api;
        String nurse = realm.user("nurse");

        load(api, "ref-items", "effectiveDate=2026-04-01", """
                ﻿外部ID,種類,コード,主言語,名称
                UT-hospital,UNIT_TYPE,hospital,ja,病院
                OC-nurse,OCCUPATION,nurse,ja,看護師
                ET-part-time,EMPLOYMENT_TYPE,part-time,ja,非常勤
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
        assertEquals(List.of("法人", "X病院", "看護部", "1病棟, 東館"), Api.names(tree));
        assertEquals("X Hospital", tree.get(1).get("names").get("en").get("name").asText());
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

        JsonNode dryRun = load(api, "org-units", "effectiveDate=2026-04-01&dryRun=true",
                "外部ID,主言語,名称\nH,ja,X病院\n");
        assertTrue(dryRun.get("dryRun").asBoolean());
        assertFalse(dryRun.get("applied").asBoolean());
        assertEquals(1, dryRun.get("counts").get("orgUnits").get("created").asInt());

        HttpResponse<String> bad = api.withAcceptLanguage("ja").postBytes(
                "/imports/csv/org-units?effectiveDate=2026-04-01", "text/csv; charset=UTF-8", """
                        外部ID,主言語,名称,表示順,親の外部ID
                        H,ja,X病院,1,

                        A,ja,A課,多い,H
                        B,ja,B課,2,NOPE
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
                "text/csv; charset=Shift_JIS", "外部ID,主言語,名称\nH,ja,X病院\n".getBytes(Charset.forName("Shift_JIS")));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("X病院"), Api.names(api.tree("asOf=2026-04-01")));

        HttpResponse<String> unknown = api.postBytes("/imports/csv/org-units?effectiveDate=2026-04-01",
                "text/csv; charset=no-such-charset", "a\n1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("csv.bad_charset", Api.json(unknown).get("error").asText());
    }

    @Test
    void aCsvFileOfATransferEndsTheOldEmploymentInAnyOrder() throws Exception {
        TestRealm realm = TestRealm.create("csv-continues");
        Api api = realm.api;
        realm.user("sato");
        realm.user("suzuki");
        load(api, "ref-items", "effectiveDate=2020-04-01", """
                外部ID,種類,コード,主言語,名称,法人
                UT-company,UNIT_TYPE,company,ja,会社,はい
                """);
        load(api, "org-units", "effectiveDate=2020-04-01&source=sheet", """
                外部ID,主言語,名称,組織種別
                X,ja,医療法人X会,company
                Y,ja,株式会社Yサービス,company
                """);
        load(api, "employments", "effectiveDate=2020-04-01&source=sheet", """
                外部ID,ユーザー名,雇用主の外部ID
                E1,sato,X
                E3,suzuki,X
                """);
        String e3 = Api.json(api.get("/external-ids/lookup?resourceType=EMPLOYMENT&source=sheet&externalId=E3"))
                .get("resourceId").asText();

        // The row that ends the old employment of Sato comes after the row of the new one. Suzuki moves with no row
        // that ends the old employment, and the error has the line of the new one.
        String transfer = """
                外部ID,ユーザー名,雇用主の外部ID,終了日,引き継ぎ元の雇用の外部ID
                E2,sato,Y,,E1
                E1,sato,X,2026-04-01,
                E4,suzuki,Y,,E3
                """;
        HttpResponse<String> unended = api.postBytes("/imports/csv/employments?effectiveDate=2026-04-01&source=sheet",
                "text/csv; charset=UTF-8", transfer.getBytes(StandardCharsets.UTF_8));

        assertEquals(400, unended.statusCode(), unended.body());
        JsonNode errors = Api.json(unended).get("errors");
        assertEquals(1, errors.size(), unended.body());
        assertEquals("employment.continues_holds", errors.get(0).get("error").asText());
        assertEquals(4, errors.get(0).get("line").asInt());
        assertEquals(e3, errors.get(0).get("params").get("id").asText());
        JsonNode moved = load(api, "employments", "effectiveDate=2026-04-01&source=sheet",
                transfer.replace("E4,suzuki,Y,,E3\n", "")).get("counts").get("employments");
        assertEquals(1, moved.get("created").asInt(), moved.toString());
        assertEquals(1, moved.get("changed").asInt(), moved.toString());
    }

    @Test
    void aCsvFileNamesTheEmployerAndMarksAUnitTypeAsALegalEntity() throws Exception {
        TestRealm realm = TestRealm.create("csv-employers");
        Api api = realm.api;
        String nurse = realm.user("nurse");

        load(api, "ref-items", "effectiveDate=2026-04-01", """
                外部ID,種類,コード,主言語,名称,法人
                UT-company,UNIT_TYPE,company,ja,会社,はい
                UT-ward,UNIT_TYPE,ward,ja,病棟,
                """);
        load(api, "org-units", "effectiveDate=2026-04-01&source=sheet", """
                外部ID,主言語,名称,組織種別
                X,ja,医療法人X会,company
                Y,ja,株式会社Yサービス,company
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
