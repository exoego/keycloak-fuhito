package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.lookup;
import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An end reason comes only with the end of an employment, in an import and in REST.
 */
class EndReasonIT {

    private static final String START = """
            {"effectiveDate": "2026-04-01", "source": "sheet",
             "refItems": [{"externalId": "END_REASON.resignation", "kind": "END_REASON", "code": "resignation",
               "primaryLanguage": "ja", "names": {"ja": {"name": "退職"}}}],
             "employments": [
               {"externalId": "E-open", "username": "open"},
               {"externalId": "E-ended", "username": "ended", "validTo": "2027-04-01", "endReason": "resignation"}
             ]}
            """;

    private static TestRealm started(String prefix) throws Exception {
        TestRealm realm = TestRealm.withEmployer(prefix);
        for (String user : List.of("open", "ended", "new", "next")) {
            realm.user(user);
        }
        ok(realm.api.post("/imports", START));
        return realm;
    }

    private static HttpResponse<String> csv(Api api, String file) throws Exception {
        return api.postBytes("/imports/csv/employments?effectiveDate=2026-04-01&source=sheet",
                "text/csv; charset=UTF-8", file.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> errors(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        List<String> errors = new ArrayList<>();
        for (JsonNode error : Api.json(response).get("errors")) {
            errors.add(error.get("row").asInt() + " " + error.get("error").asText() + " "
                    + error.path("params").path("endReason").asText());
        }
        return errors;
    }

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode employment(Api api, String externalId) throws Exception {
        return ok(api.get("/employments/" + lookup(api, "EMPLOYMENT", externalId)));
    }

    @Test
    void aJsonRowWithAnEndReasonAndNoEndFailsAndWritesNothing() throws Exception {
        Api api = started("end-reason-json").api;
        ObjectNode before = RealmExportIT.export(api);

        HttpResponse<String> response = api.withAcceptLanguage("ja").post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet", "employments": [
                  {"externalId": "E-new", "username": "new", "endReason": "resignation"},
                  {"externalId": "E-next", "username": "next", "validTo": null, "endReason": "resignation"},
                  {"externalId": "E-ended", "validTo": null, "endReason": "resignation"},
                  {"externalId": "E-open", "endReason": "resignation"}
                ]}
                """);

        String code = "employment.end_reason_without_end resignation";
        assertEquals(List.of("0 " + code, "1 " + code, "2 " + code, "3 " + code), errors(response));
        assertEquals("終了理由「resignation」は validTo に終了日があるときだけ指定できます。",
                Api.json(response).get("errors").get(0).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aCsvRowWithAnEndReasonAndNoEndFailsAndWritesNothing() throws Exception {
        Api api = started("end-reason-csv").api;
        ObjectNode before = RealmExportIT.export(api);

        HttpResponse<String> response = csv(api, """
                externalId,username,validTo,endReason
                E-new,new,,resignation
                E-next,next,#CLEAR,resignation
                E-ended,,#CLEAR,resignation
                E-open,,,resignation
                """);

        String code = "employment.end_reason_without_end resignation";
        assertEquals(List.of("0 " + code, "1 " + code, "2 " + code, "3 " + code), errors(response));
        assertEquals("The end reason \"resignation\" needs an end date in validTo.",
                Api.json(response).get("errors").get(0).get("message").asText());
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void anEndKeepsItsReasonAndARowThatRemovesTheEndRemovesTheReason() throws Exception {
        Api api = started("end-reason-ok").api;

        ok(api.post("/imports", """
                {"effectiveDate": "2026-04-01", "source": "sheet", "employments": [
                  {"externalId": "E-new", "username": "new", "validTo": "2027-04-01", "endReason": "resignation"},
                  {"externalId": "E-open", "validTo": "2027-04-01", "endReason": "resignation"},
                  {"externalId": "E-ended", "validTo": null}
                ]}
                """));
        ok(csv(api, """
                externalId,username,validTo,endReason
                E-next,next,2027-04-01,resignation
                E-open,,#CLEAR,
                """));

        for (String ended : List.of("E-new", "E-next")) {
            JsonNode employment = employment(api, ended);
            assertEquals("2027-04-01", employment.get("validTo").asText(), employment.toString());
            assertEquals("resignation", employment.get("endReason").asText(), employment.toString());
        }
        for (String open : List.of("E-open", "E-ended")) {
            JsonNode employment = employment(api, open);
            assertFalse(employment.has("validTo"), employment.toString());
            assertFalse(employment.has("endReason"), employment.toString());
        }
    }

    @Test
    void aRestEndNeedsADateAndOnlyTheEndOfAnEmploymentTakesAReason() throws Exception {
        TestRealm realm = started("end-reason-rest");
        Api api = realm.api;
        String open = lookup(api, "EMPLOYMENT", "E-open");
        String unit = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"総務課\"}}, \"validFrom\": \"2026-04-01\"}"));
        String empty = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": "
                + "{\"name\": \"庶務課\"}}, \"validFrom\": \"2026-04-01\"}"));
        String assignment = created(api.post("/assignments", "{\"employmentId\": \"" + open + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}"));
        String line = created(api.post("/reporting-lines", "{\"assignmentId\": \"" + assignment
                + "\", \"managerUserId\": \"" + realm.user("manager") + "\", \"validFrom\": \"2026-04-01\"}"));
        realm.refItem("UNIT_ROLE", "safety", "安全管理者");
        String role = created(api.post("/unit-roles", "{\"employmentId\": \"" + open + "\", \"orgUnitId\": \""
                + unit + "\", \"role\": \"safety\", \"validFrom\": \"2026-04-01\"}"));
        ObjectNode before = RealmExportIT.export(api);

        for (String body : List.of("{\"endReason\": \"resignation\"}",
                "{\"validTo\": null, \"endReason\": \"resignation\"}")) {
            HttpResponse<String> response = api.putWithVersion("/employments/" + open + "/end", body);
            assertEquals(400, response.statusCode(), response.body());
            assertEquals("invalid.required", Api.json(response).get("error").asText());
            assertEquals("validTo", Api.json(response).get("params").get("field").asText());
        }
        for (String path : List.of("/unit-roles/" + role, "/reporting-lines/" + line, "/assignments/" + assignment,
                "/org-units/" + empty)) {
            HttpResponse<String> response = api.putWithVersion(path + "/end",
                    "{\"validTo\": \"2027-04-01\", \"endReason\": \"resignation\"}");
            assertEquals(400, response.statusCode(), path + " " + response.body());
            assertTrue(response.body().contains("Unrecognized field \\\"endReason\\\""), response.body());
        }
        assertEquals(before, RealmExportIT.export(api));
    }
}
