package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalWritesIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return response.body().isEmpty() ? null : Api.json(response);
    }

    private static JsonNode error(int status, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static long version(Api api, String path) throws Exception {
        return ok(api.get(path)).get("version").asLong();
    }

    @Test
    void aWriteNeedsTheVersionThatTheCallerRead() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-required");
        Api api = realm.api;
        String id = ok(api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String terms = "/employments/" + id + "/terms";
        assertEquals(1, version(api, "/employments/" + id));

        // A write with no version fails, and writes nothing.
        JsonNode none = error(400, api.put(terms, "{\"validFrom\": \"2026-10-01\", \"workPercent\": 50}"));
        assertEquals("version.required", none.get("error").asText());
        assertEquals("The write needs the version of the resource that the caller read. Read the resource, and "
                + "send its version with the write.", none.get("message").asText());
        assertEquals(1, version(api, "/employments/" + id));

        // The version that the caller read lets the write go on, and the write counts it up.
        JsonNode changed = ok(api.put(terms, "{\"version\": 1, \"validFrom\": \"2026-10-01\", \"workPercent\": 50}"));
        assertEquals(2, changed.get("version").asLong());
        assertEquals(2, changed.get("terms").size());

        // A second write with the same version fails, and the error has the version of now.
        JsonNode old = error(409, api.put(terms, "{\"version\": 1, \"validFrom\": \"2026-10-01\", \"workPercent\": 80}"));
        assertEquals("version.conflict", old.get("error").asText());
        assertEquals("{\"version\":\"2\"}", old.get("params").toString());
        assertEquals("読み込んだ後にリソースが変わりました。読み直して、いまのバージョンで書き込み直してください。",
                Api.json(api.withAcceptLanguage("ja").put(terms, "{\"version\": 1, \"validFrom\": \"2026-10-01\"}"))
                        .get("message").asText());
        assertEquals("50", ok(api.get("/employments/" + id)).get("terms").get(1).get("workPercent").asText());
        assertEquals(2, version(api, "/employments/" + id));
        // A newer version than the one stored is not the one stored either.
        assertEquals("version.conflict", error(409, api.put(terms, "{\"version\": 3, \"validFrom\": \"2026-10-01\"}"))
                .get("error").asText());

        // A missing body and an unknown resource fail as before.
        assertEquals("invalid.required", error(400, api.put(terms, "")).get("error").asText());
        assertEquals("employment.not_found", error(404, api.put("/employments/nope/terms",
                "{\"validFrom\": \"2026-10-01\"}")).get("error").asText());

        // A DELETE names the version in the query.
        assertEquals(200, api.put("/employments/" + id + "/end", "{\"version\": 2, \"validTo\": \"2027-04-01\"}")
                .statusCode());
        String end = "/employments/" + id + "/end";
        assertEquals("version.required", error(400, api.delete(end)).get("error").asText());
        // A version that is no number is no version.
        assertEquals("version.required", error(400, api.delete(end + "?version=x")).get("error").asText());
        assertEquals("version.conflict", error(409, api.delete(end + "?version=2")).get("error").asText());
        assertEquals(4, ok(api.delete(end + "?version=3")).get("version").asLong());
        String other = ok(api.post("/employments", "{\"userId\": \"" + realm.user("clerk")
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        assertEquals("version.required", error(400, api.delete("/employments/" + other)).get("error").asText());
        assertEquals("version.conflict", error(409, api.delete("/employments/" + other + "?version=2"))
                .get("error").asText());
        assertEquals(200, api.get("/employments/" + other).statusCode());
        assertEquals(204, api.delete("/employments/" + other + "?version=1").statusCode());
        assertEquals(404, api.get("/employments/" + other).statusCode());

        // A mask and an erase of a person are writes of the person too. A mask has only the version in its body.
        String person = ok(api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        assertEquals(2, ok(api.put("/people/" + person, "{\"version\": 1, \"name\": \"Anne\"}")).get("version")
                .asLong());
        assertEquals("invalid.required", error(400, api.post("/people/" + person + "/mask", "")).get("error")
                .asText());
        assertEquals("version.required", error(400, api.post("/people/" + person + "/mask", "{}")).get("error")
                .asText());
        assertEquals("version.conflict", error(409, api.post("/people/" + person + "/mask", "{\"version\": 1}"))
                .get("error").asText());
        assertEquals("version.required", error(400, api.delete("/people/" + person + "/data")).get("error")
                .asText());
        assertEquals("version.conflict", error(409, api.delete("/people/" + person + "/data?version=1"))
                .get("error").asText());
        assertEquals("Anne", ok(api.get("/people/" + person)).get("name").asText());
        JsonNode masked = ok(api.post("/people/" + person + "/mask", "{\"version\": 2}"));
        assertEquals(3, masked.get("version").asLong());
        assertEquals(204, api.delete("/people/" + person + "/data?version=3").statusCode());

        // An announcement of a change order has only the version in its body.
        String order = ok(api.post("/change-orders", "{\"title\": \"内示\", \"effectiveDate\": \"2026-04-01\"}"))
                .get("id").asText();
        assertEquals("version.required", error(400, api.post("/change-orders/" + order + "/announce", "{}"))
                .get("error").asText());
        assertFalse(ok(api.get("/change-orders/" + order)).has("announcedAt"));
        assertEquals(2, ok(api.post("/change-orders/" + order + "/announce", "{\"version\": 1}")).get("version")
                .asLong());
    }

    @Test
    void anOperationThatChangesSeveralResourcesTakesTheVersionOfItsPath() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-transfer");
        Api api = realm.api;
        String unit = ok(api.post("/org-units", "{\"name\": \"病棟\", \"validFrom\": \"2026-04-01\"}")).get("id")
                .asText();
        JsonNode staff = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}"));
        String second = realm.legalEntity("第二法人");
        String employment = staff.get("employment").get("id").asText();
        String assignment = staff.get("assignment").get("id").asText();
        // The assignment changes on its own, so its version is not the one of the employment.
        ok(api.put("/assignments/" + assignment + "/details", "{\"version\": 1, \"validFrom\": \"2026-10-01\", "
                + "\"displayOrder\": 2}"));
        ok(api.put("/assignments/" + assignment + "/details", "{\"version\": 2, \"validFrom\": \"2026-12-01\", "
                + "\"displayOrder\": 3}"));
        String transfer = "/employments/" + employment + "/transfer";
        String body = "\"employerId\": \"" + second + "\", \"validFrom\": \"2027-04-01\"}";

        assertEquals("version.required", error(400, api.post(transfer, "{" + body)).get("error").asText());
        assertEquals("version.conflict", error(409, api.post(transfer, "{\"version\": 3, " + body)).get("error")
                .asText());
        // The version of the employment is enough, though the transfer also ends the assignment.
        JsonNode moved = ok(api.post(transfer, "{\"version\": 1, " + body));
        assertEquals(2, moved.get("ended").get("version").asLong());
        assertEquals(4, version(api, "/assignments/" + assignment));
        assertEquals(1, moved.get("assignments").size());
        // The same request again has an old version now.
        JsonNode again = error(409, api.post(transfer, "{\"version\": 1, " + body));
        assertEquals("2", again.get("params").get("version").asText());
    }

    @Test
    void aStoredIdOfAnotherSystemNeedsItsVersionAndANewOneNone() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-external-ids");
        Api api = realm.api;
        String person = ok(api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        String payroll = "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + person
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"%s}";

        assertEquals(1, ok(api.put("/external-ids", payroll.formatted("X-1", ""))).get("version").asLong());
        assertEquals("version.required", error(400, api.put("/external-ids", payroll.formatted("X-2", "")))
                .get("error").asText());
        assertEquals("version.conflict", error(409, api.put("/external-ids",
                payroll.formatted("X-2", ", \"version\": 2"))).get("error").asText());
        assertEquals(2, ok(api.put("/external-ids", payroll.formatted("X-2", ", \"version\": 1"))).get("version")
                .asLong());
        String delete = "/external-ids?resourceType=PERSON&resourceId=" + person + "&source=payroll";
        assertEquals("version.required", error(400, api.delete(delete)).get("error").asText());
        assertEquals("version.conflict", error(409, api.delete(delete + "&version=1")).get("error").asText());
        assertEquals(204, api.delete(delete + "&version=2").statusCode());
        assertEquals("external_id.not_found", error(404, api.delete(delete + "&version=2")).get("error").asText());
    }

    // Starts a write, waits until the database makes it wait for the lock of the connection, and returns it.
    private static CompletableFuture<HttpResponse<String>> waitingFor(Connection holder,
            Callable<HttpResponse<String>> write) throws Exception {
        String pid;
        try (Statement statement = holder.createStatement();
                ResultSet row = statement.executeQuery("select pg_backend_pid()")) {
            row.next();
            pid = row.getString(1);
        }
        CompletableFuture<HttpResponse<String>> started = CompletableFuture.supplyAsync(() -> {
            try {
                return write.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        String waiting = "0";
        for (int i = 0; i < 300 && waiting.equals("0"); i++) {
            Thread.sleep(100);
            waiting = KeycloakEnvironment.sql("select count(*) from pg_stat_activity where " + pid
                    + " = any(pg_blocking_pids(pid))");
        }
        assertNotEquals("0", waiting, "the write never waited");
        return started;
    }

    private static void update(Connection connection, String sql, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test
    void aSecondWriteWithTheSameVersionWaitsAndFails() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-race");
        Api api = realm.api;
        String unit = ok(api.post("/org-units", "{\"name\": \"1病棟\", \"validFrom\": \"2026-04-01\"}")).get("id")
                .asText();

        CompletableFuture<HttpResponse<String>> second;
        try (Connection first = KeycloakEnvironment.connection()) {
            // The first write holds the row of the unit, and counts up its version.
            first.setAutoCommit(false);
            update(first, "update fuhito_org_unit set version = version + 1 where id = ?", unit);
            second = waitingFor(first, () -> api.put("/org-units/" + unit,
                    "{\"version\": 1, \"name\": \"第1病棟\", \"validFrom\": \"2026-04-01\"}"));
            first.commit();
        }

        JsonNode result = error(409, second.get());
        assertEquals("version.conflict", result.get("error").asText());
        assertEquals("2", result.get("params").get("version").asText());
        assertEquals("1病棟", ok(api.get("/org-units/" + unit)).get("name").asText());

        // An assignment has a row for each period, and the write locks all of them.
        String assignment = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}")).get("assignment").get("id").asText();
        assertEquals(200, api.put("/assignments/" + assignment + "/end", "{\"version\": 1, \"validTo\": \"2027-04-01\"}")
                .statusCode());
    }

    @Test
    void aRowThatAnotherWriteChangedAfterTheCheckFailsWithAConflict() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-row-race");
        Api api = realm.api;
        String person = ok(api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        String payroll = "{\"resourceType\": \"PERSON\", \"resourceId\": \"" + person
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"%s}";
        ok(api.put("/external-ids", payroll.formatted("X-1", "")));
        String row = KeycloakEnvironment.sql("select id from fuhito_external_id where resource_id = '" + person + "'");

        CompletableFuture<HttpResponse<String>> second;
        try (Connection first = KeycloakEnvironment.connection()) {
            // The write reads the row before the other one commits, and finds the version that it names.
            first.setAutoCommit(false);
            update(first, "update fuhito_external_id set version = version + 1 where id = ?", row);
            second = waitingFor(first, () -> api.put("/external-ids", payroll.formatted("X-2", ", \"version\": 1")));
            first.commit();
        }

        JsonNode result = error(409, second.get());
        assertEquals("version.conflict", result.get("error").asText());
        assertFalse(result.path("params").has("version"), result.toString());
        assertEquals(List.of("payroll=X-1"), Api.externalIds(ok(api.get("/people/" + person))));
    }

    @Test
    void theImportAndTheSyncNeedNoVersion() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-import");
        Api api = realm.api;
        String unit = ok(api.post("/org-units", "{\"name\": \"病院\", \"validFrom\": \"2026-04-01\", "
                + "\"source\": \"sheet\", \"externalId\": \"H\"}")).get("id").asText();

        ok(api.post("/imports", """
                {"effectiveDate": "2027-04-01", "source": "sheet", "orgUnits": [
                  {"externalId": "H", "name": "総合病院"}
                ]}"""));
        assertEquals(2, version(api, "/org-units/" + unit + "/history"));
        HttpResponse<String> csv = api.postBytes("/imports/csv/org-units?effectiveDate=2028-04-01",
                "text/csv; charset=UTF-8", ("ID,名称\r\n" + unit + ",大学病院\r\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(200, csv.statusCode(), csv.body());
        assertEquals(3, version(api, "/org-units/" + unit + "/history"));
        assertEquals("大学病院", ok(api.get("/org-units/" + unit + "?asOf=2028-04-01")).get("name").asText());
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

        assertEquals(204, api.delete("/people/" + person + "/data?version=1").statusCode());

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
        assertEquals(1, Api.json(api.get("/org-units?q=1%E7%97%85%E6%A3%9F&asOf=2026-05-01")).size());
        assertEquals(422, api.post("/org-units", "{\"name\": \"2病棟\", \"validFrom\": \"2026-04-01\"}",
                "Idempotency-Key", "order-1").statusCode());
        assertEquals("idempotency.bad_key", Api.json(api.post("/org-units", body, "Idempotency-Key", " "))
                .get("error").asText());
        // The query and the caller are part of the request too.
        String order = Api.json(api.post("/change-orders", "{\"title\": \"内示\", \"effectiveDate\": \"2026-04-01\"}"))
                .get("id").asText();
        assertEquals("idempotency.key_reused", Api.json(api.post("/org-units?changeOrder=" + order, body,
                "Idempotency-Key", "order-1")).get("error").asText());
        Api other = PermissionsIT.administrator(KEYCLOAK.getKeycloakAdminClient(), realm.name, "realm-management",
                "other-writer", "fuhito-manage");
        assertEquals("idempotency.key_reused", Api.json(other.post("/org-units", body, "Idempotency-Key", "order-1"))
                .get("error").asText());

        // An operation that answers with a resource returns the same one again, though its version is old now.
        HttpResponse<String> employment = api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}");
        String id = Api.json(employment).get("id").asText();
        String leave = "{\"version\": 1, \"validFrom\": \"2026-10-01\"}";
        HttpResponse<String> firstLeave = api.post("/employments/" + id + "/leave", leave, "Idempotency-Key", "l-1");
        HttpResponse<String> secondLeave = api.post("/employments/" + id + "/leave", leave, "Idempotency-Key", "l-1");
        assertEquals(200, secondLeave.statusCode(), secondLeave.body());
        assertEquals(Api.json(firstLeave), Api.json(secondLeave));
        assertEquals(2, Api.json(api.get("/employments/" + id)).get("terms").size());
        assertEquals(2, version(api, "/employments/" + id));
    }
}
