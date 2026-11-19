package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.KeycloakEnvironment.update;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.waitingFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteLocksIT {

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return response.body().isEmpty() ? null : Api.json(response);
    }

    private static long version(Api api, String path) throws Exception {
        return ok(api.get(path)).get("version").asLong();
    }

    // The update holds the row until the write has waited for it.
    private static HttpResponse<String> afterHolding(String sql, String id, Callable<HttpResponse<String>> write)
            throws Exception {
        CompletableFuture<HttpResponse<String>> later;
        try (Connection first = KeycloakEnvironment.connection()) {
            first.setAutoCommit(false);
            update(first, sql, id);
            later = waitingFor(first, write);
            first.commit();
        }
        return later.get();
    }

    @Test
    void aTransferOfEveryoneReadsTheEmploymentsAfterAnotherWrite() throws Exception {
        TestRealm realm = TestRealm.create("locks-transfers");
        Api api = realm.api;
        String first = realm.legalEntity("法人");
        String unit = ok(api.post("/org-units", "{\"name\": \"病棟\", \"validFrom\": \"2026-04-01\"}")).get("id")
                .asText();
        JsonNode staff = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}"));
        String employment = staff.get("employment").get("id").asText();
        String second = realm.legalEntity("第二法人");

        // Another write holds the employment and gives it a staff number.
        JsonNode moved = ok(afterHolding("update fuhito_employment set version = version + 1, staff_number = 'N-2' "
                + "where id = ?", employment, () -> api.post("/employments/transfers", "{\"fromEmployerId\": \""
                + first + "\", \"toEmployerId\": \"" + second + "\", \"validFrom\": \"2027-04-01\", "
                + "\"keepStaffNumbers\": true}")));

        // The transfer read the employment after that write. So the staff number stays, and the version goes on.
        JsonNode ended = ok(api.get("/employments/" + employment));
        assertEquals(3, ended.get("version").asLong());
        assertEquals("N-2", ended.get("staffNumber").asText());
        String started = moved.get("employments").get(0).get("startedEmploymentId").asText();
        assertEquals("N-2", ok(api.get("/employments/" + started)).get("staffNumber").asText());
    }

    @Test
    void anEndOfAnEmploymentReadsItsAssignmentsAfterAnotherWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("locks-end");
        Api api = realm.api;
        String unit = ok(api.post("/org-units", "{\"name\": \"病棟\", \"validFrom\": \"2026-04-01\"}")).get("id")
                .asText();
        JsonNode staff = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2026-04-01\"}"));
        String employment = staff.get("employment").get("id").asText();
        String assignment = staff.get("assignment").get("id").asText();

        ok(afterHolding("update fuhito_assignment set version = version + 1 where assignment_id = ?", assignment,
                () -> api.put("/employments/" + employment + "/end", "{\"version\": 1, \"validTo\": \"2027-04-01\"}")));

        assertEquals(3, version(api, "/assignments/" + assignment));
    }

    @Test
    void aSwitchOfThePrimaryAssignmentReadsTheOtherAssignmentsAfterAnotherWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("locks-primary");
        Api api = realm.api;
        String unit = ok(api.post("/org-units", "{\"name\": \"病棟\", \"validFrom\": \"2026-04-01\"}")).get("id")
                .asText();
        String employment = ok(api.post("/employments", "{\"userId\": \"" + realm.user("nurse")
                + "\", \"validFrom\": \"2026-04-01\"}")).get("id").asText();
        String assignment = "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + unit
                + "\", \"validFrom\": \"2026-04-01\", \"primary\": %s}";
        String primary = ok(api.post("/assignments", assignment.formatted("true"))).get("id").asText();
        String other = ok(api.post("/assignments", assignment.formatted("false"))).get("id").asText();

        ok(afterHolding("update fuhito_assignment set version = version + 1 where assignment_id = ?", primary,
                () -> api.put("/assignments/" + other + "/details", "{\"version\": 1, \"validFrom\": \"2027-04-01\", "
                        + "\"primary\": true, \"switchPrimary\": true}")));

        assertEquals(3, version(api, "/assignments/" + primary));
    }

    @Test
    void anIdOfAnotherSystemReadsItsResourceAfterAnotherWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("locks-external-ids");
        Api api = realm.api;
        String person = ok(api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        String hold = "update fuhito_person set version = version + 1, name = 'Bob' where id = ?";

        ok(afterHolding(hold, person, () -> api.put("/external-ids", "{\"resourceType\": \"PERSON\", "
                + "\"resourceId\": \"" + person + "\", \"source\": \"payroll\", \"externalId\": \"X-1\"}")));
        assertEquals(204, afterHolding(hold, person, () -> api.delete("/external-ids?resourceType=PERSON&resourceId="
                + person + "&source=payroll&version=1")).statusCode());

        JsonNode read = ok(api.get("/people/" + person));
        assertEquals(5, read.get("version").asLong());
        assertEquals("Bob", read.get("name").asText());
    }

    @Test
    void aMaskReadsTheRecordsOfThePersonAfterAnotherWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("locks-mask");
        Api api = realm.api;
        String person = ok(api.post("/people", "{\"name\": \"Ann\"}")).get("id").asText();
        String employment = ok(api.post("/employments", "{\"personId\": \"" + person + "\", "
                + "\"validFrom\": \"2020-04-01\", \"validTo\": \"2021-04-01\"}")).get("id").asText();
        String user = realm.user("leaver");
        String userPerson = ok(api.post("/people", "{\"userId\": \"" + user + "\"}")).get("id").asText();
        String hold = "update fuhito_employment set version = version + 1 where id = ?";

        ok(afterHolding(hold, employment, () -> api.post("/people/" + person + "/mask", "{\"version\": 1}")));
        assertEquals(3, version(api, "/employments/" + employment));
        JsonNode mask = ok(afterHolding("update fuhito_person set version = version + 1 where id = ?", userPerson,
                () -> api.post("/users/" + user + "/mask", "")));
        assertEquals(3, mask.get("version").asLong());
    }

    @Test
    void aRemovalOfAUserReadsItsEmploymentsAfterAnotherWrite() throws Exception {
        TestRealm realm = TestRealm.withEmployer("locks-user-removal");
        Api api = realm.api;
        String user = realm.user("removed");
        String employment = ok(api.post("/employments", "{\"userId\": \"" + user + "\", "
                + "\"validFrom\": \"2020-04-01\"}")).get("id").asText();

        afterHolding("update fuhito_employment set version = version + 1 where id = ?", employment, () -> {
            realm.removeUser(user);
            return null;
        });

        // The removal ended the employment, and counted up the version that the other write left.
        assertEquals(3, version(api, "/employments/" + employment));
    }

    @Test
    void aReorganizationLocksItsUnitsInTheOrderOfTheirIds() throws Exception {
        TestRealm realm = TestRealm.withEmployer("locks-reorganization");
        Api api = realm.api;
        List<String> units = new ArrayList<>();
        for (String name : List.of("A", "B")) {
            units.add(ok(api.post("/org-units", "{\"name\": \"" + name + "\", \"validFrom\": \"2026-04-01\"}"))
                    .get("id").asText());
        }
        String low = Collections.min(units);
        String high = Collections.max(units);
        String change = "{\"op\": \"change\", \"id\": \"%s\", \"version\": 1, \"name\": \"C\"}";

        CompletableFuture<HttpResponse<String>> reorganization;
        try (Connection first = KeycloakEnvironment.connection()) {
            first.setAutoCommit(false);
            // Another write holds the unit with the higher ID, and the first step names that unit.
            update(first, "update fuhito_org_unit set version = version where id = ?", high);
            reorganization = waitingFor(first, () -> api.post("/reorganizations", "{\"effectiveDate\": "
                    + "\"2027-04-01\", \"steps\": [" + change.formatted(high) + ", " + change.formatted(low) + "]}"));
            // The reorganization holds the unit with the lower ID already, though its step comes later.
            assertTrue(KeycloakEnvironment.sqlError("select id from fuhito_org_unit where id = '" + low
                    + "' for update nowait").contains("could not obtain lock"));
            first.commit();
        }
        ok(reorganization.get());
    }
}
