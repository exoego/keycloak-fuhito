package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PostgreSQL cannot hold NUL in a text, so no resource has an ID with it.
 */
class NulPathIdsIT {

    // The path of each value, and the value that the extension reads from it.
    private static final Map<String, String> IDS = Map.of("nope", "nope", "%00", "\0", "a%00b", "a\0b");

    private static final Map<String, String> NOT_FOUND = Map.ofEntries(
            Map.entry("assignments", "assignment.not_found"),
            Map.entry("attribute-definitions", "attribute.not_found"),
            Map.entry("change-orders", "change_order.not_found"),
            Map.entry("employments", "employment.not_found"),
            Map.entry("hierarchies", "hierarchy.not_found"),
            Map.entry("org-units", "org_unit.not_found"),
            Map.entry("people", "person.not_found"),
            Map.entry("qualifications", "qualification.not_found"),
            Map.entry("ref-items", "ref_item.not_found"),
            Map.entry("ref-kinds", "ref_kind.not_found"),
            Map.entry("reporting-lines", "reporting_line.not_found"),
            Map.entry("unit-roles", "unit_role.not_found"),
            Map.entry("users", "user.not_found"));

    // Every call with an ID in its path. A write gets an empty body, as the ID comes before the fields of the body.
    private static final List<String> CALLS = List.of(
            "GET /assignments/{}", "DELETE /assignments/{}", "PUT /assignments/{}/details",
            "PUT /assignments/{}/end", "DELETE /assignments/{}/end", "POST /assignments/{}/move",
            "GET /attribute-definitions/{}", "PUT /attribute-definitions/{}", "DELETE /attribute-definitions/{}",
            "GET /change-orders/{}", "PUT /change-orders/{}", "DELETE /change-orders/{}",
            "POST /change-orders/{}/announce", "GET /change-orders/{}/changes", "GET /change-orders/{}/changes/count",
            "GET /employments/{}", "DELETE /employments/{}", "PUT /employments/{}/end", "DELETE /employments/{}/end",
            "POST /employments/{}/leave", "POST /employments/{}/promotion", "POST /employments/{}/return",
            "PUT /employments/{}/terms", "POST /employments/{}/transfer",
            "GET /hierarchies/{}", "PUT /hierarchies/{}", "DELETE /hierarchies/{}",
            "GET /org-units/{}", "PUT /org-units/{}", "DELETE /org-units/{}", "GET /org-units/{}/children",
            "GET /org-units/{}/children/count", "PUT /org-units/{}/end", "DELETE /org-units/{}/end",
            "GET /org-units/{}/history", "GET /org-units/{}/members", "GET /org-units/{}/members/count",
            "PUT /org-units/{}/organization", "DELETE /org-units/{}/organization", "PUT /org-units/{}/parent",
            "GET /org-units/{}/path", "GET /org-units/{}/role-holders", "GET /org-units/{}/role-holders/count",
            "GET /org-units/{}/subtree", "GET /org-units/{}/subtree/count", "GET /org-units/{}/succession",
            "PUT /org-units/{}/time-settings",
            "GET /people/{}", "PUT /people/{}", "DELETE /people/{}", "GET /people/{}/assignments",
            "DELETE /people/{}/data", "GET /people/{}/history", "GET /people/{}/managers", "POST /people/{}/mask",
            "POST /people/{}/mask?clearKeycloakName=true",
            "GET /people/{}/qualifications", "GET /people/{}/reports", "GET /people/{}/reports/count",
            "GET /people/{}/unit-roles", "GET /people/{}/unit-roles/count",
            "GET /qualifications/{}", "PUT /qualifications/{}", "DELETE /qualifications/{}",
            "GET /ref-items/{}", "PUT /ref-items/{}", "DELETE /ref-items/{}", "GET /ref-items/{}/history",
            "PUT /ref-kinds/{}", "DELETE /ref-kinds/{}",
            "GET /reporting-lines/{}", "PUT /reporting-lines/{}/end", "DELETE /reporting-lines/{}",
            "GET /unit-roles/{}", "PUT /unit-roles/{}/end", "DELETE /unit-roles/{}",
            "GET /users/{}/assignments", "DELETE /users/{}/data", "GET /users/{}/history",
            "GET /users/{}/managers", "POST /users/{}/mask", "POST /users/{}/mask?clearKeycloakName=true",
            "GET /users/{}/qualifications",
            "GET /users/{}/reports", "GET /users/{}/reports/count", "GET /users/{}/unit-roles",
            "GET /users/{}/unit-roles/count");

    @Test
    void anIdWithNulNamesNoResourceInEveryCall() throws Exception {
        Api api = TestRealm.create("nul-path").api;
        for (String call : CALLS) {
            String[] methodAndPath = call.split(" ", 2);
            String collection = methodAndPath[1].split("/", -1)[1];
            String param = switch (collection) {
                case "users" -> "userId";
                case "ref-kinds" -> "code";
                default -> "id";
            };
            for (Map.Entry<String, String> id : IDS.entrySet()) {
                String path = methodAndPath[1].replace("{}", id.getKey());
                HttpResponse<String> response = switch (methodAndPath[0]) {
                    case "GET" -> api.get(path);
                    case "PUT" -> api.put(path, "{}");
                    case "POST" -> api.post(path, "{}");
                    default -> api.delete(path);
                };
                String what = call + " with " + id.getKey();
                assertEquals(404, response.statusCode(), what + " " + response.body());
                JsonNode error = Api.json(response);
                assertEquals(NOT_FOUND.get(collection), error.get("error").asText(), what);
                assertEquals(id.getValue(), error.get("params").get(param).asText(), what);
            }
        }
    }

    // A list is named by a fixed name, so its name is no ID.
    @Test
    void aListNameWithNulIsAnUnknownList() throws Exception {
        Api api = TestRealm.create("nul-list").api;
        for (String nul : List.of("%00", "a%00b")) {
            assertSameError(api.get("/export/nope"), api.get("/export/" + nul));
            assertSameError(api.get("/imports/csv/nope"), api.get("/imports/csv/" + nul));
            byte[] csv = "id\n".getBytes(StandardCharsets.UTF_8);
            assertSameError(api.postBytes("/imports/csv/nope?effectiveDate=2026-04-01", "text/csv", csv),
                    api.postBytes("/imports/csv/" + nul + "?effectiveDate=2026-04-01", "text/csv", csv));
        }
    }

    private static void assertSameError(HttpResponse<String> unknown, HttpResponse<String> nul) throws Exception {
        assertEquals(unknown.statusCode(), nul.statusCode(), nul.body());
        assertEquals(Api.json(unknown).get("error"), Api.json(nul).get("error"), nul.body());
    }
}
