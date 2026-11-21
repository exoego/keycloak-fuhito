package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A key that an import document does not have fails with {@code import.unknown_key}, which names the key with its
 * path, as a CSV column that a list does not have fails. The import would drop such a key of a row.
 */
class UnknownKeyIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SETUP = """
            {"effectiveDate": "2026-04-01",
             "changeOrders": [{"externalId": "O1", "title": "4月の異動"}],
             "refKinds": [{"externalId": "K1", "code": "site", "primaryLanguage": "ja", "names": {"ja": {"name": "拠点"}}}],
             "attributeDefinitions": [{"externalId": "D1", "target": "ORG_UNIT", "name": "beds", "type": "NUMBER"}],
             "refItems": [
               {"externalId": "I1", "kind": "UNIT_ROLE", "code": "approver", "primaryLanguage": "ja",
                "names": {"ja": {"name": "承認者"}}},
               {"externalId": "I2", "kind": "QUALIFICATION", "code": "license", "primaryLanguage": "ja",
                "names": {"ja": {"name": "免許"}}}],
             "hierarchies": [{"externalId": "R1", "code": "project", "name": "プロジェクト"}],
             "orgUnits": [
               {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validFrom": "2020-04-01"},
               {"externalId": "W", "code": "w", "primaryLanguage": "ja", "names": {"ja": {"name": "病棟"}},
                "parentExternalId": "H", "validFrom": "2020-04-01"}],
             "orgUnitParents": [{"hierarchy": "project", "orgUnitExternalId": "W", "parentExternalId": "H"}],
             "people": [
               {"externalId": "P1", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}},
               {"externalId": "P2", "primaryLanguage": "ja", "names": {"ja": {"name": "鈴木"}}}],
             "qualifications": [{"externalId": "Q1", "personExternalId": "P1", "kind": "license", "number": "1"}],
             "employments": [
               {"externalId": "E1", "personExternalId": "P1", "validFrom": "2020-04-01"},
               {"externalId": "E2", "personExternalId": "P2", "validFrom": "2020-04-01"}],
             "unitRoles": [{"externalId": "U1", "employmentExternalId": "E2", "orgUnitExternalId": "W", "role": "approver"}],
             "assignments": [
               {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "W", "validFrom": "2020-04-01"}],
             "reportingLines": [{"externalId": "L1", "assignmentExternalId": "A1", "managerPersonExternalId": "P2"}]}
            """;

    // The status, the error, and the field of a response, or its body when it has no error of the extension.
    private static String failure(Api api, String document) throws Exception {
        HttpResponse<String> response = api.post("/imports", document);
        JsonNode body = Api.json(response);
        String field = body.path("params").path("field").asText(null);
        return response.statusCode() + " " + (field == null ? response.body() : body.get("error").asText() + " " + field);
    }

    private static String rows(String list, String rows) {
        return "{\"effectiveDate\": \"2026-05-01\", \"" + list + "\": [" + rows + "]}";
    }

    @Test
    void aKeyThatARowOfAListDoesNotHaveFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unknown-key");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        String ward = Api.json(api.get("/org-units?asOf=2026-04-01&code=w")).get(0).get("id").asText();
        String externalId = "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + ward
                + "\", \"source\": \"payroll\", \"externalId\": \"X-1\"";
        ok(api.post("/imports", rows("externalIds", externalId + "}")));
        ObjectNode before = RealmExportIT.export(api);

        List<String> failures = new ArrayList<>();
        failures.add(failure(api, rows("changeOrders", "{\"externalId\": \"O1\", \"tilte\": \"5月の異動\"}")));
        failures.add(failure(api, rows("refKinds", "{\"externalId\": \"K1\", \"primaryLangauge\": \"en\"}")));
        failures.add(failure(api, rows("attributeDefinitions", "{\"externalId\": \"D1\", \"requried\": true}")));
        failures.add(failure(api, rows("refItems", "{\"externalId\": \"I1\", \"displayorder\": 3}")));
        failures.add(failure(api, rows("hierarchies", "{\"externalId\": \"R1\", \"nmae\": \"計画\"}")));
        failures.add(failure(api, rows("orgUnits", "{\"externalId\": \"W\", \"parentExternalID\": \"W\"}")));
        failures.add(failure(api, rows("orgUnitParents", "{\"hierarchy\": \"project\", \"orgUnitExternalId\": \"W\", "
                + "\"parentExternalId\": \"H\", \"validFrom\": \"2026-05-01\"}")));
        failures.add(failure(api, rows("people", "{\"externalId\": \"P1\", \"userName\": \"nurse\"}")));
        failures.add(failure(api, rows("qualifications", "{\"externalId\": \"Q1\", \"validfrom\": \"2021-04-01\"}")));
        failures.add(failure(api, rows("employments", "{\"externalId\": \"E2\"}, "
                + "{\"externalId\": \"E1\", \"employerExternalID\": \"L\"}")));
        failures.add(failure(api, rows("unitRoles", "{\"externalId\": \"U1\", \"includeDescendants\": true}")));
        failures.add(failure(api, rows("assignments", "{\"externalId\": \"A1\", \"allocationPercentage\": 50}")));
        failures.add(failure(api, rows("reportingLines", "{\"externalId\": \"L1\", \"managerUserName\": \"x\"}")));
        failures.add(failure(api, rows("externalIds", externalId + ", \"resourceID\": \"" + ward + "\"}")));

        assertEquals(List.of("400 import.unknown_key changeOrders[0].tilte",
                "400 import.unknown_key refKinds[0].primaryLangauge",
                "400 import.unknown_key attributeDefinitions[0].requried",
                "400 import.unknown_key refItems[0].displayorder",
                "400 import.unknown_key hierarchies[0].nmae",
                "400 import.unknown_key orgUnits[0].parentExternalID",
                "400 import.unknown_key orgUnitParents[0].validFrom",
                "400 import.unknown_key people[0].userName",
                "400 import.unknown_key qualifications[0].validfrom",
                "400 import.unknown_key employments[1].employerExternalID",
                "400 import.unknown_key unitRoles[0].includeDescendants",
                "400 import.unknown_key assignments[0].allocationPercentage",
                "400 import.unknown_key reportingLines[0].managerUserName",
                "400 import.unknown_key externalIds[0].resourceID"), failures);
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aKeyOfTheDocumentOrOfTheNamesThatTheyDoNotHaveFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unknown-key-document");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);

        List<String> failures = new ArrayList<>();
        // A key of another list.
        failures.add(failure(api, rows("people", "{\"externalId\": \"P1\", \"orgUnitId\": \"W\"}")));
        // The keys that a row gives as null are no key of the row.
        failures.add(failure(api, rows("orgUnits", "{\"externalId\": \"W\", \"nulls\": [\"code\"]}")));
        failures.add(failure(api, """
                {"effectiveDate": "2026-05-01",
                 "orgunits": [{"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "第1病棟"}}}]}
                """));
        failures.add(failure(api, """
                {"effectiveDate": "2026-05-01", "dryrun": true,
                 "orgUnits": [{"externalId": "W", "names": {"ja": {"name": "第1病棟"}}}]}
                """));
        failures.add(failure(api, rows("orgUnits", "{\"externalId\": \"H\"}, "
                + "{\"externalId\": \"W\", \"names\": {\"ja\": {\"name\": \"第1病棟\", \"shortname\": \"1病\"}}}")));
        failures.add(failure(api, rows("people", "{\"externalId\": \"P1\", \"names\": {\"ja\": {\"kana\": \"やまだ\"}}}")));
        failures.add(failure(api, rows("refItems", "{\"externalId\": \"I1\", \"names\": {\"en\": {\"title\": \"A\"}}}")));
        failures.add(failure(api, rows("refKinds", "{\"externalId\": \"K1\", \"names\": {\"en\": {\"label\": \"Site\"}}}")));
        HttpResponse<String> japanese = api.withAcceptLanguage("ja").post("/imports",
                rows("employments", "{\"externalId\": \"E1\", \"employerExternalID\": \"L\"}"));

        assertEquals(List.of("400 import.unknown_key people[0].orgUnitId",
                "400 import.unknown_key orgUnits[0].nulls",
                "400 import.unknown_key orgunits",
                "400 import.unknown_key dryrun",
                "400 import.unknown_key orgUnits[1].names.ja.shortname",
                "400 import.unknown_key people[0].names.ja.kana",
                "400 import.unknown_key refItems[0].names.en.title",
                "400 import.unknown_key refKinds[0].names.en.label"), failures);
        assertEquals("employments[0].employerExternalID はインポートの文書にないキーです。キーの名前と場所を確かめてください。",
                Api.json(japanese).get("message").asText(), japanese.body());
        assertEquals(before, RealmExportIT.export(api));
    }

    // A restore reads its document as a REST write reads its body, and Keycloak rejects an unknown key there.
    @Test
    void aKeyThatARestoreDocumentDoesNotHaveFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unknown-key-restore");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);

        List<String> failures = new ArrayList<>();
        for (String path : List.of("", "/orgUnits/0", "/orgUnits/0/versions/0", "/orgUnits/0/versions/0/names/ja",
                "/employments/0/terms/0")) {
            ObjectNode document = before.deepCopy();
            ((ObjectNode) document.at(path)).put("unknownKey", "x");
            HttpResponse<String> response = api.post("/restore?replace=true", JSON.writeValueAsString(document));
            failures.add(response.statusCode() + " " + response.body());
        }

        assertEquals(5, failures.stream().filter(failure -> failure.startsWith("400 ")).count(), failures.toString());
        assertEquals(before, RealmExportIT.export(api));
    }
}
