package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportIT {

    private static final String HOSPITAL = """
            {"effectiveDate": "2026-04-01", "source": "sheet", %s
             "refItems": [
               {"kind": "UNIT_TYPE", "code": "hospital", "name": "病院", "names": {"en": "Hospital"}},
               {"kind": "UNIT_TYPE", "code": "department", "name": "部"}
             ],
             "orgUnits": [
               {"externalId": "W1", "name": "1病棟", "phoneticName": "いちびょうとう", "parentExternalId": "N"},
               {"externalId": "N", "name": "看護部", "unitType": "department", "parentExternalId": "H", "displayOrder": 2},
               {"externalId": "M", "name": "診療部", "unitType": "department", "parentExternalId": "H", "displayOrder": 1},
               {"externalId": "H", "name": "X病院", "unitType": "hospital", "code": "x-hospital",
                "validFrom": "1998-04-01", "attributes": {"beds": 120}}
             ]}
            """;

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static List<String> names(Api api, String asOf) throws Exception {
        return Api.json(api.get("/org-units?asOf=" + asOf)).findValuesAsText("name");
    }

    private static String rows(TestRealm realm, String table) throws Exception {
        return KeycloakEnvironment.sql("select count(*) from " + table + " where realm_id = '" + realm.id() + "'");
    }

    @Test
    void importsAHospitalThenTheSameDocumentThenAChangedOne() throws Exception {
        TestRealm realm = TestRealm.create("import");
        Api api = realm.api;

        JsonNode first = ok(api.post("/imports", HOSPITAL.formatted("")));

        assertTrue(first.get("applied").asBoolean());
        assertFalse(first.get("dryRun").asBoolean());
        assertEquals(0, first.get("errors").size());
        assertEquals(2, first.get("counts").get("refItems").get("created").asInt());
        assertEquals(4, first.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(List.of("X病院", "診療部", "看護部", "1病棟"), names(api, "2026-04-01"));
        assertEquals(List.of("X病院"), names(api, "1998-04-01"));
        JsonNode root = Api.json(api.get("/org-units?roots=true&asOf=2026-04-01")).get(0);
        assertEquals("H", root.get("externalId").asText());
        assertEquals("sheet", root.get("source").asText());
        assertEquals("hospital", root.get("unitType").asText());
        assertEquals("x-hospital", root.get("code").asText());
        assertEquals(120, root.get("attributes").get("beds").asInt());

        JsonNode again = ok(api.post("/imports", HOSPITAL.formatted("")));
        assertTrue(again.get("applied").asBoolean());
        assertEquals(2, again.get("counts").get("refItems").get("unchanged").asInt());
        assertEquals(4, again.get("counts").get("orgUnits").get("unchanged").asInt());
        assertEquals(0, again.get("counts").get("orgUnits").get("changed").asInt());
        assertEquals("4", rows(realm, "fuhito_org_unit_version"));

        // In April 2027 the ward gets a new name and moves, and a new department appears.
        JsonNode changed = ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "source": "sheet", "orgUnits": [
                  {"externalId": "W1", "name": "東病棟", "phoneticName": "ひがしびょうとう", "parentExternalId": "M"},
                  {"externalId": "R", "name": "リハビリ部", "unitType": "department", "parentExternalId": "H", "displayOrder": 3},
                  {"externalId": "N", "name": "看護部", "unitType": "department", "parentExternalId": "H", "displayOrder": 2}
                ]}
                """));
        assertEquals(1, changed.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(1, changed.get("counts").get("orgUnits").get("changed").asInt());
        assertEquals(1, changed.get("counts").get("orgUnits").get("unchanged").asInt());
        assertEquals(List.of("X病院", "診療部", "看護部", "1病棟"), names(api, "2027-03-31"));
        assertEquals(List.of("X病院", "診療部", "東病棟", "看護部", "リハビリ部"), names(api, "2027-04-01"));
    }

    @Test
    void aDryRunChecksEverythingAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("import-dry-run");

        JsonNode report = ok(realm.api.post("/imports", HOSPITAL.formatted("\"dryRun\": true,")));

        assertTrue(report.get("dryRun").asBoolean());
        assertFalse(report.get("applied").asBoolean());
        assertEquals(4, report.get("counts").get("orgUnits").get("created").asInt());
        assertEquals(0, report.get("errors").size());
        for (String table : List.of("fuhito_ref_item", "fuhito_org_unit", "fuhito_org_unit_version",
                "fuhito_org_unit_link", "fuhito_hierarchy", "fuhito_change_log")) {
            assertEquals("0", rows(realm, table), table);
        }
        assertEquals(List.of(), names(realm.api, "2026-04-01"));
    }

    @Test
    void badRowsAreAllReportedAndNothingIsWritten() throws Exception {
        TestRealm realm = TestRealm.create("import-errors");

        HttpResponse<String> response = realm.api.withAcceptLanguage("ja").post("/imports", """
                {"effectiveDate": "2026-04-01", "orgUnits": [
                  {"externalId": "H", "name": "X病院"},
                  {"externalId": "A", "name": " ", "parentExternalId": "H"},
                  {"externalId": "B", "name": "B", "parentExternalId": "NOPE"},
                  {"externalId": "C", "name": "C", "parentExternalId": "A"}
                ]}
                """);

        assertEquals(400, response.statusCode(), response.body());
        JsonNode report = Api.json(response);
        assertFalse(report.get("applied").asBoolean());
        assertEquals(List.of("1", "2", "3"), report.get("errors").findValuesAsText("row"));
        assertEquals(List.of("invalid.name", "import.parent_unknown", "import.parent_failed"),
                report.get("errors").findValuesAsText("error"));
        assertEquals("orgUnits", report.get("errors").get(0).get("list").asText());
        assertEquals("NOPE", report.get("errors").get(1).get("params").get("parentExternalId").asText());
        assertEquals("外部 ID「NOPE」の親は、取り込みデータにも保存済みデータにもありません。",
                report.get("errors").get(1).get("message").asText());
        assertEquals(1, report.get("counts").get("orgUnits").get("created").asInt());
        assertEquals("0", rows(realm, "fuhito_org_unit"));
        assertEquals("0", rows(realm, "fuhito_change_log"));
    }

    @Test
    void anImportUnderAChangeOrderLogsEveryWrite() throws Exception {
        TestRealm realm = TestRealm.create("import-order");
        Api api = realm.api;
        String order = Api.json(api.post("/change-orders",
                "{\"title\": \"初期データ登録\", \"effectiveDate\": \"2026-04-01\"}")).get("id").asText();

        ok(api.post("/imports?changeOrder=" + order, HOSPITAL.formatted("")));

        JsonNode changes = Api.json(api.get("/change-orders/" + order + "/changes"));
        assertEquals(List.of("FUHITO_REF_ITEM", "FUHITO_REF_ITEM", "FUHITO_ORG_UNIT", "FUHITO_ORG_UNIT",
                "FUHITO_ORG_UNIT", "FUHITO_ORG_UNIT"), changes.findValuesAsText("resourceType"));
        assertEquals(List.of("CREATE", "CREATE", "CREATE", "CREATE", "CREATE", "CREATE"), changes.findValuesAsText("operation"));
        // A parent is written before its children, wherever it is in the document.
        assertEquals(List.of("X病院", "看護部", "1病棟", "診療部"), List.of(
                changes.get(2).get("representation").get("name").asText(),
                changes.get(3).get("representation").get("name").asText(),
                changes.get(4).get("representation").get("name").asText(),
                changes.get(5).get("representation").get("name").asText()));
    }

    @Test
    void rejectsABadDocument() throws Exception {
        TestRealm realm = TestRealm.create("import-bad");

        HttpResponse<String> noDate = realm.api.post("/imports", "{\"orgUnits\": []}");
        assertEquals(400, noDate.statusCode());
        assertEquals("invalid.required", Api.json(noDate).get("error").asText());
        assertEquals(401, new Api(realm.name, null).post("/imports", "{}").statusCode());
    }
}
