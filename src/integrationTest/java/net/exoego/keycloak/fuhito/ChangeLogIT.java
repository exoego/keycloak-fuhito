package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transfer in April 2026 under one personnel order that the director approved.
 */
class ChangeLogIT {

    private static TestRealm realm;
    private static TestRealm otherRealm;
    private static Api api;
    private static String order;
    private static String unit;
    private static String newUnit;
    private static String employment;
    private static String oldAssignment;
    private static String newAssignment;

    @BeforeAll
    static void transferOnePerson() throws Exception {
        realm = TestRealm.create("change-log");
        otherRealm = TestRealm.create("change-log");
        api = realm.api;
        String director = realm.user("director");
        String staff = realm.user("staff");
        unit = id(api.post("/org-units", "{\"name\": \"総務課\", \"validFrom\": \"2025-04-01\"}"));
        JsonNode added = Api.json(api.post("/staff", "{\"userId\": \"" + staff + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2025-04-01\"}"));
        employment = added.get("employment").get("id").asText();
        oldAssignment = added.get("assignment").get("id").asText();

        order = id(api.post("/change-orders", "{\"number\": \"2026-001\", \"title\": \"2026年4月異動\", "
                + "\"effectiveDate\": \"2026-04-01\", \"approverUserId\": \"" + director + "\"}"));
        String under = "?changeOrder=" + order;
        newUnit = id(api.post("/org-units" + under, "{\"name\": \"人事課\", \"validFrom\": \"2026-04-01\"}"));
        assertEquals(200, api.put("/assignments/" + oldAssignment + "/end" + under, "{\"validTo\": \"2026-04-01\"}").statusCode());
        newAssignment = id(api.post("/assignments" + under, "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \""
                + newUnit + "\", \"validFrom\": \"2026-04-01\", \"primary\": true}"));
    }

    private static String id(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    @Test
    void listsTheWritesOfAChangeOrderInTheOrderTheyHappened() throws Exception {
        JsonNode changes = ok(api.get("/change-orders/" + order + "/changes"));

        assertEquals(List.of("FUHITO_ORG_UNIT", "FUHITO_ASSIGNMENT", "FUHITO_ASSIGNMENT"), changes.findValuesAsText("resourceType"));
        assertEquals(List.of(newUnit, oldAssignment, newAssignment), changes.findValuesAsText("resourceId"));
        assertEquals(List.of("CREATE", "UPDATE", "CREATE"), changes.findValuesAsText("operation"));
        assertEquals(List.of(order, order, order), changes.findValuesAsText("changeOrderId"));
        JsonNode created = changes.get(0);
        assertEquals("fuhito/org-units/" + newUnit, created.get("resourcePath").asText());
        assertEquals("人事課", created.get("representation").get("name").asText());
        assertEquals("2026-04-01", created.get("representation").get("validFrom").asText());
        assertTrue(created.get("recordedAt").asText().endsWith("Z"));
        assertEquals(36, created.get("actorUserId").asText().length());
        assertEquals("fuhito/assignments/" + oldAssignment + "/end", changes.get(1).get("resourcePath").asText());
        assertEquals("2026-04-01", changes.get(1).get("representation").get("validTo").asText());
        assertEquals(1, ok(api.get("/change-orders/" + order + "/changes?first=1&max=1")).size());
    }

    @Test
    void listsTheWritesToOneResourceWithAndWithoutAChangeOrder() throws Exception {
        JsonNode entries = ok(api.get("/change-log?resourceType=FUHITO_ASSIGNMENT&resourceId=" + oldAssignment));

        assertEquals(List.of("CREATE", "UPDATE"), entries.findValuesAsText("operation"));
        assertFalse(entries.get(0).has("changeOrderId"));
        assertEquals(order, entries.get(1).get("changeOrderId").asText());
        assertFalse(entries.get(0).get("representation").has("validTo"));

        // The order itself is a resource too, and its own writes do not belong to it.
        JsonNode ofOrder = ok(api.get("/change-log?resourceType=FUHITO_CHANGE_ORDER&resourceId=" + order));
        assertEquals(List.of("CREATE"), ofOrder.findValuesAsText("operation"));
        assertFalse(ofOrder.get(0).has("changeOrderId"));
        assertEquals("2026年4月異動", ofOrder.get(0).get("representation").get("title").asText());
    }

    private static List<Long> sequences(JsonNode entries) {
        List<Long> numbers = new ArrayList<>();
        entries.forEach(entry -> numbers.add(entry.get("sequence").asLong()));
        return numbers;
    }

    @Test
    void followsEveryWriteOfTheRealmAfterASequenceNumber() throws Exception {
        JsonNode firstPage = ok(api.get("/change-log?max=3"));

        assertEquals(List.of(1L, 2L, 3L), sequences(firstPage));
        assertEquals("FUHITO_ORG_UNIT", firstPage.get(0).get("resourceType").asText());
        assertEquals(unit, firstPage.get(0).get("resourceId").asText());
        assertEquals(List.of(4L, 5L, 6L), sequences(ok(api.get("/change-log?after=3&max=3"))));
        assertEquals(List.of(5L), sequences(ok(api.get("/change-log?after=3&first=1&max=1"))));
        assertEquals(0, ok(api.get("/change-log?after=100000")).size());

        JsonNode orders = ok(api.get("/change-log?resourceType=FUHITO_CHANGE_ORDER"));
        assertTrue(orders.findValuesAsText("resourceId").contains(order));
        assertEquals(List.of("FUHITO_CHANGE_ORDER"), orders.findValuesAsText("resourceType").stream().distinct().toList());
        JsonNode ofAssignment = ok(api.get("/change-log?resourceId=" + oldAssignment));
        assertEquals(List.of("CREATE", "UPDATE"), ofAssignment.findValuesAsText("operation"));
        assertEquals(List.of("UPDATE"),
                ok(api.get("/change-log?after=" + ofAssignment.get(0).get("sequence").asLong() + "&resourceId=" + oldAssignment))
                        .findValuesAsText("operation"));
    }

    @Test
    void eachRealmCountsItsOwnWritesFromOneWithoutGapsUnderParallelWrites() throws Exception {
        TestRealm busy = TestRealm.create("change-log");
        int writes = 24;
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < writes; i++) {
                String body = "{\"kind\": \"UNIT_TYPE\", \"code\": \"c" + i + "\", \"name\": \"x\"}";
                results.add(executor.submit(() -> busy.api.post("/ref-items", body).statusCode()));
            }
            for (Future<Integer> result : results) {
                assertEquals(201, result.get());
            }
        }

        assertEquals(LongStream.rangeClosed(1, writes).boxed().toList(), sequences(ok(busy.api.get("/change-log"))));
    }

    @Test
    void aDeleteIsLoggedWithoutAState() throws Exception {
        String item = realm.refItem("UNIT_TYPE", "to-delete", "x");
        assertEquals(204, api.delete("/ref-items/" + item).statusCode());

        JsonNode entries = ok(api.get("/change-log?resourceType=FUHITO_REF_ITEM&resourceId=" + item));

        assertEquals(List.of("CREATE", "DELETE"), entries.findValuesAsText("operation"));
        assertFalse(entries.get(1).has("representation"));
    }

    @Test
    void anUnknownChangeOrderRejectsTheWriteAndAFailedWriteLogsNothing() throws Exception {
        HttpResponse<String> unknown = api.post("/org-units?changeOrder=no-such-order", "{\"name\": \"x\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(400, unknown.statusCode(), unknown.body());
        assertEquals("change_order.not_found", Api.json(unknown).get("error").asText());
        assertEquals(400, otherRealm.api.post("/org-units?changeOrder=" + order, "{\"name\": \"x\", \"validFrom\": \"2026-04-01\"}").statusCode());

        String scratch = id(api.post("/change-orders", "{\"title\": \"失敗する\", \"effectiveDate\": \"2026-04-01\"}"));
        assertEquals(400, api.post("/org-units?changeOrder=" + scratch, "{\"name\": \" \", \"validFrom\": \"2026-04-01\"}").statusCode());
        assertEquals(409, api.put("/org-units/" + unit + "/end?changeOrder=" + scratch, "{\"validTo\": \"2026-01-01\"}").statusCode());
        assertEquals(0, ok(api.get("/change-orders/" + scratch + "/changes")).size());
        // A read ignores the parameter.
        assertEquals(200, api.get("/org-units?changeOrder=no-such-order&asOf=2026-04-01").statusCode());
    }

    @Test
    void aChangeOrderWithWritesCannotBeDeleted() throws Exception {
        HttpResponse<String> response = api.delete("/change-orders/" + order);

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("change_order.in_use", Api.json(response).get("error").asText());
        assertEquals(200, api.get("/change-orders/" + order).statusCode());
    }

    @Test
    void rejectsBadReads() throws Exception {
        assertEquals(404, api.get("/change-orders/nope/changes").statusCode());
        assertEquals(404, otherRealm.api.get("/change-orders/" + order + "/changes").statusCode());
        assertEquals(0, ok(otherRealm.api.get("/change-log?resourceType=FUHITO_ORG_UNIT&resourceId=" + newUnit)).size());
        assertEquals(401, new Api(realm.name, null).get("/change-log?resourceType=x&resourceId=y").statusCode());
    }
}
