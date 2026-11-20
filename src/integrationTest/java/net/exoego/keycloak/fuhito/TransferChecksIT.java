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
 * A transfer of every person of an employer checks its values also when nobody moves.
 */
class TransferChecksIT {

    private static Api api;
    private static String company;
    private static String dormant;
    private static String corporation;
    private static String later;
    private static String ward;

    @BeforeAll
    static void createRealm() throws Exception {
        TestRealm realm = TestRealm.create("transfer-checks");
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
}
