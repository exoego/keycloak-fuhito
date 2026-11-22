package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An earlier end of an employment removes each assignment of it that would start on the end or later. That holds
 * also for an assignment that ends on the start of another assignment of the person.
 */
class EndRemovalsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A person at company X has an assignment from 2026-04-01 and moves to company Y on 2027-04-01. The copy of the
     * assignment at company Y starts on the end of it.
     *
     * @param realm the realm
     * @param old the ID of the employment at company X
     * @param assignment the ID of the assignment at company X
     * @param copy the ID of the assignment at company Y
     */
    private record Moved(TestRealm realm, String old, String assignment, String copy) {
    }

    private static Moved moved(String prefix) throws Exception {
        TestRealm realm = TestRealm.create(prefix);
        Api api = realm.api;
        String x = realm.legalEntity("医療法人X会");
        String y = realm.legalEntity("株式会社Yサービス");
        String old = id(api.post("/employments", "{\"userId\": \"" + realm.user("sato") + "\", \"employerId\": \""
                + x + "\", \"validFrom\": \"2020-04-01\"}"));
        String assignment = id(api.post("/assignments", "{\"employmentId\": \"" + old + "\", \"orgUnitId\": \""
                + unit(api, "病棟") + "\", \"validFrom\": \"2026-04-01\"}"));
        HttpResponse<String> transfer = api.postWithVersion("/employments/" + old + "/transfer",
                "{\"employerId\": \"" + y + "\", \"validFrom\": \"2027-04-01\"}");
        assertEquals(201, transfer.statusCode(), transfer.body());
        JsonNode copy = Api.json(transfer).get("assignments").get(0);
        assertEquals(List.of(assignment), copy.get("endedOnStart").findValuesAsText("assignmentId"));
        return new Moved(realm, old, assignment, copy.get("id").asText());
    }

    private static String unit(Api api, String name) throws Exception {
        return id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name
                + "\"}}, \"validFrom\": \"2020-04-01\"}"));
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    @Test
    void anAssignmentThatEndsOnTheStartOfAnotherCanBeDeleted() throws Exception {
        Moved moved = moved("end-removals-delete");
        Api api = moved.realm().api;

        assertEquals(204, api.deleteWithVersion("/assignments/" + moved.assignment()).statusCode());

        assertEquals(404, api.get("/assignments/" + moved.assignment()).statusCode());
        assertFalse(Api.json(api.get("/assignments/" + moved.copy())).has("endedOnStart"));
    }

    @Test
    void anEarlierEndRemovesAnAssignmentThatEndsOnTheStartOfAnother() throws Exception {
        Moved moved = moved("end-removals-end");
        Api api = moved.realm().api;

        HttpResponse<String> end = api.putWithVersion("/employments/" + moved.old() + "/end",
                "{\"validTo\": \"2026-01-01\"}");

        assertEquals(200, end.statusCode(), end.body());
        assertEquals(404, api.get("/assignments/" + moved.assignment()).statusCode());
        assertFalse(Api.json(api.get("/assignments/" + moved.copy())).has("endedOnStart"));
    }

    @Test
    void anEarlierEndKeepsWhatStartedBeforeItAndItsExportRestores() throws Exception {
        Moved moved = moved("end-removals-kept");
        Api api = moved.realm().api;
        // A move inside the old employment. The end removes both assignments of it.
        String first = id(api.post("/assignments", "{\"employmentId\": \"" + moved.old() + "\", \"orgUnitId\": \""
                + unit(api, "検査科") + "\", \"validFrom\": \"2026-06-01\", \"validTo\": \"2026-09-01\"}"));
        HttpResponse<String> move = api.postWithVersion("/assignments/" + first + "/move", "{\"orgUnitId\": \""
                + unit(api, "外来") + "\", \"validFrom\": \"2026-07-01\"}");
        assertEquals(201, move.statusCode(), move.body());
        String second = Api.json(move).get("started").get("id").asText();

        HttpResponse<String> end = api.putWithVersion("/employments/" + moved.old() + "/end",
                "{\"validTo\": \"2026-05-01\"}");

        assertEquals(200, end.statusCode(), end.body());
        // The assignment that started before the end ends with the employment and stays.
        assertEquals("2026-05-01", Api.json(api.get("/assignments/" + moved.assignment())).get("validTo").asText());
        assertEquals(404, api.get("/assignments/" + first).statusCode());
        assertEquals(404, api.get("/assignments/" + second).statusCode());
        ObjectNode export = RealmExportIT.export(api);
        TestRealm target = TestRealm.create("end-removals-restore");
        target.user("sato");
        moved.realm().remove();
        HttpResponse<String> restored = target.api.post("/restore", JSON.writeValueAsString(export));
        assertEquals(200, restored.statusCode(), restored.body());
        assertEquals(export, RealmExportIT.export(target.api));
    }
}
