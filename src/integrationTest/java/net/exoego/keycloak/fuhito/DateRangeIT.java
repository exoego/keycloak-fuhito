package net.exoego.keycloak.fuhito;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.RealmExportIT.named;
import static net.exoego.keycloak.fuhito.RestoreMissingValuesIT.assertBadValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DateRangeIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Java reads each of them. PostgreSQL cannot hold the first, and other databases cannot hold the others.
    private static final List<String> OUTSIDE = List.of("-5000-01-01", "+10000-01-01", "0000-12-31");
    private static final List<String> EDGES = List.of("0001-01-01", "9999-12-31");

    private static Api api;

    @BeforeAll
    static void createRealm() {
        api = TestRealm.create("date-range").api;
    }

    private static String query(String date) {
        return URLEncoder.encode(date, StandardCharsets.UTF_8);
    }

    private static void assertInvalidDate(HttpResponse<String> response, String field, String date) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response);
        assertEquals("invalid.date", error.get("error").asText(), response.body());
        assertEquals(JSON.valueToTree(Map.of("field", field, "value", date)), error.get("params"));
    }

    // A bad row fails the whole import, and the report names the row.
    private static void assertInvalidRow(HttpResponse<String> response, String field) throws Exception {
        assertEquals(400, response.statusCode(), response.body());
        JsonNode error = Api.json(response).get("errors").get(0);
        assertEquals("invalid.date", error.get("error").asText(), response.body());
        assertEquals(field, error.get("params").get("field").asText(), response.body());
    }

    private static String order(String date) {
        return "{\"title\": \"日付の端\", \"effectiveDate\": \"" + date + "\"}";
    }

    private static String unit(String name, String date) {
        return "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + name + "\"}}, \"validFrom\": \""
                + date + "\"}";
    }

    @Test
    void aChangeOrderTakesOnlyADateInTheRange() throws Exception {
        for (String date : OUTSIDE) {
            assertInvalidDate(api.post("/change-orders", order(date)), "effectiveDate", date);
            assertInvalidDate(api.get("/change-orders?from=" + query(date)), "from", date);
            assertInvalidDate(api.get("/change-orders?to=" + query(date)), "to", date);
        }
        for (String date : EDGES) {
            HttpResponse<String> created = api.post("/change-orders", order(date));
            assertEquals(201, created.statusCode(), created.body());
            assertEquals(date, Api.json(created).get("effectiveDate").asText());
        }
        HttpResponse<String> listed = api.get("/change-orders?from=0001-01-01&to=9999-12-31");
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(List.of("0001-01-01"), Api.json(listed).findValuesAsText("effectiveDate"));
    }

    @Test
    void aListOfChangeOrdersWithNoEndHasTheLastDate() throws Exception {
        Api orders = TestRealm.create("date-range-orders").api;
        for (String date : EDGES) {
            HttpResponse<String> created = orders.post("/change-orders", order(date));
            assertEquals(201, created.statusCode(), created.body());
        }
        HttpResponse<String> last = orders.get("/change-orders?from=9999-12-31");
        assertEquals(200, last.statusCode(), last.body());
        assertEquals(List.of("9999-12-31"), Api.json(last).findValuesAsText("effectiveDate"));
        assertEquals(1, orders.count("/change-orders/count?from=9999-12-31"));
        HttpResponse<String> all = orders.get("/change-orders");
        assertEquals(200, all.statusCode(), all.body());
        assertEquals(List.of("9999-12-31", "0001-01-01"), Api.json(all).findValuesAsText("effectiveDate"));
        assertEquals(2, orders.count("/change-orders/count"));
    }

    @Test
    void anEmploymentFromTheLastDateKeepsItsEmployerALegalEntity() throws Exception {
        TestRealm realm = TestRealm.create("date-range-employer");
        String company = realm.legalEntity("最後の日の雇用主");
        realm.refItem("UNIT_TYPE", "department", "部");
        HttpResponse<String> employment = realm.api.post("/employments", "{\"userId\": \"" + realm.user("last-day")
                + "\", \"employerId\": \"" + company + "\", \"validFrom\": \"9999-12-31\"}");
        assertEquals(201, employment.statusCode(), employment.body());

        HttpResponse<String> changed = realm.api.putWithVersion("/org-units/" + company, "{\"primaryLanguage\": \"ja\", "
                + "\"names\": {\"ja\": {\"name\": \"最後の日の雇用主\"}}, \"unitType\": \"department\", "
                + "\"validFrom\": \"9999-12-31\"}");
        assertEquals(409, changed.statusCode(), changed.body());
        assertEquals("org_unit.is_employer", Api.json(changed).get("error").asText());
    }

    @Test
    void anOrgUnitTakesOnlyADateInTheRangeAndSoDoesARead() throws Exception {
        for (String date : OUTSIDE) {
            assertInvalidDate(api.post("/org-units", unit("範囲外", date)), "validFrom", date);
        }
        HttpResponse<String> first = api.post("/org-units", unit("最初の日から", "0001-01-01"));
        assertEquals(201, first.statusCode(), first.body());
        HttpResponse<String> last = api.post("/org-units", unit("最後の日から", "9999-12-31"));
        assertEquals(201, last.statusCode(), last.body());
        String id = Api.json(first).get("id").asText();

        for (String date : OUTSIDE) {
            assertInvalidDate(api.get("/org-units/" + id + "?asOf=" + query(date)), "asOf", date);
            assertInvalidDate(api.get("/org-units?asOf=" + query(date)), "asOf", date);
        }
        for (String date : EDGES) {
            HttpResponse<String> read = api.get("/org-units/" + id + "?asOf=" + date);
            assertEquals(200, read.statusCode(), read.body());
            assertEquals("最初の日から", Api.json(read).get("name").asText());
            assertEquals(200, api.get("/org-units?asOf=" + date).statusCode());
        }
        HttpResponse<String> lastRead = api.get("/org-units/" + Api.json(last).get("id").asText() + "?asOf=9999-12-31");
        assertEquals("最後の日から", Api.json(lastRead).get("name").asText());
    }

    @Test
    void anImportTakesOnlyADateInTheRange() throws Exception {
        for (String date : OUTSIDE) {
            assertInvalidDate(api.post("/imports", "{\"effectiveDate\": \"" + date + "\"}"), "effectiveDate", date);
            assertInvalidRow(api.post("/imports", imported("2026-04-01", "bad", date)), "validFrom");
        }
        for (String date : EDGES) {
            HttpResponse<String> response = api.post("/imports", imported(date, "edge-" + date, date));
            assertEquals(200, response.statusCode(), response.body());
        }
    }

    private static String imported(String effectiveDate, String externalId, String validFrom) {
        return "{\"effectiveDate\": \"" + effectiveDate + "\", \"orgUnits\": [{\"externalId\": \"" + externalId
                + "\", \"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"" + externalId + "\"}}, "
                + "\"validFrom\": \"" + validFrom + "\"}]}";
    }

    @Test
    void aCsvFileTakesOnlyADateInTheRange() throws Exception {
        for (String date : OUTSIDE) {
            assertInvalidDate(csv(query(date), "externalId,primaryLanguage,name\r\ncsv-bad,ja,範囲外\r\n"),
                    "effectiveDate", date);
            assertInvalidRow(csv("2026-04-01", "externalId,primaryLanguage,name,validFrom\r\ncsv-bad,ja,範囲外,"
                    + date + "\r\n"), "validFrom");
        }
        for (String date : EDGES) {
            HttpResponse<String> loaded = csv(date, "externalId,primaryLanguage,name,validFrom\r\ncsv-" + date
                    + ",ja,ファイル " + date + "," + date + "\r\n");
            assertEquals(200, loaded.statusCode(), loaded.body());
        }
    }

    private static HttpResponse<String> csv(String effectiveDate, String body) throws Exception {
        return api.postBytes("/imports/csv/org-units?effectiveDate=" + effectiveDate, "text/csv; charset=UTF-8",
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRestoreTakesOnlyADateInTheRange() throws Exception {
        TestRealm target = TestRealm.create("date-range-restore");
        for (String date : OUTSIDE) {
            assertBadValue(target.api, restored(date, "2026-04-01T00:00:00Z", "2026-04-01"),
                    Map.of("list", "change-orders", "id", "dates-o", "field", "effectiveDate"));
            assertBadValue(target.api, restored("2026-04-01", date + "T00:00:00Z", "2026-04-01"),
                    Map.of("list", "change-orders", "id", "dates-o", "field", "createdAt"));
            assertBadValue(target.api, restored("2026-04-01", "2026-04-01T00:00:00Z", date),
                    Map.of("list", "org-unit-versions", "id", "dates-v", "field", "validFrom"));
        }
        assertEquals(0, Api.json(target.api.get("/export/change-orders")).size());

        ObjectNode edges = restored("0001-01-01", "0001-01-01T00:00:00Z", "9999-12-31");
        ((ObjectNode) edges.get("changeOrders").get(0)).put("orderDate", "9999-12-31")
                .put("announcedAt", "9999-12-31T23:59:59.999999Z");
        HttpResponse<String> response = target.api.post("/restore", JSON.writeValueAsString(edges));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode order = Api.json(target.api.get("/export/change-orders")).get(0);
        assertEquals("0001-01-01", order.get("effectiveDate").asText());
        assertEquals("9999-12-31", order.get("orderDate").asText());
    }

    // The IDs of a restore are unique in the database, so they differ from those of the other tests.
    private static ObjectNode restored(String effectiveDate, String createdAt, String validFrom) {
        ObjectNode document = JSON.createObjectNode().put("format", "fuhito.export.v1");
        document.putArray("changeOrders").addObject().put("id", "dates-o").put("title", "日付の端")
                .put("effectiveDate", effectiveDate).put("createdAt", createdAt);
        ObjectNode unit = document.putArray("orgUnits").addObject().put("id", "dates-u");
        named(unit.putArray("versions").addObject().put("id", "dates-v").put("validFrom", validFrom))
                .putObject("attributes");
        unit.putArray("parents");
        unit.putArray("successors");
        return document;
    }
}
