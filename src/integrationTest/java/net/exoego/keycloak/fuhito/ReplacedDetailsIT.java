package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.FixedValuesIT.outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PUT of the details of an org unit does not move the unit and does not set the places that apply from above. It
 * takes the parent and the places of any date of the details that it replaces, as a read of such a date has them.
 */
class ReplacedDetailsIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}";

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static ObjectNode readOn(Api api, String path, String date) throws Exception {
        return ok(api.get(path + "?asOf=" + date)).deepCopy();
    }

    @Test
    void anotherParentOrPlaceFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("replaced-details");
        Api api = realm.api;
        created(api.post("/ref-items", "{\"kind\": \"UNIT_TYPE\", \"code\": \"region\", " + NAMES
                + ", \"rollup\": true}"));
        for (String place : List.of("tokyo", "nagoya", "osaka")) {
            realm.refItem("WORK_LOCATION", place, place);
        }
        realm.refItem("COST_CENTER", "c1", "経理");
        realm.refItem("COST_CENTER", "c2", "営業");
        String from2020 = "{" + NAMES + ", \"validFrom\": \"2020-04-01\"";
        String first = created(api.post("/org-units", from2020 + ", \"workLocation\": \"tokyo\", "
                + "\"costCenter\": \"c1\"}"));
        String second = created(api.post("/org-units", from2020 + ", \"workLocation\": \"osaka\"}"));
        String root = created(api.post("/org-units", from2020 + "}"));
        String rollup = created(api.post("/org-units", from2020 + ", \"unitType\": \"region\"}"));
        String unit = "/org-units/" + created(api.post("/org-units", from2020 + ", \"parentId\": \"" + first
                + "\"}"));
        // The unit above changes its work location, and the unit moves, inside the details of 2020-04-01.
        ok(api.putWithVersion("/org-units/" + first, "{" + NAMES + ", \"validFrom\": \"2021-04-01\", "
                + "\"workLocation\": \"nagoya\", \"costCenter\": \"c1\"}"));
        ok(api.putWithVersion(unit + "/parent", "{\"parentId\": \"" + second + "\", \"validFrom\": \"2022-04-01\"}"));
        // The details of 2020-04-01 now end on 2024-04-01, and the unit has its first parent only before 2022-04-01.
        ok(api.putWithVersion(unit, "{" + NAMES + ", \"validFrom\": \"2024-04-01\"}"));
        ObjectNode before = RealmExportIT.export(api);

        assertEquals(List.of("parentId", "parentId", "parentId", "parentId", "parentId", "effectiveWorkLocation",
                        "effectiveWorkLocation", "effectiveWorkLocation", "effectiveCostCenter", "effectiveCostCenter")
                .stream().map(field -> "400 invalid.value_fixed " + field).toList(), List.of(
                outcome(api.put(unit, readOn(api, unit, "2021-01-01").put("parentId", root).toString()), "field"),
                outcome(api.put(unit, readOn(api, unit, "2025-01-01").put("parentId", first).toString()), "field"),
                outcome(api.put(unit, readOn(api, unit, "2023-01-01").put("validFrom", "2023-01-01")
                        .put("parentId", first).toString()), "field"),
                outcome(api.put("/org-units/" + root, readOn(api, "/org-units/" + root, "2021-01-01")
                        .put("parentId", first).toString()), "field"),
                outcome(api.put("/org-units/" + rollup, readOn(api, "/org-units/" + rollup, "2021-01-01")
                        .put("parentId", first).toString()), "field"),
                outcome(api.put(unit, readOn(api, unit, "2025-01-01").put("effectiveWorkLocation", "tokyo")
                        .toString()), "field"),
                outcome(api.put(unit, readOn(api, unit, "2023-01-01").put("validFrom", "2023-01-01")
                        .put("effectiveWorkLocation", "nagoya").toString()), "field"),
                outcome(api.put("/org-units/" + root, readOn(api, "/org-units/" + root, "2021-01-01")
                        .put("effectiveWorkLocation", "tokyo").toString()), "field"),
                outcome(api.put(unit, readOn(api, unit, "2021-01-01").put("effectiveCostCenter", "c2").toString()),
                        "field"),
                outcome(api.put(unit, readOn(api, unit, "2025-01-01").put("effectiveCostCenter", "c1").toString()),
                        "field")));
        assertEquals(before, RealmExportIT.export(api));

        // A read of a date after a change above or a move has the values of that date, and its details start before.
        List<String> dates = List.of("2021-01-01", "2021-06-01", "2023-01-01", "2025-01-01");
        List<String> got = new ArrayList<>();
        for (String date : dates) {
            ObjectNode read = readOn(api, unit, date);
            got.add(read.get("validFrom").asText() + " " + read.get("parentId").asText() + " "
                    + read.get("effectiveWorkLocation").asText() + " " + read.path("effectiveCostCenter").asText());
            ok(api.put(unit, read.toString()));
            ok(api.put(unit, readOn(api, unit, date).without(List.of("parentId", "effectiveWorkLocation",
                    "effectiveCostCenter")).toString()));
        }
        assertEquals(List.of("2020-04-01 " + first + " tokyo c1", "2020-04-01 " + first + " nagoya c1",
                "2020-04-01 " + second + " osaka ", "2024-04-01 " + second + " osaka "), got);
        for (String id : List.of(root, rollup)) {
            String path = "/org-units/" + id;
            ObjectNode read = readOn(api, path, "2021-01-01");
            assertTrue(read.path("parentId").isMissingNode(), read.toString());
            ok(api.put(path, read.toString()));
            ok(api.put(path, readOn(api, path, "2021-01-01").putNull("parentId").putNull("effectiveWorkLocation")
                    .putNull("effectiveCostCenter").toString()));
        }
        assertEquals(List.of(first, second, second), List.of(readOn(api, unit, "2021-01-01").get("parentId").asText(),
                readOn(api, unit, "2023-01-01").get("parentId").asText(),
                readOn(api, unit, "2025-01-01").get("parentId").asText()));
    }
}
