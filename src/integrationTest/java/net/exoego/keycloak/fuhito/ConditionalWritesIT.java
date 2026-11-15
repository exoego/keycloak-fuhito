package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
