package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A transfer checks its values before it writes anything. A transfer of every person of an employer checks them also
 * when nobody moves.
 */
class TransferChecksIT {

    private static TestRealm realm;
    private static Api api;
    private static String company;
    private static String dormant;
    private static String corporation;
    private static String later;
    private static String ward;

    @BeforeAll
    static void createRealm() throws Exception {
        realm = TestRealm.create("transfer-checks");
        api = realm.api;
        company = realm.legalEntity("株式会社Yサービス");
        dormant = realm.legalEntity("休眠会社");
        corporation = realm.legalEntity("医療法人X会");
        later = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"新会社\"}}, \"unitType\": \"legal-entity\", \"validFrom\": \"2030-04-01\"}")).get("id").asText();
        ward = created(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病棟\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        realm.refItem("END_REASON", "merger", "合併");
        String closed = realm.refItem("END_REASON", "closed", "使わない理由");
        assertEquals(200, api.putWithVersion("/ref-items/" + closed, "{\"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"使わない理由\"}}, \"active\": false}").statusCode());
        created(api.post("/employments", "{\"userId\": \"" + realm.user("sato") + "\", \"employerId\": \"" + company
                + "\", \"validFrom\": \"2020-04-01\"}"));
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String merger(String from, String to, String endReason, boolean endEmployer) {
        return "{\"fromEmployerId\": \"" + from + "\", \"toEmployerId\": \"" + to + "\", \"validFrom\": "
                + "\"2027-04-01\", \"endReason\": \"" + endReason + "\", \"endEmployer\": " + endEmployer + "}";
    }

    // Each bad value of a merger from one employer, with the code of its error. The old employer must not end.
    private static Map<String, String> badValues(String from) {
        Map<String, String> requests = new HashMap<>();
        for (boolean endEmployer : List.of(false, true)) {
            requests.put(merger(from, "nope", "merger", endEmployer), "employment.employer_unknown");
            requests.put(merger(from, later, "merger", endEmployer), "employment.employer_unknown");
            requests.put(merger(from, ward, "merger", endEmployer), "employment.employer_not_legal_entity");
            requests.put(merger(from, from, "merger", endEmployer), "employment.same_employer");
            requests.put(merger(from, corporation, "nope", endEmployer), "ref_item.unknown");
            requests.put(merger(from, corporation, "closed", endEmployer), "ref_item.unknown");
        }
        return requests;
    }

    private static void failsAndWritesNothing(Map<String, String> requests) throws Exception {
        ObjectNode before = RealmExportIT.export(api);
        Map<String, String> expected = new TreeMap<>();
        Map<String, String> actual = new TreeMap<>();
        for (var request : requests.entrySet()) {
            HttpResponse<String> response = api.post("/employments/transfers", request.getKey());
            expected.put(request.getKey(), "400 " + request.getValue());
            actual.put(request.getKey(), response.statusCode() + " " + Api.json(response).path("error").asText());
        }
        assertEquals(expected, actual);
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aMergerThatMovesNobodyFailsOnABadValueAndWritesNothing() throws Exception {
        Map<String, String> requests = badValues(dormant);
        for (boolean endEmployer : List.of(false, true)) {
            requests.put(merger("nope", corporation, "merger", endEmployer), "employment.employer_unknown");
            requests.put(merger(later, corporation, "merger", endEmployer), "employment.employer_unknown");
            requests.put(merger(ward, corporation, "merger", endEmployer), "employment.employer_not_legal_entity");
        }
        failsAndWritesNothing(requests);
    }

    @Test
    void aMergerThatMovesPeopleFailsOnTheSameValues() throws Exception {
        failsAndWritesNothing(badValues(company));
    }

    @Test
    void aGoodMergerThatMovesNobodyStillSucceeds() throws Exception {
        String empty = TestRealm.legalEntity(api, "空の会社");
        ObjectNode before = RealmExportIT.export(api);

        JsonNode nobody = created(api.post("/employments/transfers", merger(empty, corporation, "merger", false)));
        assertEquals(0, nobody.get("transferred").asInt());
        assertFalse(nobody.has("endedEmployer"));
        assertEquals(before, RealmExportIT.export(api));

        // The old employer still ends, as the request asks.
        JsonNode ended = created(api.post("/employments/transfers", merger(empty, corporation, "merger", true)));
        assertEquals(0, ended.get("transferred").asInt());
        assertEquals("2027-04-01", ended.get("endedEmployer").get("validTo").asText());
    }

    private static String closingOn(Api on, String name, String validTo) throws Exception {
        String id = TestRealm.legalEntity(on, name);
        assertEquals(200, on.putWithVersion("/org-units/" + id + "/end", "{\"validTo\": \"" + validTo + "\"}")
                .statusCode());
        return id;
    }

    private static String employment(Api on, String userId, String employerId, String validFrom, String validTo)
            throws Exception {
        return created(on.post("/employments", "{\"userId\": \"" + userId + "\", \"employerId\": \"" + employerId
                + "\", \"validFrom\": \"" + validFrom + "\""
                + (validTo == null ? "" : ", \"validTo\": \"" + validTo + "\"") + "}")).get("id").asText();
    }

    private static String transfer(String employerId) {
        return "{\"employerId\": \"" + employerId + "\", \"validFrom\": \"2027-04-01\"}";
    }

    @Test
    void aTransferToAnEmployerThatEndsFirstFailsAndWritesNothing() throws Exception {
        String hospital = TestRealm.legalEntity(api, "X病院");
        String closing = closingOn(api, "閉鎖予定の会社", "2030-04-01");
        // The first employment ends in time, so a merger fails only on a later one.
        employment(api, realm.user("in-time"), hospital, "2019-04-01", "2030-04-01");
        String open = employment(api, realm.user("open"), hospital, "2020-04-01", null);
        String late = employment(api, realm.user("late"), hospital, "2020-04-01", "2031-04-01");
        ObjectNode before = RealmExportIT.export(api);

        Map<String, String> expected = new TreeMap<>();
        Map<String, String> actual = new TreeMap<>();
        Map<String, HttpResponse<String>> responses = Map.of(
                "open", api.postWithVersion("/employments/" + open + "/transfer", transfer(closing)),
                "late", api.postWithVersion("/employments/" + late + "/transfer", transfer(closing)),
                "merger", api.post("/employments/transfers", merger(hospital, closing, "merger", false)));
        for (var response : responses.entrySet()) {
            expected.put(response.getKey(), "409 employment.employer_ends " + closing + " 2030-04-01");
            JsonNode error = Api.json(response.getValue());
            actual.put(response.getKey(), response.getValue().statusCode() + " " + error.path("error").asText() + " "
                    + error.path("params").path("employerId").asText() + " "
                    + error.path("params").path("date").asText());
        }
        assertEquals(expected, actual);
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aTransferToAnEmployerThatHoldsLongEnoughWorksAndItsExportRestores() throws Exception {
        TestRealm scratch = TestRealm.create("transfer-checks-in-time");
        try {
            Api on = scratch.api;
            String hospital = TestRealm.legalEntity(on, "X病院");
            String clinic = TestRealm.legalEntity(on, "Xクリニック");
            String closing = closingOn(on, "閉鎖予定の会社", "2030-04-01");
            scratch.refItem("END_REASON", "merger", "合併");
            String one = employment(on, scratch.user("one"), hospital, "2020-04-01", "2030-04-01");
            employment(on, scratch.user("early"), clinic, "2020-04-01", "2029-04-01");
            employment(on, scratch.user("last-day"), clinic, "2020-04-01", "2030-04-01");

            JsonNode transfer = created(on.postWithVersion("/employments/" + one + "/transfer", transfer(closing)));
            assertEquals(closing, transfer.get("started").get("employerId").asText());
            assertEquals("2030-04-01", transfer.get("started").get("validTo").asText());
            JsonNode merger = created(on.post("/employments/transfers", merger(clinic, closing, "merger", false)));
            assertEquals(2, merger.get("transferred").asInt());

            ObjectNode before = RealmExportIT.export(on);
            HttpResponse<String> restored = on.post("/restore?replace=true", before.toString());
            assertEquals(200, restored.statusCode(), restored.body());
            assertEquals(before, RealmExportIT.export(on));
        } finally {
            scratch.remove();
        }
    }
}
