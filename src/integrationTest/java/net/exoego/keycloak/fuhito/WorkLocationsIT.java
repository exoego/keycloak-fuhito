package net.exoego.keycloak.fuhito;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A company whose units give their members a work location and a cost center.
 */
class WorkLocationsIT {

    private static TestRealm realm;
    private static Api api;

    @BeforeAll
    static void createTheItems() throws Exception {
        realm = TestRealm.withEmployer("work-locations");
        api = realm.api;
        realm.refItem("WORK_LOCATION", "tokyo", "東京本社");
        realm.refItem("WORK_LOCATION", "osaka", "大阪支社");
        realm.refItem("WORK_LOCATION", "nagoya", "名古屋支社");
        realm.refItem("WORK_LOCATION", "home", "在宅");
        realm.refItem("COST_CENTER", "cc-hq", "本社");
        realm.refItem("COST_CENTER", "cc-sales", "営業");
    }

    private static String unit(String name, String parentId, String more) throws Exception {
        String parent = parentId == null ? "" : ", \"parentId\": \"" + parentId + "\"";
        HttpResponse<String> response = api.post("/org-units", "{\"name\": \"" + name + "\"" + parent + more
                + ", \"validFrom\": \"2020-04-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return Api.json(response);
    }

    private static JsonNode get(String path) throws Exception {
        return ok(api.get(path));
    }

    private static String errorOf(HttpResponse<String> response) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String places(JsonNode node) {
        return text(node, "workLocation") + " " + text(node, "costCenter") + " " + text(node, "effectiveWorkLocation")
                + " " + text(node, "effectiveCostCenter");
    }

    private static List<String> each(JsonNode nodes) {
        List<String> result = new ArrayList<>();
        nodes.forEach(node -> result.add(places(node)));
        return result;
    }

    @Test
    void aUnitTakesEachValueFromTheNearestUnitAboveOnTheDate() throws Exception {
        String company = unit("会社", null, ", \"workLocation\": \"tokyo\", \"costCenter\": \"cc-hq\"");
        String sales = unit("営業部", company, ", \"costCenter\": \"cc-sales\"");
        String team = unit("営業第1課", sales, "");
        String other = unit("別会社", null, "");
        ok(api.put("/org-units/" + company, "{\"name\": \"会社\", \"workLocation\": \"osaka\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        ok(api.put("/org-units/" + team + "/parent", "{\"parentId\": \"" + other + "\", \"validFrom\": "
                + "\"2022-04-01\"}"));

        assertEquals("tokyo cc-hq tokyo cc-hq", places(get("/org-units/" + company + "?asOf=2020-04-01")));
        assertEquals("null null tokyo cc-sales", places(get("/org-units/" + team + "?asOf=2021-03-31")));
        assertEquals("null null osaka cc-sales", places(get("/org-units/" + team + "?asOf=2021-04-01")));
        assertEquals("null null null null", places(get("/org-units/" + team + "?asOf=2022-04-01")));
        // The reads of many units give the values that apply too.
        assertEquals(List.of("null cc-sales osaka cc-sales"),
                each(get("/org-units/" + company + "/children?asOf=2021-04-01")));
        assertEquals(List.of("osaka cc-hq osaka cc-hq", "null cc-sales osaka cc-sales", "null null osaka cc-sales"),
                each(get("/org-units/" + team + "/path?asOf=2021-04-01")));
        assertEquals(List.of("osaka cc-hq osaka cc-hq", "null cc-sales osaka cc-sales", "null null osaka cc-sales"),
                each(get("/org-units/" + company + "/subtree?asOf=2021-04-01")));
        assertEquals(List.of("null null osaka cc-sales"), each(get("/org-units/search?q="
                + URLEncoder.encode("営業第1課", StandardCharsets.UTF_8) + "&asOf=2021-04-01")));
        // A history has the values of each version only.
        assertEquals(List.of("tokyo cc-hq null null", "osaka cc-hq null null"),
                each(get("/org-units/" + company + "/history").get("versions")));

        // A step of a reorganization gives a unit its values.
        ok(api.post("/reorganizations", "{\"effectiveDate\": \"2023-04-01\", \"steps\": [{\"op\": \"change\", "
                + "\"id\": \"" + other + "\", \"name\": \"別会社\", \"workLocation\": \"nagoya\"}]}"));
        assertEquals("null null nagoya null", places(get("/org-units/" + team + "?asOf=2023-04-01")));
        // A unit with a work location of its own takes only the cost center from above.
        String branch = unit("大阪営業所", sales, ", \"workLocation\": \"osaka\"");
        assertEquals("osaka null osaka cc-sales", places(get("/org-units/" + branch + "?asOf=2020-04-01")));

        assertEquals("ref_item.unknown", errorOf(api.post("/org-units", "{\"name\": \"X\", \"costCenter\": \"nope\", "
                + "\"validFrom\": \"2020-04-01\"}")));
        assertEquals("ref_item.unknown", errorOf(api.put("/org-units/" + team, "{\"name\": \"営業第1課\", "
                + "\"workLocation\": \"cc-hq\", \"validFrom\": \"2024-04-01\"}")));
    }

    @Test
    void anAssignmentTakesTheValuesOfItsUnitUnlessItHasItsOwn() throws Exception {
        String company = unit("本社", null, ", \"workLocation\": \"tokyo\", \"costCenter\": \"cc-hq\"");
        String sales = unit("営業本部", company, ", \"costCenter\": \"cc-sales\"");
        String elsewhere = unit("子会社", null, "");
        JsonNode first = ok(api.post("/staff", "{\"userId\": \"" + realm.user("first") + "\", \"orgUnitId\": \""
                + sales + "\", \"validFrom\": \"2020-04-01\", \"costCenter\": \"cc-hq\"}")).get("assignment");
        String firstId = first.get("id").asText();
        assertEquals(List.of("null cc-hq tokyo cc-hq"), each(first.get("periods")));
        ok(api.put("/assignments/" + firstId + "/details", "{\"validFrom\": \"2021-01-01\", \"workLocation\": "
                + "\"home\", \"primary\": true}"));
        ok(api.put("/org-units/" + company, "{\"name\": \"本社\", \"workLocation\": \"osaka\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        String second = ok(api.post("/staff", "{\"userId\": \"" + realm.user("second") + "\", \"orgUnitId\": \""
                + sales + "\", \"validFrom\": \"2020-04-01\"}")).get("assignment").get("id").asText();

        // A period that is over takes the values of its last date. The one of today takes those of today.
        assertEquals(List.of("null cc-hq tokyo cc-hq", "home null home cc-sales"),
                each(get("/assignments/" + firstId).get("periods")));
        HttpResponse<String> read = api.get("/assignments/" + second);
        assertEquals(List.of("null null osaka cc-sales"), each(Api.json(read).get("periods")));

        // A correction of the unit changes what applies to the assignment, but not its ETag.
        ok(api.put("/org-units/" + company, "{\"name\": \"本社\", \"workLocation\": \"nagoya\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        HttpResponse<String> again = api.get("/assignments/" + second);
        assertEquals(List.of("null null nagoya cc-sales"), each(Api.json(again).get("periods")));
        assertEquals(read.headers().firstValue("ETag").orElseThrow(), again.headers().firstValue("ETag")
                .orElseThrow());

        // A move does not keep the values of the old assignment, and can give new ones.
        JsonNode move = ok(api.post("/assignments/" + second + "/move", "{\"orgUnitId\": \"" + elsewhere
                + "\", \"validFrom\": \"2022-04-01\", \"costCenter\": \"cc-hq\"}"));
        assertEquals(List.of("null null nagoya cc-sales"), each(move.get("ended").get("periods")));
        assertEquals(List.of("null cc-hq null cc-hq"), each(move.get("started").get("periods")));

        assertEquals("ref_item.unknown", errorOf(api.post("/assignments", "{\"employmentId\": \""
                + first.get("employmentId").asText() + "\", \"orgUnitId\": \"" + sales + "\", \"validFrom\": "
                + "\"2020-04-01\", \"workLocation\": \"cc-hq\"}")));
        assertEquals("ref_item.unknown", errorOf(api.put("/assignments/" + firstId + "/details",
                "{\"validFrom\": \"2022-01-01\", \"costCenter\": \"tokyo\"}")));
    }
}
