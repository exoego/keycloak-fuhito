package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.ImportIT.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// The import, the export, and the restore of employments by an employer outside the realm. EmploymentsIT has the
// writes and the reads of such employments, and CsvImportIT has their CSV files.
class ExternalEmployerIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anImportNamesAnAgencyAndCannotChangeIt() throws Exception {
        TestRealm realm = TestRealm.create("import-agency");
        Api api = realm.api;
        String sato = realm.user("sato");
        realm.user("suzuki");
        String document = """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "refItems": [
                   {"externalId": "UNIT_TYPE.company", "kind": "UNIT_TYPE", "code": "company", "primaryLanguage": "ja", "names": {"ja": {"name": "会社"}}, "legalEntity": true},
                   {"externalId": "AGENCY.staffing", "kind": "EXTERNAL_EMPLOYER", "code": "staffing", "primaryLanguage": "ja", "names": {"ja": {"name": "Yスタッフ"}}},
                   {"externalId": "AGENCY.contracting", "kind": "EXTERNAL_EMPLOYER", "code": "contracting", "primaryLanguage": "ja", "names": {"ja": {"name": "Z請負"}}}
                 ],
                 "orgUnits": [
                   {"externalId": "X", "primaryLanguage": "ja", "names": {"ja": {"name": "医療法人X会"}}, "unitType": "company"},
                   {"externalId": "Y", "primaryLanguage": "ja", "names": {"ja": {"name": "株式会社Yサービス"}}, "unitType": "company"}
                 ],
                 "employments": [
                   {"externalId": "E1", "username": "sato", "staffNumber": "1001", "externalEmployer": "staffing"},
                   {"externalId": "E2", "username": "suzuki", "staffNumber": "1001", "employerExternalId": "X"}
                 ],
                 "assignments": [
                   {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "Y", "primary": true}
                 ]}
                """;

        JsonNode first = ok(api.post("/imports", document));

        assertEquals(2, first.get("counts").get("employments").get("created").asInt());
        JsonNode ofSato = Api.json(api.get("/users/" + sato + "/history")).get("employments").get(0);
        assertEquals("staffing", ofSato.get("externalEmployer").asText());
        assertFalse(ofSato.has("employerId"));
        assertFalse(Api.json(api.get("/users/" + sato + "/assignments?asOf=2026-04-01")).get(0).get("seconded")
                .asBoolean());

        // The same document changes nothing, also when the agency takes no new employments.
        JsonNode again = ok(api.post("/imports", document.replace("\"names\": {\"ja\": {\"name\": \"Yスタッフ\"}}",
                "\"names\": {\"ja\": {\"name\": \"Yスタッフ\"}}, \"active\": false")));
        assertEquals(2, again.get("counts").get("employments").get("unchanged").asInt());

        HttpResponse<String> bad = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [
                   {"externalId": "E1", "username": "sato", "externalEmployer": "contracting"},
                   {"externalId": "E2", "username": "suzuki", "externalEmployer": "staffing"},
                   {"externalId": "E3", "username": "sato", "validFrom": "2027-04-01", "externalEmployer": "staffing"},
                   {"externalId": "E4", "username": "sato", "validFrom": "2027-04-01", "externalEmployer": "nope"},
                   {"externalId": "E5", "username": "sato", "validFrom": "2027-04-01", "externalEmployer": "contracting",
                    "employerExternalId": "X"}
                 ]}
                """);
        assertEquals(400, bad.statusCode(), bad.body());
        assertEquals(List.of("import.employer_mismatch", "import.employer_mismatch",
                "employment.external_employer_inactive", "employment.external_employer_unknown",
                "employment.employer_twice"), Api.json(bad).get("errors").findValuesAsText("error"));
        assertEquals("2", rows(realm, "fuhito_employment"));
    }

    // The source realm is removed before the restore, with its employment by the agency.
    @Test
    void anExportWithAnAgencyLoadsIntoAnEmptyRealmAndGivesTheSameExportAgain() throws Exception {
        TestRealm source = TestRealm.withEmployer("export-agency");
        String sato = source.user("sato");
        String agency = source.refItem("EXTERNAL_EMPLOYER", "staffing", "Yスタッフ");
        String dispatched = Api.json(source.api.post("/employments", "{\"userId\": \"" + sato
                + "\", \"externalEmployer\": \"staffing\", \"staffNumber\": \"S-1\", \"validFrom\": \"2021-04-01\"}"))
                .get("id").asText();
        String direct = Api.json(source.api.post("/employments", "{\"userId\": \"" + sato
                + "\", \"validFrom\": \"2024-04-01\"}")).get("id").asText();

        ObjectNode first = RealmExportIT.export(source.api);
        JsonNode employments = first.get("employments");
        assertEquals(2, employments.size(), employments.toString());
        assertEquals(List.of(agency), employments.findValuesAsText("externalEmployerId"));
        assertEquals(1, employments.findValuesAsText("employerId").size(), employments.toString());
        assertFalse(Api.json(source.api.get("/employments/" + direct)).has("externalEmployer"));

        TestRealm target = TestRealm.create("export-agency-target");
        target.user("sato");
        source.remove();
        HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(first));

        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(first, RealmExportIT.export(target.api));
        JsonNode read = Api.json(target.api.get("/employments/" + dispatched));
        assertEquals("staffing", read.get("externalEmployer").asText());
        assertFalse(read.has("employerId"));
        assertEquals(List.of(dispatched), Api.json(target.api.get("/employments?externalEmployer=staffing"))
                .findValuesAsText("id"));
    }

    @Test
    void aRestoreNeedsTheExternalEmployerInTheExportAndNoLegalEntityBesideIt() throws Exception {
        TestRealm target = TestRealm.create("export-agency-check");
        ObjectNode document = JSON.createObjectNode().put("format", "fuhito.export.v1");
        document.putArray("people").addObject().put("id", "p-9");
        ObjectNode employment = document.putArray("employments").addObject().put("id", "e-9").put("personId", "p-9")
                .put("externalEmployerId", "r-9").put("validFrom", "2020-04-01");

        HttpResponse<String> unknown = target.api.post("/restore", JSON.writeValueAsString(document));
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("restore.reference_unknown", Api.json(unknown).get("error").asText());
        assertEquals("externalEmployerId", Api.json(unknown).get("params").get("field").asText());

        document.putArray("refItems").addObject().put("id", "r-9");
        document.putArray("orgUnits").addObject().put("id", "u-9");
        employment.put("employerId", "u-9");
        HttpResponse<String> twice = target.api.post("/restore", JSON.writeValueAsString(document));
        assertEquals(400, twice.statusCode(), twice.body());
        assertEquals("employment.employer_twice", Api.json(twice).get("error").asText());
        assertEquals(0, Api.json(target.api.get("/export/employments")).size());
    }
}
