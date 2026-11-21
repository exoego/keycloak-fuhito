package net.exoego.keycloak.fuhito;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A key that a JSON body of the extension does not have fails with {@code invalid.unknown_key}, which names the key
 * with its path, as a CSV column that a list does not have fails. Keycloak would answer with an error that names a
 * Java type and no path, and the import would drop such a key of a row.
 */
class UnknownKeyIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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

    private static String failure(Api api, String document) throws Exception {
        return failure(api.post("/imports", document));
    }

    // The status, the error, and the field of a response, or its body when it has no error of the extension.
    private static String failure(HttpResponse<String> response) throws Exception {
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

        assertEquals(List.of("400 invalid.unknown_key changeOrders[0].tilte",
                "400 invalid.unknown_key refKinds[0].primaryLangauge",
                "400 invalid.unknown_key attributeDefinitions[0].requried",
                "400 invalid.unknown_key refItems[0].displayorder",
                "400 invalid.unknown_key hierarchies[0].nmae",
                "400 invalid.unknown_key orgUnits[0].parentExternalID",
                "400 invalid.unknown_key orgUnitParents[0].validFrom",
                "400 invalid.unknown_key people[0].userName",
                "400 invalid.unknown_key qualifications[0].validfrom",
                "400 invalid.unknown_key employments[1].employerExternalID",
                "400 invalid.unknown_key unitRoles[0].includeDescendants",
                "400 invalid.unknown_key assignments[0].allocationPercentage",
                "400 invalid.unknown_key reportingLines[0].managerUserName",
                "400 invalid.unknown_key externalIds[0].resourceID"), failures);
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

        assertEquals(List.of("400 invalid.unknown_key people[0].orgUnitId",
                "400 invalid.unknown_key orgUnits[0].nulls",
                "400 invalid.unknown_key orgunits",
                "400 invalid.unknown_key dryrun",
                "400 invalid.unknown_key orgUnits[1].names.ja.shortname",
                "400 invalid.unknown_key people[0].names.ja.kana",
                "400 invalid.unknown_key refItems[0].names.en.title",
                "400 invalid.unknown_key refKinds[0].names.en.label"), failures);
        assertEquals("このリクエストの本文はキー employments[0].employerExternalID を受け付けません。キーの名前と場所を確かめてください。"
                + "大文字と小文字は区別されます。", Api.json(japanese).get("message").asText(), japanese.body());
        assertEquals(before, RealmExportIT.export(api));
    }

    // The document is not read yet, so the error has the path in place of a list and an ID of a row.
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
            failures.add(failure(api.post("/restore?replace=true", JSON.writeValueAsString(document))));
        }

        assertEquals(List.of("400 invalid.unknown_key unknownKey",
                "400 invalid.unknown_key orgUnits[0].unknownKey",
                "400 invalid.unknown_key orgUnits[0].versions[0].unknownKey",
                "400 invalid.unknown_key orgUnits[0].versions[0].names.ja.unknownKey",
                "400 invalid.unknown_key employments[0].terms[0].unknownKey"), failures);
        assertEquals(before, RealmExportIT.export(api));
    }

    private static String lookup(Api api, String resourceType, String externalId) throws Exception {
        return Api.json(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    @Test
    void aKeyThatTheBodyOfARestWriteDoesNotHaveFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("unknown-key-rest");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        String hospital = lookup(api, "ORG_UNIT", "H");
        String ward = lookup(api, "ORG_UNIT", "W");
        String person = lookup(api, "PERSON", "P1");
        String employment = lookup(api, "EMPLOYMENT", "E1");
        ObjectNode before = RealmExportIT.export(api);
        String unit = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"新病棟\"%s}}, \"parentId\": \""
                + hospital + "\", \"validFrom\": \"2026-05-01\"%s}";
        String people = "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"佐藤\"%s}}%s}";
        String employments = "{\"personId\": \"" + person + "\", \"validFrom\": \"2026-05-01\"%s}";
        String assignments = "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + ward
                + "\", \"validFrom\": \"2026-05-01\"%s}";
        String changeOrders = "{\"title\": \"5月の異動\", \"effectiveDate\": \"2026-05-01\"%s}";
        String reorganizations = "{\"effectiveDate\": \"2027-04-01\"%s, \"steps\": [{\"op\": \"create\", "
                + "\"ref\": \"a\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"甲\"%s}}, "
                + "\"parentId\": \"" + hospital
                + "\"}, {\"op\": \"change\", \"id\": \"" + ward + "\", \"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"乙\"}}%s}]}";

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.post("/org-units", unit.formatted("", ", \"parentID\": \"" + ward + "\""))));
        failures.add(failure(api.post("/org-units", unit.formatted(", \"shortname\": \"新\"", ""))));
        failures.add(failure(api.putWithVersion("/org-units/" + ward + "/end",
                "{\"validTo\": \"2027-04-01\", \"endReason\": \"closed\"}")));
        failures.add(failure(api.post("/people", people.formatted("", ", \"userName\": \"nurse\""))));
        failures.add(failure(api.post("/people", people.formatted(", \"kana\": \"さとう\"", ""))));
        failures.add(failure(api.post("/employments", employments.formatted(", \"employerExternalId\": \"L\""))));
        failures.add(failure(api.post("/employments", employments.formatted(
                ", \"terms\": [{\"validFrom\": \"2026-05-01\", \"workPercentage\": 50}]"))));
        failures.add(failure(api.post("/assignments", assignments.formatted(", \"allocationPercentage\": 50"))));
        failures.add(failure(api.post("/assignments", assignments.formatted(
                ", \"periods\": [{\"validFrom\": \"2026-05-01\", \"allocationPercentage\": 50}]"))));
        failures.add(failure(api.post("/change-orders", changeOrders.formatted(", \"tilte\": \"5月\""))));
        failures.add(failure(api.post("/change-orders", changeOrders.formatted(
                ", \"externalIds\": [{\"source\": \"hr\", \"externalID\": \"C-1\"}]"))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganizations.formatted(", \"dryRun\": true",
                "", ""))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganizations.formatted("", "",
                ", \"nmae\": \"乙\""))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganizations.formatted("",
                ", \"shortname\": \"甲\"", ""))));
        HttpResponse<String> hierarchy = api.post("/hierarchies", "{\"code\": \"c\", \"name\": \"n\", \"unknown\": 1}");
        HttpResponse<String> japanese = api.withAcceptLanguage("ja").post("/change-orders",
                changeOrders.formatted(", \"tilte\": \"5月\""));

        assertEquals(List.of("400 invalid.unknown_key parentID",
                "400 invalid.unknown_key names.ja.shortname",
                "400 invalid.unknown_key endReason",
                "400 invalid.unknown_key userName",
                "400 invalid.unknown_key names.ja.kana",
                "400 invalid.unknown_key employerExternalId",
                "400 invalid.unknown_key terms[0].workPercentage",
                "400 invalid.unknown_key allocationPercentage",
                "400 invalid.unknown_key periods[0].allocationPercentage",
                "400 invalid.unknown_key tilte",
                "400 invalid.unknown_key externalIds[0].externalID",
                "400 invalid.unknown_key dryRun",
                "400 invalid.unknown_key steps[1].nmae",
                "400 invalid.unknown_key steps[0].names.ja.shortname"), failures);
        assertEquals(400, hierarchy.statusCode(), hierarchy.body());
        assertEquals("{\"error\":\"invalid.unknown_key\",\"params\":{\"field\":\"unknown\"},\"message\":\"The body of "
                + "this request does not take the key unknown. Check the name and the place of the key. The case of "
                + "each letter counts.\"}", hierarchy.body());
        assertEquals("このリクエストの本文はキー tilte を受け付けません。キーの名前と場所を確かめてください。大文字と小文字は区別されます。",
                Api.json(japanese).get("message").asText(), japanese.body());
        assertEquals(before, RealmExportIT.export(api));
        // Without the key, the same bodies write.
        assertEquals(201, api.post("/org-units", unit.formatted("", "")).statusCode());
        assertEquals(201, api.post("/change-orders", changeOrders.formatted("")).statusCode());
        assertEquals(200, api.postWithVersion("/reorganizations", reorganizations.formatted("", "", ""))
                .statusCode());
    }

    // Keycloak gave this error to the extension too, before the extension answered with its own.
    @Test
    void aKeycloakEndpointAnswersAKeyThatItsBodyDoesNotHaveAsBefore() throws Exception {
        TestRealm realm = TestRealm.create("unknown-key-keycloak");
        String token = KEYCLOAK.getKeycloakAdminClient().tokenManager().getAccessTokenString();

        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm.name + "/groups"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"first\", \"unknown\": 1}",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals("400 {\"error\":\"Invalid json representation for GroupRepresentation. Unrecognized field "
                + "\\\"unknown\\\" at line 1 column 31.\"}", response.statusCode() + " " + response.body());
    }
}
