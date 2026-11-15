package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ConditionalWritesIT {

    private static String etag(HttpResponse<String> response) {
        return response.headers().firstValue("ETag").orElseThrow();
    }

    @Test
    void aWriteForAnOldStateFailsAndLeavesTheResourceAlone() throws Exception {
        TestRealm realm = TestRealm.withEmployer("etags");
        Api api = realm.api;
        HttpResponse<String> created = api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = Api.json(created).get("id").asText();
        HttpResponse<String> read = api.get("/employments/" + id);
        String first = etag(read);
        assertEquals(first, etag(api.get("/employments/" + id)));

        // One product changes the terms with the ETag it read.
        HttpResponse<String> changed = api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"workPercent\": 50}", "If-Match", first);
        assertEquals(200, changed.statusCode(), changed.body());
        String second = etag(changed);
        assertNotEquals(first, second);
        assertEquals(second, etag(api.get("/employments/" + id)));

        // A second product still has the first ETag. Its write fails, and nothing changes.
        HttpResponse<String> stale = api.put("/employments/" + id + "/terms",
                "{\"validFrom\": \"2026-10-01\", \"workPercent\": 80}", "If-Match", first);
        assertEquals(412, stale.statusCode(), stale.body());
        assertEquals("etag.mismatch", Api.json(stale).get("error").asText());
        assertEquals(second, etag(api.get("/employments/" + id)));
        assertEquals(200, api.put("/employments/" + id + "/terms", "{\"validFrom\": \"2026-10-01\"}", "If-Match",
                "\"other\", " + second).statusCode());
        assertEquals(200, api.put("/employments/" + id + "/end", "{\"validTo\": \"2027-04-01\"}", "If-Match", "*")
                .statusCode());

        // The ETag of an org unit covers every date, so a read of one date gives the same one.
        HttpResponse<String> unit = api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\"}");
        String unitId = Api.json(unit).get("id").asText();
        String ofUnit = etag(api.get("/org-units/" + unitId + "?asOf=2026-05-01"));
        assertEquals(ofUnit, etag(api.get("/org-units/" + unitId + "?asOf=2026-06-01")));
        assertEquals(200, api.put("/org-units/" + unitId, "{\"name\": \"第1病棟\", \"validFrom\": \"2027-04-01\"}",
                "If-Match", ofUnit).statusCode());
        assertNotEquals(ofUnit, etag(api.get("/org-units/" + unitId + "?asOf=2026-05-01")));
        assertEquals(412, api.put("/org-units/" + unitId + "/end", "{\"validTo\": \"2028-04-01\"}", "If-Match", ofUnit)
                .statusCode());
    }

    @Test
    void aSecondWriteWithTheSameEtagWaitsAndFails() throws Exception {
        TestRealm realm = TestRealm.withEmployer("if-match-race");
        Api api = realm.api;
        String unit = Api.json(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\"}"))
                .get("id").asText();
        String etag = etag(api.get("/org-units/" + unit));

        CompletableFuture<HttpResponse<String>> second;
        try (Connection first = KeycloakEnvironment.connection()) {
            // The first write holds the row of the unit and changes the unit.
            first.setAutoCommit(false);
            try (PreparedStatement lock = first.prepareStatement(
                    "select id from fuhito_org_unit where id = ? for update")) {
                lock.setString(1, unit);
                lock.execute();
            }
            String holder;
            try (Statement statement = first.createStatement();
                    ResultSet pid = statement.executeQuery("select pg_backend_pid()")) {
                pid.next();
                holder = pid.getString(1);
            }
            second = CompletableFuture.supplyAsync(() -> {
                try {
                    return api.put("/org-units/" + unit, "{\"name\": \"第1病棟\", \"validFrom\": \"2026-04-01\"}",
                            "If-Match", etag);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            String waiting = "0";
            for (int i = 0; i < 300 && waiting.equals("0"); i++) {
                Thread.sleep(100);
                waiting = KeycloakEnvironment.sql("select count(*) from pg_stat_activity where " + holder
                        + " = any(pg_blocking_pids(pid))");
            }
            assertNotEquals("0", waiting, "the second write never waited");
            try (PreparedStatement rename = first.prepareStatement(
                    "update fuhito_org_unit_version set name = 'Changed' where org_unit_id = ?")) {
                rename.setString(1, unit);
                rename.executeUpdate();
            }
            first.commit();
        }

        HttpResponse<String> result = second.get();
        assertEquals(412, result.statusCode(), result.body());
        assertEquals("Changed", Api.json(api.get("/org-units/" + unit)).get("name").asText());

        // An assignment has a row for each period, and the write locks all of them.
        String assignment = Api.json(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}")).get("assignment").get("id").asText();
        assertEquals(200, api.put("/assignments/" + assignment + "/end", "{\"validTo\": \"2027-04-01\"}", "If-Match",
                etag(api.get("/assignments/" + assignment))).statusCode());
    }

    @Test
    void anEraseClearsTheStoredResponsesThatNameThePerson() throws Exception {
        TestRealm realm = TestRealm.withEmployer("idempotency-erase");
        Api api = realm.api;
        String body = "{\"name\": \"山田 花子\"}";
        HttpResponse<String> first = api.post("/people", body, "Idempotency-Key", "person-1");
        assertEquals(201, first.statusCode(), first.body());
        String person = Api.json(first).get("id").asText();
        HttpResponse<String> other = api.post("/people", "{\"name\": \"Other\"}", "Idempotency-Key", "person-2");

        assertEquals(204, api.delete("/people/" + person + "/data").statusCode());

        // The same request again still writes nothing, and its answer no longer has the name.
        HttpResponse<String> again = api.post("/people", body, "Idempotency-Key", "person-1");
        assertEquals(201, again.statusCode(), again.body());
        assertFalse(again.body().contains("山田"), again.body());
        assertEquals(first.headers().firstValue("Location"), again.headers().firstValue("Location"));
        assertEquals(1, Api.json(api.get("/people")).size());
        // The response about another person stays.
        assertEquals(Api.json(other), Api.json(api.post("/people", "{\"name\": \"Other\"}", "Idempotency-Key",
                "person-2")));
    }

    @Test
    void aRepeatedPostWithTheSameKeyGetsTheFirstResponse() throws Exception {
        TestRealm realm = TestRealm.withEmployer("idempotency");
        Api api = realm.api;
        String body = "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\"}";

        HttpResponse<String> first = api.post("/org-units", body, "Idempotency-Key", "order-1");
        HttpResponse<String> again = api.post("/org-units", body, "Idempotency-Key", "order-1");

        assertEquals(201, first.statusCode(), first.body());
        assertEquals(201, again.statusCode(), again.body());
        assertEquals(Api.json(first), Api.json(again));
        assertEquals(first.headers().firstValue("Location"), again.headers().firstValue("Location"));
        assertEquals(1, Api.json(api.get("/org-units/search?q=1%E7%97%85%E6%A3%9F&asOf=2026-05-01")).size());
        assertEquals(422, api.post("/org-units", "{\"name\": \"2病棟\", \"validFrom\": \"2026-04-01\"}",
                "Idempotency-Key", "order-1").statusCode());
        assertEquals("idempotency.bad_key", Api.json(api.post("/org-units", body, "Idempotency-Key", " "))
                .get("error").asText());

        // An operation that answers with a resource returns the same one again.
        HttpResponse<String> employment = api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}");
        String id = Api.json(employment).get("id").asText();
        String leave = "{\"validFrom\": \"2026-10-01\"}";
        HttpResponse<String> firstLeave = api.post("/employments/" + id + "/leave", leave, "Idempotency-Key", "l-1");
        HttpResponse<String> secondLeave = api.post("/employments/" + id + "/leave", leave, "Idempotency-Key", "l-1");
        assertEquals(200, secondLeave.statusCode(), secondLeave.body());
        assertEquals(Api.json(firstLeave), Api.json(secondLeave));
        assertEquals(2, Api.json(api.get("/employments/" + id)).get("terms").size());
    }
}
