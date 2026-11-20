package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An earlier end of an employment removes each assignment of it that would start on the end or later. It does not
 * remove one that another assignment continues, as the delete of that assignment does not.
 */
class EndRemovalsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A person at company X has an assignment from 2026-04-01 and moves to company Y on 2027-04-01. The copy of the
     * assignment at company Y continues it.
     *
     * @param realm the realm
     * @param x the ID of company X
     * @param old the ID of the employment at company X
     * @param assignment the ID of the assignment at company X
     */
    private record Moved(TestRealm realm, String x, String old, String assignment) {
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
        assertEquals(assignment, Api.json(transfer).get("assignments").get(0).get("continuesAssignmentId").asText());
        return new Moved(realm, x, old, assignment);
    }

    private static String unit(Api api, String name) throws Exception {
        return id(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name
                + "\"}}, \"validFrom\": \"2020-04-01\"}"));
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    // What the delete of the assignment that the copy continues answers.
    private static JsonNode deleteError(Moved moved) throws Exception {
        HttpResponse<String> delete = moved.realm().api.deleteWithVersion("/assignments/" + moved.assignment());
        assertEquals(409, delete.statusCode(), delete.body());
        JsonNode error = Api.json(delete);
        assertEquals("assignment.in_use", error.get("error").asText(), delete.body());
        assertEquals(JSON.valueToTree(Map.of("id", moved.assignment(), "uses", "continuation")), error.get("params"));
        return error;
    }

    @Test
    void anEarlierEndThatWouldRemoveAContinuedAssignmentFailsAsItsDeleteAndWritesNothing() throws Exception {
        Moved moved = moved("end-removals-end");
        Api api = moved.realm().api;
        JsonNode delete = deleteError(moved);
        ObjectNode before = RealmExportIT.export(api);

        HttpResponse<String> end = api.putWithVersion("/employments/" + moved.old() + "/end",
                "{\"validTo\": \"2026-01-01\"}");

        assertEquals(409, end.statusCode(), end.body());
        assertEquals(delete, Api.json(end));
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void anImportRowThatWouldRemoveAContinuedAssignmentFailsTheSameWay() throws Exception {
        Moved moved = moved("end-removals-import");
        Api api = moved.realm().api;
        JsonNode delete = deleteError(moved);
        ObjectNode before = RealmExportIT.export(api);

        HttpResponse<String> imported = api.post("/imports", "{\"effectiveDate\": \"2025-04-01\", \"source\": "
                + "\"sheet\", \"employments\": [{\"id\": \"" + moved.old() + "\", \"validTo\": \"2026-01-01\"}]}");

        assertEquals(400, imported.statusCode(), imported.body());
        JsonNode errors = Api.json(imported).get("errors");
        assertEquals(1, errors.size(), errors.toString());
        assertEquals(List.of("employments", "0", delete.get("error").asText()), List.of(
                errors.get(0).get("list").asText(), errors.get(0).get("row").asText(),
                errors.get(0).get("error").asText()));
        assertEquals(delete.get("params"), errors.get(0).get("params"));
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aTransferOrAMergerThatWouldRemoveAContinuedAssignmentFailsTheSameWay() throws Exception {
        for (boolean merger : List.of(true, false)) {
            Moved moved = moved("end-removals-transfer");
            Api api = moved.realm().api;
            String z = moved.realm().legalEntity("株式会社Zケア");
            JsonNode delete = deleteError(moved);
            ObjectNode before = RealmExportIT.export(api);

            HttpResponse<String> response = merger
                    ? api.post("/employments/transfers", "{\"fromEmployerId\": \"" + moved.x() + "\", "
                            + "\"toEmployerId\": \"" + z + "\", \"validFrom\": \"2026-01-01\", \"endEmployer\": true}")
                    : api.postWithVersion("/employments/" + moved.old() + "/transfer", "{\"employerId\": \"" + z
                            + "\", \"validFrom\": \"2026-01-01\"}");

            assertEquals(409, response.statusCode(), response.body());
            assertEquals(delete, Api.json(response));
            assertEquals(before, RealmExportIT.export(api));
        }
    }

    @Test
    void anEarlierEndThatRemovesNoContinuedAssignmentWorksAndItsExportRestores() throws Exception {
        Moved moved = moved("end-removals-kept");
        Api api = moved.realm().api;
        // A move inside the old employment. The end removes both assignments, so nothing that stays names the first.
        String first = id(api.post("/assignments", "{\"employmentId\": \"" + moved.old() + "\", \"orgUnitId\": \""
                + unit(api, "検査科") + "\", \"validFrom\": \"2026-06-01\", \"validTo\": \"2026-09-01\"}"));
        HttpResponse<String> move = api.postWithVersion("/assignments/" + first + "/move", "{\"orgUnitId\": \""
                + unit(api, "外来") + "\", \"validFrom\": \"2026-07-01\"}");
        assertEquals(201, move.statusCode(), move.body());
        String second = Api.json(move).get("started").get("id").asText();

        HttpResponse<String> end = api.putWithVersion("/employments/" + moved.old() + "/end",
                "{\"validTo\": \"2026-05-01\"}");

        assertEquals(200, end.statusCode(), end.body());
        // The assignment that the copy continues ends with the employment and stays.
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
