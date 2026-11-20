package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource that keeps no system takes {@code source} only as the system of {@code externalId}. A create with one of
 * them and not the other fails and writes nothing. A row of an import with {@code source} and neither {@code id} nor
 * {@code externalId} fails too.
 */
class SourceWithNoExternalIdIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"拠点\"}}";

    // What the new resources name. The manager is a user with no person yet.
    private record Staff(String unit, String employment, String assignment, String manager) {
    }

    // A kind of resource, the path or the list that makes one, and the values of a new one.
    private record New(String resourceType, String list, String values) {
    }

    private static Staff staff(TestRealm realm) throws Exception {
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        realm.refItem("UNIT_ROLE", "approver", "承認者");
        assertEquals(201, api.post("/ref-kinds", "{\"code\": \"area\", \"primaryLanguage\": \"ja\", \"names\": "
                + "{\"ja\": {\"name\": \"地域\"}}}").statusCode());
        JsonNode staff = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}"));
        return new Staff(unit, staff.get("employment").get("id").asText(), staff.get("assignment").get("id").asText(),
                realm.user("boss"));
    }

    // The status, the code, and the field of the error, or of the error of the first bad row of an import.
    private static String outcome(HttpResponse<String> response) throws Exception {
        JsonNode body = Api.json(response);
        JsonNode error = body.has("errors") ? body.path("errors").path(0) : body;
        return response.statusCode() + " " + error.path("error").asText() + " "
                + error.path("params").path("field").asText();
    }

    private static String with(String body, String fields) {
        return body.substring(0, body.length() - 1) + fields + "}";
    }

    private static HttpResponse<String> csv(Api api, String list, String file) throws Exception {
        return api.postBytes("/imports/csv/" + list + "?effectiveDate=2026-04-01", "text/csv; charset=UTF-8",
                file.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCreateTakesSourceOnlyWithAnId() throws Exception {
        TestRealm realm = TestRealm.withEmployer("source-create");
        Api api = realm.api;
        Staff staff = staff(realm);
        List<New> creates = List.of(
                new New("REF_KIND", "/ref-kinds", "{\"code\": \"site\", " + NAMES + "}"),
                new New("REF_ITEM", "/ref-items", "{\"kind\": \"area\", \"code\": \"tokyo\", " + NAMES + "}"),
                new New("ATTRIBUTE_DEFINITION", "/attribute-definitions", "{\"target\": \"PERSON\", \"name\": "
                        + "\"beds\", \"type\": \"NUMBER\"}"),
                new New("HIERARCHY", "/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"),
                new New("CHANGE_ORDER", "/change-orders", "{\"title\": \"異動\", \"effectiveDate\": \"2026-04-01\"}"),
                new New("UNIT_ROLE", "/unit-roles", "{\"employmentId\": \"" + staff.employment() + "\", "
                        + "\"orgUnitId\": \"" + staff.unit() + "\", \"role\": \"approver\", "
                        + "\"validFrom\": \"2020-04-01\"}"),
                // The create of the line would make a person for the manager.
                new New("REPORTING_LINE", "/reporting-lines", "{\"assignmentId\": \"" + staff.assignment() + "\", "
                        + "\"managerUserId\": \"" + staff.manager() + "\", \"validFrom\": \"2021-04-01\"}"));
        JsonNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (New create : creates) {
            expected.add(create.list() + " 400 invalid.value_fixed source");
            got.add(create.list() + " " + outcome(api.post(create.list(), with(create.values(),
                    ", \"source\": \"hr\""))));
            expected.add(create.list() + " 400 invalid.required source");
            got.add(create.list() + " " + outcome(api.post(create.list(), with(create.values(),
                    ", \"externalId\": \"X\""))));
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        // With an ID, the source names the system of that ID, and the resource keeps no system.
        for (New create : creates) {
            String externalId = "X-" + create.resourceType();
            HttpResponse<String> response = api.post(create.list(), with(create.values(), ", \"source\": \"hr\", "
                    + "\"externalId\": \"" + externalId + "\""));
            assertEquals(201, response.statusCode(), response.body());
            JsonNode created = Api.json(response);
            assertEquals(List.of("hr=" + externalId), Api.externalIds(created), create.list());
            assertTrue(created.path("source").isMissingNode(), create.list());
        }
    }

    @Test
    void aRowOfAnImportNeedsAnIdOrAnExternalIdBesideItsSource() throws Exception {
        TestRealm realm = TestRealm.withEmployer("source-import");
        Api api = realm.api;
        Staff staff = staff(realm);
        List<New> rows = List.of(
                new New("REF_KIND", "refKinds", "\"code\": \"site\", " + NAMES),
                new New("ATTRIBUTE_DEFINITION", "attributeDefinitions", "\"target\": \"PERSON\", \"name\": \"beds\", "
                        + "\"type\": \"NUMBER\""),
                new New("REF_ITEM", "refItems", "\"kind\": \"area\", \"code\": \"tokyo\", " + NAMES),
                new New("HIERARCHY", "hierarchies", "\"code\": \"project\", \"name\": \"P\""),
                new New("CHANGE_ORDER", "changeOrders", "\"title\": \"異動\""),
                new New("UNIT_ROLE", "unitRoles", "\"employmentId\": \"" + staff.employment() + "\", "
                        + "\"orgUnitId\": \"" + staff.unit() + "\", \"role\": \"approver\""),
                new New("REPORTING_LINE", "reportingLines", "\"assignmentId\": \"" + staff.assignment() + "\", "
                        + "\"managerUsername\": \"boss\""));
        JsonNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (New row : rows) {
            expected.add(row.list() + " 400 invalid.required externalId");
            got.add(row.list() + " " + outcome(api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \""
                    + row.list() + "\": [{\"source\": \"hr\", " + row.values() + "}]}")));
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        StringBuilder document = new StringBuilder("{\"effectiveDate\": \"2026-04-01\"");
        for (New row : rows) {
            document.append(", \"").append(row.list()).append("\": [{\"source\": \"hr\", \"externalId\": \"X-")
                    .append(row.resourceType()).append("\", ").append(row.values()).append("}]");
        }
        HttpResponse<String> response = api.post("/imports", document.append('}').toString());
        assertEquals(200, response.statusCode(), response.body());
        for (New row : rows) {
            assertEquals(1, Api.json(response).get("counts").get(row.list()).get("created").asInt(), row.list());
            assertEquals(200, api.get("/external-ids/lookup?resourceType=" + row.resourceType()
                    + "&source=hr&externalId=X-" + row.resourceType()).statusCode(), row.list());
        }
    }

    @Test
    void aRowOfACsvFileNeedsAnIdOrAnExternalIdBesideItsSource() throws Exception {
        TestRealm realm = TestRealm.withEmployer("source-csv");
        Api api = realm.api;
        Staff staff = staff(realm);
        List<New> files = List.of(
                new New("REF_KIND", "ref-kinds", "source,code,primaryLanguage,name\r\nhr,site,ja,拠点\r\n"),
                new New("ATTRIBUTE_DEFINITION", "attribute-definitions",
                        "source,target,name,type\r\nhr,PERSON,beds,NUMBER\r\n"),
                new New("REF_ITEM", "ref-items", "source,kind,code,primaryLanguage,name\r\nhr,area,tokyo,ja,東京\r\n"),
                new New("HIERARCHY", "hierarchies", "source,code,name\r\nhr,project,P\r\n"),
                new New("CHANGE_ORDER", "change-orders", "source,title\r\nhr,異動\r\n"),
                new New("UNIT_ROLE", "unit-roles", "source,employmentId,orgUnitId,role\r\nhr," + staff.employment()
                        + "," + staff.unit() + ",approver\r\n"),
                new New("REPORTING_LINE", "reporting-lines", "source,assignmentId,managerUsername\r\nhr,"
                        + staff.assignment() + ",boss\r\n"));
        JsonNode before = RealmExportIT.export(api);

        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (New file : files) {
            expected.add(file.list() + " 400 invalid.required externalId");
            got.add(file.list() + " " + outcome(csv(api, file.list(), file.values())));
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        for (New file : files) {
            String externalId = "X-" + file.resourceType();
            HttpResponse<String> response = csv(api, file.list(), "externalId," + file.values()
                    .replace("\r\nhr,", "\r\n" + externalId + ",hr,"));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(200, api.get("/external-ids/lookup?resourceType=" + file.resourceType()
                    + "&source=hr&externalId=" + externalId).statusCode(), file.list());
        }
    }
}
