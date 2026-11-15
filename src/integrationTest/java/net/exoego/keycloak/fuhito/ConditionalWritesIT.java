package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
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

        // A mask and an erase of a person are writes to the person too.
        String person = Api.json(api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        String ofPerson = etag(api.get("/people/" + person));
        assertEquals(200, api.put("/people/" + person, "{\"name\": \"Anne\"}").statusCode());
        assertEquals(412, api.post("/people/" + person + "/mask", "", "If-Match", ofPerson).statusCode());
        assertEquals(412, api.delete("/people/" + person + "/data", "If-Match", ofPerson).statusCode());
        assertEquals("Anne", Api.json(api.get("/people/" + person)).get("name").asText());
        String current = etag(api.get("/people/" + person));
        HttpResponse<String> masked = api.post("/people/" + person + "/mask", "", "If-Match", current);
        assertEquals(200, masked.statusCode(), masked.body());
        assertEquals(204, api.delete("/people/" + person + "/data", "If-Match", etag(masked)).statusCode());
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
        // The query and the caller are part of the request too.
        String order = Api.json(api.post("/change-orders", "{\"title\": \"内示\", \"effectiveDate\": \"2026-04-01\"}"))
                .get("id").asText();
        assertEquals("idempotency.key_reused", Api.json(api.post("/org-units?changeOrder=" + order, body,
                "Idempotency-Key", "order-1")).get("error").asText());
        Api other = PermissionsIT.administrator(KEYCLOAK.getKeycloakAdminClient(), realm.name, "realm-management",
                "other-writer", "manage-users");
        assertEquals("idempotency.key_reused", Api.json(other.post("/org-units", body, "Idempotency-Key", "order-1"))
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
