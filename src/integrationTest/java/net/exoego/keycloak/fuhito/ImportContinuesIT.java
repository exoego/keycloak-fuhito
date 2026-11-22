package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.lookup;
import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.ImportIT.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImportContinuesIT {

    private static final String TWO_COMPANIES = """
            {"effectiveDate": "2020-04-01", "source": "sheet",
             "refItems": [{"externalId": "UNIT_TYPE.company", "kind": "UNIT_TYPE", "code": "company", "primaryLanguage": "ja", "names": {"ja": {"name": "会社"}}, "legalEntity": true}],
             "orgUnits": [
               {"externalId": "X", "primaryLanguage": "ja", "names": {"ja": {"name": "医療法人X会"}}, "unitType": "company"},
               {"externalId": "Y", "primaryLanguage": "ja", "names": {"ja": {"name": "株式会社Yサービス"}}, "unitType": "company"},
               {"externalId": "K", "primaryLanguage": "ja", "names": {"ja": {"name": "栄養科"}}, "parentExternalId": "X"},
               {"externalId": "M", "primaryLanguage": "ja", "names": {"ja": {"name": "検査科"}}, "parentExternalId": "X"},
               {"externalId": "L", "primaryLanguage": "ja", "names": {"ja": {"name": "給食課"}}, "parentExternalId": "Y"}
             ],
             "employments": [
               {"externalId": "E1", "username": "sato", "employerExternalId": "X"},
               {"externalId": "E3", "username": "suzuki", "employerExternalId": "X"}
             ],
             "assignments": [
               {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "K", "primary": true},
               {"externalId": "A3", "employmentExternalId": "E3", "orgUnitExternalId": "K", "primary": true}
             ]}
            """;

    private static TestRealm twoCompanies(String prefix) throws Exception {
        TestRealm realm = TestRealm.create(prefix);
        realm.user("sato");
        realm.user("suzuki");
        ok(realm.api.post("/imports", TWO_COMPANIES));
        return realm;
    }

    private static List<String> errors(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        List<String> errors = new ArrayList<>();
        for (JsonNode error : Api.json(response).get("errors")) {
            errors.add(error.get("list").asText() + "[" + error.get("row").asInt() + "] " + error.get("error").asText()
                    + " " + error.path("params").path("id").asText());
        }
        return errors;
    }

    @Test
    void aRowFailsWhileTheEmploymentThatItContinuesHasNotEnded() throws Exception {
        TestRealm realm = twoCompanies("import-continues-holds");
        Api api = realm.api;
        String e1 = lookup(api, "EMPLOYMENT", "E1");
        String e3 = lookup(api, "EMPLOYMENT", "E3");
        String a1 = lookup(api, "ASSIGNMENT", "A1");

        // Sato moves to company Y with no row that ends the old employment. The old employment of Suzuki ends a
        // month after the new one starts.
        HttpResponse<String> unended = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [
                   {"externalId": "E2", "username": "sato", "employerExternalId": "Y", "continuesEmploymentExternalId": "E1"},
                   {"externalId": "E3", "username": "suzuki", "employerExternalId": "X", "validTo": "2026-05-01"},
                   {"externalId": "E4", "username": "suzuki", "employerExternalId": "Y", "continuesEmploymentExternalId": "E3"}
                 ]}
                """);

        assertEquals(List.of("employments[0] employment.continues_holds " + e1,
                "employments[2] employment.continues_holds " + e3), errors(unended));
        assertEquals("The employment \"" + e1 + "\" does not end by 2026-04-01, when the employment that continues "
                + "it starts. Its end must be on that date or before.",
                Api.json(unended).get("errors").get(0).get("message").asText());
        assertEquals("2", rows(realm, "fuhito_employment"));

        // The row that ends the old employment of Sato comes after the row of the new one. Suzuki starts at company
        // Y a month after the old employment ends. The old assignments end with the old employments.
        JsonNode moved = ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [
                   {"externalId": "E2", "username": "sato", "employerExternalId": "Y", "continuesEmploymentExternalId": "E1"},
                   {"externalId": "E1", "username": "sato", "employerExternalId": "X", "validTo": "2026-04-01"},
                   {"externalId": "E3", "username": "suzuki", "employerExternalId": "X", "validTo": "2026-04-01"},
                   {"externalId": "E4", "username": "suzuki", "employerExternalId": "Y", "validFrom": "2026-05-01",
                    "continuesEmploymentExternalId": "E3"}
                 ],
                 "assignments": [
                   {"externalId": "A2", "employmentExternalId": "E2", "orgUnitExternalId": "L", "primary": true},
                   {"externalId": "A4", "employmentExternalId": "E4", "orgUnitExternalId": "L", "primary": true,
                    "validFrom": "2026-05-01"}
                 ]}
                """));
        assertEquals(2, moved.get("counts").get("employments").get("created").asInt(), moved.toString());
        assertEquals(2, moved.get("counts").get("employments").get("changed").asInt(), moved.toString());
        assertEquals(2, moved.get("counts").get("assignments").get("created").asInt(), moved.toString());
        assertEquals(e3, ok(api.get("/employments/" + lookup(api, "EMPLOYMENT", "E4"))).get("continuesEmploymentId")
                .asText());
        // The new assignment of Sato starts on the end of the old one. The one of Suzuki starts after a gap.
        assertEquals(List.of(a1), ok(api.get("/assignments/" + lookup(api, "ASSIGNMENT", "A2"))).get("endedOnStart")
                .findValuesAsText("assignmentId"));
        assertFalse(ok(api.get("/assignments/" + lookup(api, "ASSIGNMENT", "A4"))).has("endedOnStart"));

        // A later file cannot open the old employment again.
        HttpResponse<String> reopened = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [{"externalId": "E1", "username": "sato", "employerExternalId": "X", "validTo": "2026-10-01"}]}
                """);
        assertEquals(List.of("employments[0] employment.continues_holds " + e1), errors(reopened));
        assertEquals("2026-04-01", ok(api.get("/employments/" + e1)).get("validTo").asText());
    }

    // An assignment that is not primary may hold on beside the one that a move started.
    @Test
    void aFileCannotOpenAgainWhatATransferEndedButCanOpenAMovedAssignment() throws Exception {
        TestRealm realm = twoCompanies("import-continues-reopen");
        Api api = realm.api;
        ok(api.post("/imports", """
                {"effectiveDate": "2020-04-01", "source": "sheet",
                 "assignments": [{"externalId": "C3", "employmentExternalId": "E3", "orgUnitExternalId": "K"}]}
                """));
        String e1 = lookup(api, "EMPLOYMENT", "E1");
        String c3 = lookup(api, "ASSIGNMENT", "C3");
        assertEquals(201, api.postWithVersion("/employments/" + e1 + "/transfer", "{\"employerId\": \""
                + lookup(api, "ORG_UNIT", "Y") + "\", \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(201, api.postWithVersion("/assignments/" + c3 + "/move", "{\"orgUnitId\": \""
                + lookup(api, "ORG_UNIT", "M") + "\", \"validFrom\": \"2026-04-01\"}").statusCode());

        HttpResponse<String> reopened = api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "employments": [{"externalId": "E1", "username": "sato", "employerExternalId": "X", "validTo": "2026-10-01"}],
                 "assignments": [{"externalId": "C3", "validTo": "2026-10-01"}]}
                """);

        assertEquals(List.of("employments[0] employment.continues_holds " + e1), errors(reopened));
        assertEquals("2026-04-01", ok(api.get("/assignments/" + c3)).get("validTo").asText());
        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet",
                 "assignments": [{"externalId": "C3", "validTo": "2026-10-01"}]}
                """));
        assertEquals("2026-10-01", ok(api.get("/assignments/" + c3)).get("validTo").asText());
    }
}
