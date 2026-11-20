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
        HttpResponse<String> response = api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}" + parent + more
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
        ok(api.putWithVersion("/org-units/" + company, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"会社\"}}, \"workLocation\": \"osaka\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        ok(api.putWithVersion("/org-units/" + team + "/parent", "{\"parentId\": \"" + other + "\", \"validFrom\": "
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
        assertEquals(List.of("null null osaka cc-sales"), each(get("/org-units?q="
                + URLEncoder.encode("営業第1課", StandardCharsets.UTF_8) + "&asOf=2021-04-01")));
        // A history has the values of each version only.
        assertEquals(List.of("tokyo cc-hq null null", "osaka cc-hq null null"),
                each(get("/org-units/" + company + "/history").get("versions")));

        // A step of a reorganization gives a unit its values.
        ok(api.postWithVersion("/reorganizations", "{\"effectiveDate\": \"2023-04-01\", \"steps\": [{\"op\": \"change\", "
                + "\"id\": \"" + other + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"別会社\"}}, \"workLocation\": \"nagoya\"}]}"));
        assertEquals("null null nagoya null", places(get("/org-units/" + team + "?asOf=2023-04-01")));
        // A unit with a work location of its own takes only the cost center from above.
        String branch = unit("大阪営業所", sales, ", \"workLocation\": \"osaka\"");
        assertEquals("osaka null osaka cc-sales", places(get("/org-units/" + branch + "?asOf=2020-04-01")));

        assertEquals("ref_item.unknown", errorOf(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"X\"}}, \"costCenter\": \"nope\", "
                + "\"validFrom\": \"2020-04-01\"}")));
        assertEquals("ref_item.unknown", errorOf(api.putWithVersion("/org-units/" + team, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"営業第1課\"}}, "
                + "\"workLocation\": \"cc-hq\", \"validFrom\": \"2024-04-01\"}")));
    }

    private static String assign(String user, String orgUnitId, String more) throws Exception {
        String employment = ok(api.post("/employments", "{\"userId\": \"" + realm.user(user)
                + "\", \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        return ok(api.post("/assignments", "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + orgUnitId + "\", \"validFrom\": \"2020-04-01\", \"primary\": true" + more + "}")).get("id")
                .asText();
    }

    private static List<String> members(String orgUnitId, String query) throws Exception {
        return get("/org-units/" + orgUnitId + "/members?" + query).findValuesAsText("assignmentId");
    }

    @Test
    void theMembersAreKeptByTheValuesThatApplyOnTheDate() throws Exception {
        // The head office moves from Tokyo to Osaka in 2021. The sales division has its own cost center.
        String office = unit("本部", null, ", \"workLocation\": \"tokyo\", \"costCenter\": \"cc-hq\"");
        String sales = unit("営業本部", office, ", \"costCenter\": \"cc-sales\"");
        String branch = unit("名古屋営業所", sales, ", \"workLocation\": \"nagoya\", \"displayOrder\": 1");
        String team = unit("営業課", sales, ", \"displayOrder\": 2");
        ok(api.putWithVersion("/org-units/" + office, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"本部\"}}, \"workLocation\": \"osaka\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        String atTop = assign("at-top", office, ", \"displayOrder\": 0");
        String inTeam = assign("in-team", team, ", \"displayOrder\": 1");
        String atHome = assign("at-home", team, ", \"displayOrder\": 2, \"workLocation\": \"home\"");
        String ownTokyo = assign("own-tokyo", team, ", \"displayOrder\": 3, \"workLocation\": \"tokyo\"");
        String ownCostCenter = assign("own-cost-center", branch, ", \"costCenter\": \"cc-hq\"");
        String below = "includeDescendants=true&asOf=2020-04-01&";

        // The value of the unit, of a unit above, or of the assignment itself.
        assertEquals(List.of(atTop, inTeam, ownTokyo), members(office, below + "workLocation=tokyo"));
        assertEquals(List.of(ownCostCenter), members(office, below + "workLocation=nagoya"));
        assertEquals(List.of(atHome), members(office, below + "workLocation=home"));
        // The cost center of the assignment wins over the one of the branch.
        assertEquals(List.of(inTeam, atHome, ownTokyo), members(office, below + "costCenter=cc-sales"));
        assertEquals(List.of(atTop, ownCostCenter), members(office, below + "costCenter=cc-hq"));
        assertEquals(List.of(inTeam, ownTokyo), members(office, below + "workLocation=tokyo&costCenter=cc-sales"));
        assertEquals(List.of(inTeam, ownTokyo), members(team, "asOf=2020-04-01&workLocation=tokyo"));
        assertEquals(List.of(), members(sales, "asOf=2020-04-01&costCenter=cc-sales"));
        // The units belong to no legal entity, so none of these people are seconded.
        assertEquals(List.of(), members(office, below + "workLocation=tokyo&seconded=true"));
        assertEquals(List.of(atTop, inTeam, ownTokyo), members(office, below + "workLocation=tokyo&seconded=false"));
        assertEquals(List.of(inTeam, ownTokyo), members(team, "asOf=2020-04-01&workLocation=tokyo&seconded=false"));
        // The values of the units are dated.
        String later = "includeDescendants=true&asOf=2021-04-01&";
        assertEquals(List.of(ownTokyo), members(office, later + "workLocation=tokyo"));
        assertEquals(List.of(atTop, inTeam), members(office, later + "workLocation=osaka"));
        assertEquals(List.of(atTop), members(office, "asOf=2021-04-01&workLocation=osaka"));

        // Each member has the values of the assignment and the ones that apply.
        assertEquals(List.of(atTop, ownCostCenter, inTeam, atHome, ownTokyo), members(office, below));
        assertEquals(List.of("null null tokyo cc-hq", "null cc-hq nagoya cc-hq", "null null tokyo cc-sales",
                "home null home cc-sales", "tokyo null tokyo cc-sales"),
                each(get("/org-units/" + office + "/members?" + below)));
        // The other reads in the shape of members have them too.
        assertEquals(List.of("null cc-hq nagoya cc-hq"), each(get("/assignments?orgUnitId=" + branch
                + "&asOf=2020-04-01")));
        String userId = get("/assignments/" + atHome).get("userId").asText();
        assertEquals(List.of("home null home cc-sales"),
                each(get("/users/" + userId + "/assignments?asOf=2021-04-01")));

        // The filter applies before the page and the count, also when the Link header leads to the next page.
        HttpResponse<String> page = api.get("/org-units/" + office + "/members?" + below
                + "costCenter=cc-sales&max=2");
        assertEquals(List.of(inTeam, atHome), ok(page).findValuesAsText("assignmentId"));
        assertEquals(3, api.count("/org-units/" + office + "/members/count?" + below + "costCenter=cc-sales"));
        String link = page.headers().firstValue("Link").orElseThrow();
        String prefix = "/admin/realms/" + realm.name + "/fuhito";
        HttpResponse<String> next = api.get(link.substring(link.indexOf(prefix) + prefix.length(), link.indexOf('>')));
        assertEquals(List.of(ownTokyo), ok(next).findValuesAsText("assignmentId"));
        assertEquals(List.of(ownTokyo), members(office, below + "costCenter=cc-sales&first=2&max=2"));
        assertEquals(1, api.count("/org-units/" + office + "/members/count?" + below
                + "workLocation=home&costCenter=cc-sales"));

        // In another hierarchy, the team is below a project. Its values still come from the default hierarchy.
        ok(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"プロジェクト\"}"));
        String project = unit("新製品プロジェクト", null, ", \"workLocation\": \"osaka\"");
        String inProject = assign("in-project", project, "");
        ok(api.putWithVersion("/org-units/" + team + "/parent?hierarchy=project", "{\"parentId\": \"" + project
                + "\", \"validFrom\": \"2020-04-01\"}"));
        String inProjectTree = "hierarchy=project&" + below;
        assertEquals(List.of(inProject, inTeam, atHome, ownTokyo), members(project, inProjectTree));
        assertEquals(List.of(inTeam, ownTokyo), members(project, inProjectTree + "workLocation=tokyo"));
        assertEquals(List.of(inProject), members(project, inProjectTree + "workLocation=osaka"));
        assertEquals(List.of(inTeam, atHome, ownTokyo), members(project, inProjectTree + "costCenter=cc-sales"));

        assertEquals("ref_item.unknown", errorOf(api.get("/org-units/" + office + "/members?workLocation=nope")));
        // A code of the other kind is unknown too.
        assertEquals("ref_item.unknown", errorOf(api.get("/org-units/" + office + "/members?costCenter=tokyo")));
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
        ok(api.putWithVersion("/assignments/" + firstId + "/details", "{\"validFrom\": \"2021-01-01\", \"workLocation\": "
                + "\"home\", \"primary\": true}"));
        ok(api.putWithVersion("/org-units/" + company, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"本社\"}}, \"workLocation\": \"osaka\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        String second = ok(api.post("/staff", "{\"userId\": \"" + realm.user("second") + "\", \"orgUnitId\": \""
                + sales + "\", \"validFrom\": \"2020-04-01\"}")).get("assignment").get("id").asText();

        // A period that is over takes the values of its last date. The one of today takes those of today.
        assertEquals(List.of("null cc-hq tokyo cc-hq", "home null home cc-sales"),
                each(get("/assignments/" + firstId).get("periods")));
        HttpResponse<String> read = api.get("/assignments/" + second);
        assertEquals(List.of("null null osaka cc-sales"), each(Api.json(read).get("periods")));

        // A correction of the unit changes what applies to the assignment, but not its version.
        ok(api.putWithVersion("/org-units/" + company, "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"本社\"}}, \"workLocation\": \"nagoya\", "
                + "\"costCenter\": \"cc-hq\", \"validFrom\": \"2021-04-01\"}"));
        HttpResponse<String> again = api.get("/assignments/" + second);
        assertEquals(List.of("null null nagoya cc-sales"), each(Api.json(again).get("periods")));
        assertEquals(Api.json(read).get("version"), Api.json(again).get("version"));

        // A move does not keep the values of the old assignment, and can give new ones.
        JsonNode move = ok(api.postWithVersion("/assignments/" + second + "/move", "{\"orgUnitId\": \"" + elsewhere
                + "\", \"validFrom\": \"2022-04-01\", \"costCenter\": \"cc-hq\"}"));
        assertEquals(List.of("null null nagoya cc-sales"), each(move.get("ended").get("periods")));
        assertEquals(List.of("null cc-hq null cc-hq"), each(move.get("started").get("periods")));

        assertEquals("ref_item.unknown", errorOf(api.post("/assignments", "{\"employmentId\": \""
                + first.get("employmentId").asText() + "\", \"orgUnitId\": \"" + sales + "\", \"validFrom\": "
                + "\"2020-04-01\", \"workLocation\": \"cc-hq\"}")));
        assertEquals("ref_item.unknown", errorOf(api.putWithVersion("/assignments/" + firstId + "/details",
                "{\"validFrom\": \"2022-01-01\", \"costCenter\": \"tokyo\"}")));
    }
}
