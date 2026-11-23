package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restore takes a version that a write can count up, and a count-up of the largest long fails.
 */
class VersionLimitsIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() < 300, response.statusCode() + " " + response.body());
        return Api.json(response);
    }

    private static long version(HttpResponse<String> response) throws Exception {
        return ok(response).get("version").asLong();
    }

    private static RestoreRulesIT.Case badVersion(String list, String id, String pointer, String version) {
        return RestoreRulesIT.breaks(list, id, "version", null, RestoreRulesIT.at(pointer, "{\"version\": " + version
                + "}"));
    }

    // A write gives 1 to a new resource and counts it up by one. A client in JavaScript reads every version up to
    // 9007199254740991 exactly.
    @Test
    void aRestoreTakesAVersionFromOneTo9007199254740991() throws Exception {
        RestoreRulesIT.assertEachBreaks("versions-range", List.of(
                badVersion("hierarchies", "h-1", "/hierarchies/0", "0"),
                badVersion("hierarchies", "h-1", "/hierarchies/0", "-1"),
                badVersion("hierarchies", "h-1", "/hierarchies/0", "9007199254740992"),
                badVersion("hierarchies", "h-1", "/hierarchies/0", "9223372036854775807"),
                badVersion("ref-kinds", "k-1", "/refKinds/0", "0"),
                badVersion("ref-items", "role-1", "/refItems/0", "9007199254740992"),
                badVersion("attribute-definitions", "d-1", "/attributeDefinitions/0", "0"),
                badVersion("org-units", "u-1", "/orgUnits/0", "0"),
                badVersion("people", "p-1", "/people/0", "0"),
                badVersion("employments", "e-1", "/employments/0", "0"),
                badVersion("assignments", "a-1", "/assignments/0", "0"),
                badVersion("unit-roles", "ur-1", "/unitRoles/0", "0"),
                badVersion("external-ids", "x-1", "/externalIds/0", "0"),
                badVersion("change-orders", "o-1", "/changeOrders/0", "0")));

        TestRealm target = RestoreKeysIT.target("versions-limits");
        try {
            ObjectNode document = RestoreKeysIT.document();
            RestoreKeysIT.change(document, "/hierarchies/0", "{\"version\": 9007199254740991}");
            RestoreKeysIT.change(document, "/people/0", "{\"version\": 1}");
            ok(target.api.post("/restore", JSON.writeValueAsString(document)));
            assertEquals(List.of(9007199254740991L, 1L), List.of(version(target.api.get("/hierarchies/h-1")),
                    version(target.api.get("/people/p-1"))));
            assertEquals(9007199254740992L, version(target.api.put("/hierarchies/h-1",
                    "{\"code\": \"project\", \"name\": \"Race\", \"version\": 9007199254740991}")));
        } finally {
            target.remove();
        }
    }

    // No write gives such a version. A version that wraps around would be one that a write gave long before.
    @Test
    void aVersionAtTheLargestLongFailsToCountUpAndNothingIsWritten() throws Exception {
        TestRealm realm = TestRealm.withEmployer("versions-largest");
        Api api = realm.api;
        String hierarchy = ok(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}")).get("id")
                .asText();
        String unit = ok(api.post("/org-units", "{\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": "
                + "\"病院\"}}, \"validFrom\": \"2020-04-01\"}")).get("id").asText();
        String assignment = ok(api.post("/staff", "{\"userId\": \"" + realm.user("nurse") + "\", \"orgUnitId\": \""
                + unit + "\", \"validFrom\": \"2020-04-01\"}")).get("assignment").get("id").asText();
        String payroll = "{\"resourceType\": \"ORG_UNIT\", \"resourceId\": \"" + unit
                + "\", \"source\": \"payroll\", \"externalId\": \"%s\"}";
        ok(api.putWithVersion("/external-ids", payroll.formatted("P-1")));
        KeycloakEnvironment.sql("update fuhito_hierarchy set version = " + Long.MAX_VALUE + " where id = '"
                + hierarchy + "'");
        KeycloakEnvironment.sql("update fuhito_assignment_version set version = " + Long.MAX_VALUE
                + " where id = '" + assignment + "'");
        KeycloakEnvironment.sql("update fuhito_external_id set version = " + Long.MAX_VALUE
                + " where resource_id = '" + unit + "' and source = 'payroll'");
        ObjectNode before = RealmExportIT.export(api);

        List<Integer> statuses = List.of(
                api.put("/hierarchies/" + hierarchy, "{\"code\": \"project\", \"name\": \"Q\", \"version\": "
                        + Long.MAX_VALUE + "}").statusCode(),
                api.putWithVersion("/assignments/" + assignment + "/details",
                        "{\"validFrom\": \"2021-04-01\", \"displayOrder\": 1}").statusCode(),
                api.putWithVersion("/external-ids", payroll.formatted("P-2")).statusCode());

        assertEquals(List.of(500, 500, 500), statuses);
        assertEquals(before, RealmExportIT.export(api));
        assertEquals(Long.MAX_VALUE, version(api.get("/hierarchies/" + hierarchy)));
    }
}
