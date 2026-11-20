package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeOrdersIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String director;
    private static String hr;

    @BeforeAll
    static void createRealms() {
        realm = TestRealm.create("change-orders");
        otherRealm = TestRealm.create("change-orders");
        api = realm.api;
        director = realm.user("director");
        hr = realm.user("hr-staff");
    }

    private static JsonNode created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static String errorOf(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response).get("error").asText();
    }

    @Test
    void createsReadsUpdatesAndDeletesAnOrder() throws Exception {
        JsonNode order = created(api.post("/change-orders", "{\"number\": \"2026-0042\", \"kind\": \"personnel\", "
                + "\"title\": \"2026年4月定期異動\", \"note\": \"理事会承認済み\", \"orderDate\": \"2026-03-15\", "
                + "\"effectiveDate\": \"2026-04-01\", \"approverUserId\": \"" + director + "\", "
                + "\"requesterUserId\": \"" + hr + "\", \"externalReference\": \"稟議-123\"}"));
        String id = order.get("id").asText();

        assertEquals("2026年4月定期異動", order.get("title").asText());
        assertEquals("2026-03-15", order.get("orderDate").asText());
        assertEquals("2026-04-01", order.get("effectiveDate").asText());
        assertEquals(director, order.get("approverUserId").asText());
        assertEquals(hr, order.get("requesterUserId").asText());
        assertEquals("稟議-123", order.get("externalReference").asText());
        assertTrue(order.get("createdAt").asText().endsWith("Z"));
        assertEquals(order, Api.json(api.get("/change-orders/" + id)));

        HttpResponse<String> response = api.putWithVersion("/change-orders/" + id,
                "{\"number\": \"2026-0042\", \"title\": \"訂正後\", \"effectiveDate\": \"2026-05-01\"}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode changed = Api.json(response);
        assertEquals("訂正後", changed.get("title").asText());
        assertFalse(changed.has("approverUserId"));
        assertEquals(order.get("createdAt"), changed.get("createdAt"));
        assertEquals(changed, Api.json(api.get("/change-orders/" + id)));

        assertEquals(204, api.deleteWithVersion("/change-orders/" + id).statusCode());
        assertEquals("change_order.not_found", errorOf(404, api.get("/change-orders/" + id)));
    }

    @Test
    void anEmptyNoteOrTextFails() throws Exception {
        for (String field : List.of("note", "externalReference", "title", "number", "kind", "approverUserId")) {
            for (String empty : List.of("", "  ")) {
                HttpResponse<String> response = api.post("/change-orders", "{\"title\": \"空\", "
                        + "\"effectiveDate\": \"2027-04-01\", \"" + field + "\": \"" + empty + "\"}");
                assertEquals("invalid.blank", errorOf(400, response), field);
                assertEquals(field, Api.json(response).get("params").get("field").asText());
            }
        }
        JsonNode order = created(api.post("/change-orders", "{\"title\": \"空\", \"note\": \"メモ\", "
                + "\"effectiveDate\": \"2027-04-01\"}"));
        String path = "/change-orders/" + order.get("id").asText();

        assertEquals("invalid.blank", errorOf(400, api.putWithVersion(path, "{\"title\": \"空\", \"note\": \"\", "
                + "\"effectiveDate\": \"2027-04-01\"}")));
        assertEquals("メモ", Api.json(api.get(path)).get("note").asText());
        HttpResponse<String> cleared = api.putWithVersion(path, "{\"title\": \"空\", \"note\": null, "
                + "\"effectiveDate\": \"2027-04-01\"}");
        assertEquals(200, cleared.statusCode(), cleared.body());
        assertFalse(Api.json(cleared).has("note"));
    }

    @Test
    void recordsThatAnOrderWasAnnounced() throws Exception {
        JsonNode order = created(api.post("/change-orders", "{\"title\": \"内示\", \"effectiveDate\": \"2027-04-01\"}"));
        String id = order.get("id").asText();
        assertFalse(order.has("announcedAt"));
        // A request cannot set the announcement.
        assertFalse(created(api.post("/change-orders", "{\"title\": \"x\", \"effectiveDate\": \"2027-04-01\", "
                + "\"announcedAt\": \"2020-01-01T00:00:00Z\"}")).has("announcedAt"));

        HttpResponse<String> response = api.postWithVersion("/change-orders/" + id + "/announce", "");

        assertEquals(200, response.statusCode(), response.body());
        JsonNode announced = Api.json(response);
        assertTrue(announced.get("announcedAt").asText().endsWith("Z"));
        assertFalse(announced.get("announcedByUserId").asText().isEmpty());
        assertEquals(announced, Api.json(api.get("/change-orders/" + id)));
        // A replace keeps it.
        assertEquals(announced.get("announcedAt"), Api.json(api.putWithVersion("/change-orders/" + id,
                "{\"title\": \"発令\", \"effectiveDate\": \"2027-04-01\"}")).get("announcedAt"));
        assertEquals(List.of("CREATE", "UPDATE", "UPDATE"), Api.json(api.get(
                "/change-log?resourceType=FUHITO_CHANGE_ORDER&resourceId=" + id)).findValuesAsText("operation"));

        assertEquals("change_order.already_announced", errorOf(409, api.postWithVersion("/change-orders/" + id + "/announce", "")));
        assertEquals("change_order.not_found", errorOf(404, api.postWithVersion("/change-orders/nope/announce", "")));
        assertEquals("change_order.not_found",
                errorOf(404, otherRealm.api.postWithVersion("/change-orders/" + id + "/announce", "")));
    }

    @Test
    void listsOrdersByEffectiveDate() throws Exception {
        TestRealm listRealm = TestRealm.create("change-orders-list");
        for (String date : List.of("2026-04-01", "2027-04-01", "2026-10-01")) {
            created(listRealm.api.post("/change-orders", "{\"title\": \"" + date + "\", \"effectiveDate\": \"" + date + "\"}"));
        }

        assertEquals(List.of("2027-04-01", "2026-10-01", "2026-04-01"),
                Api.json(listRealm.api.get("/change-orders")).findValuesAsText("title"));
        assertEquals(List.of("2026-10-01"),
                Api.json(listRealm.api.get("/change-orders?from=2026-04-02&to=2027-04-01")).findValuesAsText("title"));
        assertEquals(List.of("2026-10-01"),
                Api.json(listRealm.api.get("/change-orders?first=1&max=1")).findValuesAsText("title"));
        assertEquals(400, listRealm.api.get("/change-orders?from=nope").statusCode());
        assertEquals(3, listRealm.api.count("/change-orders/count"));
        assertEquals(1, listRealm.api.count("/change-orders/count?from=2026-04-02&to=2027-04-01"));
        assertEquals(400, listRealm.api.get("/change-orders/count?to=nope").statusCode());
    }

    @Test
    void rejectsBadRequests() throws Exception {
        created(api.post("/change-orders", "{\"number\": \"TAKEN\", \"title\": \"x\", \"effectiveDate\": \"2026-04-01\"}"));

        assertEquals("change_order.number_taken", errorOf(409, api.post("/change-orders",
                "{\"number\": \"TAKEN\", \"title\": \"y\", \"effectiveDate\": \"2026-04-01\"}")));
        assertEquals("invalid.required", errorOf(400, api.post("/change-orders", "{\"title\": \"x\"}")));
        assertEquals("invalid.required", errorOf(400, api.post("/change-orders", "{\"effectiveDate\": \"2026-04-01\"}")));
        assertEquals("change_order.user_unknown", errorOf(400, api.post("/change-orders",
                "{\"title\": \"x\", \"effectiveDate\": \"2026-04-01\", \"approverUserId\": \"nobody\"}")));
        assertEquals(401, new Api(realm.name, null).post("/change-orders", "{}").statusCode());
        assertEquals(401, new Api(realm.name, null).get("/change-orders").statusCode());
    }

    @Test
    void oneRealmDoesNotSeeTheOrdersOrUsersOfAnother() throws Exception {
        String body = "{\"number\": \"PER-REALM\", \"title\": \"x\", \"effectiveDate\": \"2026-04-01\"}";
        String id = created(api.post("/change-orders", body)).get("id").asText();

        assertEquals("change_order.not_found", errorOf(404, otherRealm.api.get("/change-orders/" + id)));
        assertEquals("change_order.not_found", errorOf(404, otherRealm.api.putWithVersion("/change-orders/" + id, body)));
        assertEquals("change_order.not_found", errorOf(404, otherRealm.api.deleteWithVersion("/change-orders/" + id)));
        assertFalse(Api.json(otherRealm.api.get("/change-orders")).findValuesAsText("id").contains(id));
        assertEquals("change_order.user_unknown", errorOf(400, otherRealm.api.post("/change-orders",
                "{\"title\": \"x\", \"effectiveDate\": \"2026-04-01\", \"approverUserId\": \"" + director + "\"}")));
        created(otherRealm.api.post("/change-orders", body));
    }
}
