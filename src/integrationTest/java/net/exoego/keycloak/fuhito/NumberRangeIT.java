package net.exoego.keycloak.fuhito;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static net.exoego.keycloak.fuhito.KeycloakEnvironment.KEYCLOAK;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A whole number outside the range of its field fails with {@code invalid.out_of_range}, which names the field with
 * its path and the limits. A number longer than Jackson reads fails with {@code invalid.too_long}. Keycloak would
 * answer both with an error that names no field.
 */
class NumberRangeIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String INT = " -2147483648 2147483647";
    private static final String LONG = " -9223372036854775808 9223372036854775807";
    private static final String DIGITS = "9".repeat(1001);
    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"甲\"}}";

    private static final String SETUP = """
            {"effectiveDate": "2026-04-01",
             "refItems": [{"externalId": "I1", "kind": "JOB_TITLE", "code": "chief", "primaryLanguage": "ja",
                           "names": {"ja": {"name": "主任"}}}],
             "hierarchies": [{"externalId": "R1", "code": "project", "name": "プロジェクト"}],
             "orgUnits": [
               {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validFrom": "2020-04-01"},
               {"externalId": "W", "code": "w", "primaryLanguage": "ja", "names": {"ja": {"name": "病棟"}},
                "parentExternalId": "H", "validFrom": "2020-04-01"}],
             "people": [{"externalId": "P1", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}}],
             "employments": [{"externalId": "E1", "personExternalId": "P1", "validFrom": "2020-04-01",
                              "workPercent": 80}],
             "assignments": [
               {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "W", "validFrom": "2020-04-01",
                "allocationPercent": 50}]}
            """;

    // The status, the error, the field, and the limits of a response, or the status and the body of another one.
    private static String failure(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 300) {
            return String.valueOf(response.statusCode());
        }
        JsonNode params = Api.json(response).path("params");
        if (!params.has("field")) {
            return response.statusCode() + " " + response.body();
        }
        StringBuilder failure = new StringBuilder(response.statusCode() + " " + Api.json(response).get("error").asText()
                + " " + params.get("field").asText());
        for (String limit : List.of("min", "max")) {
            if (params.has(limit)) {
                failure.append(' ').append(params.get(limit).asText());
            }
        }
        return failure.toString();
    }

    private static String lookup(Api api, String resourceType, String externalId) throws Exception {
        return Api.json(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    @Test
    void aRestWriteFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("range-rest");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        String hospital = lookup(api, "ORG_UNIT", "H");
        String ward = lookup(api, "ORG_UNIT", "W");
        String person = lookup(api, "PERSON", "P1");
        String employment = lookup(api, "EMPLOYMENT", "E1");
        String hierarchy = "/hierarchies/" + lookup(api, "HIERARCHY", "R1");
        ObjectNode before = RealmExportIT.export(api);
        String refItem = "{\"kind\": \"JOB_TITLE\", \"code\": \"%s\", " + NAMES + ", \"displayOrder\": %s}";
        String rename = "{\"code\": \"project\", \"name\": \"計画\", \"version\": %s}";
        String orgUnit = "{" + NAMES + ", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2026-05-01\"%s}";
        String assignment = "{\"employmentId\": \"" + employment + "\", \"orgUnitId\": \"" + ward + "\", "
                + "\"validFrom\": \"2026-05-01\"%s}";
        String newEmployment = "{\"personId\": \"" + person + "\", \"validFrom\": \"2026-05-01\"%s}";

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.post("/ref-items", refItem.formatted("a", "99999999999"))));
        failures.add(failure(api.post("/ref-items", refItem.formatted("a", "2147483648"))));
        failures.add(failure(api.post("/ref-items", refItem.formatted("a", "-2147483649"))));
        failures.add(failure(api.post("/hierarchies", "{\"code\": \"b\", \"name\": \"n\", "
                + "\"version\": 99999999999999999999}")));
        failures.add(failure(api.put(hierarchy, rename.formatted("9223372036854775808"))));
        failures.add(failure(api.put(hierarchy, rename.formatted("-9223372036854775809"))));
        failures.add(failure(api.post("/org-units", orgUnit.formatted(", \"displayOrder\": -2147483649"))));
        failures.add(failure(api.post("/assignments", assignment.formatted(", \"displayOrder\": 2147483648"))));
        failures.add(failure(api.post("/employments", newEmployment.formatted(", \"terms\": [{\"validFrom\": "
                + "\"2026-05-01\", \"version\": 9223372036854775808}]"))));
        failures.add(failure(api.post("/ref-items", refItem.formatted("a", DIGITS))));
        failures.add(failure(api.post("/org-units", orgUnit.formatted(", \"attributes\": {\"beds\": " + DIGITS
                + "}"))));
        // Such a percentage made the server run out of memory when it wrote the value into the error.
        failures.add(failure(api.post("/employments", newEmployment.formatted(", \"workPercent\": 1e999999999"))));
        failures.add(failure(api.post("/assignments", assignment.formatted(", \"allocationPercent\": 1e-999999999"))));
        HttpResponse<String> japanese = api.withAcceptLanguage("ja").post("/ref-items",
                refItem.formatted("a", "2147483648"));

        assertEquals(List.of("400 invalid.out_of_range displayOrder" + INT,
                "400 invalid.out_of_range displayOrder" + INT,
                "400 invalid.out_of_range displayOrder" + INT,
                "400 invalid.out_of_range version" + LONG,
                "400 invalid.out_of_range version" + LONG,
                "400 invalid.out_of_range version" + LONG,
                "400 invalid.out_of_range displayOrder" + INT,
                "400 invalid.out_of_range displayOrder" + INT,
                "400 invalid.out_of_range terms[0].version" + LONG,
                "400 invalid.too_long displayOrder 1000",
                "400 invalid.too_long attributes.beds 1000",
                "400 invalid.percent workPercent",
                "400 invalid.percent allocationPercent"), failures);
        assertEquals("displayOrder は -2147483648 から 2147483647 までの整数にしてください。",
                Api.json(japanese).get("message").asText(), japanese.body());
        assertEquals(before, RealmExportIT.export(api));

        // The largest and the smallest number of each field is read.
        JsonNode largest = Api.json(api.post("/ref-items", refItem.formatted("c", "2147483647")));
        JsonNode smallest = Api.json(api.post("/ref-items", refItem.formatted("d", "-2147483648")));
        assertEquals(List.of(2147483647, -2147483648), List.of(largest.get("displayOrder").asInt(),
                smallest.get("displayOrder").asInt()));
        // The version is read, and it is not the version of the hierarchy.
        assertEquals(List.of("409", "409"), List.of(
                String.valueOf(api.put(hierarchy, rename.formatted("9223372036854775807")).statusCode()),
                String.valueOf(api.put(hierarchy, rename.formatted("-9223372036854775808")).statusCode())));
    }

    private static String rows(String list, String rows) {
        return "{\"effectiveDate\": \"2026-05-01\", \"" + list + "\": [" + rows + "]}";
    }

    @Test
    void anImportFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("range-import");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.post("/imports", rows("refItems",
                "{\"externalId\": \"I1\", \"displayOrder\": 99999999999}"))));
        failures.add(failure(api.post("/imports", rows("orgUnits", "{\"externalId\": \"H\"}, "
                + "{\"externalId\": \"W\", \"displayOrder\": -2147483649}"))));
        failures.add(failure(api.post("/imports", rows("assignments",
                "{\"externalId\": \"A1\", \"displayOrder\": 2147483648}"))));
        failures.add(failure(api.post("/imports", rows("refItems",
                "{\"externalId\": \"I1\", \"displayOrder\": " + DIGITS + "}"))));
        failures.add(failure(api.post("/imports", rows("orgUnits",
                "{\"externalId\": \"W\", \"attributes\": {\"beds\": " + DIGITS + "}}"))));

        assertEquals(List.of("400 invalid.out_of_range refItems[0].displayOrder" + INT,
                "400 invalid.out_of_range orgUnits[1].displayOrder" + INT,
                "400 invalid.out_of_range assignments[0].displayOrder" + INT,
                "400 invalid.too_long refItems[0].displayOrder 1000",
                "400 invalid.too_long orgUnits[0].attributes.beds 1000"), failures);
        assertEquals(before, RealmExportIT.export(api));

        ok(api.post("/imports", "{\"effectiveDate\": \"2026-05-01\", "
                + "\"refItems\": [{\"externalId\": \"I1\", \"displayOrder\": 2147483647}], "
                + "\"orgUnits\": [{\"externalId\": \"W\", \"displayOrder\": -2147483648}]}"));
        assertEquals(List.of(2147483647, -2147483648), List.of(
                Api.json(api.get("/ref-items/" + lookup(api, "REF_ITEM", "I1"))).get("displayOrder").asInt(),
                Api.json(api.get("/org-units/" + lookup(api, "ORG_UNIT", "W") + "?asOf=2026-05-01"))
                        .get("displayOrder").asInt()));
    }

    // A JSON pointer such as /orgUnits/0/versions/0 as a path such as orgUnits[0].versions[0].
    private static String path(String pointer) {
        return pointer.substring(1).replaceAll("/(\\d+)", "[$1]").replace('/', '.');
    }

    private static ObjectNode with(ObjectNode document, String pointer, JsonNode value) {
        ObjectNode changed = document.deepCopy();
        int last = pointer.lastIndexOf('/');
        ((ObjectNode) changed.at(pointer.substring(0, last))).set(pointer.substring(last + 1), value);
        return changed;
    }

    /**
     * A value of a restore document.
     *
     * @param pointer the JSON pointer of the value
     * @param value the value that the document gets
     * @param limits the limits that the error names
     */
    record Change(String pointer, JsonNode value, String limits) {
    }

    // The document is not read yet, so the error has the path in place of a list and an ID of a row.
    @Test
    void aRestoreFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("range-restore");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        ObjectNode before = RealmExportIT.export(api);
        List<Change> changes = List.of(new Change("/refItems/0/displayOrder", NODES.numberNode(2147483648L), INT),
                new Change("/orgUnits/0/versions/0/displayOrder", NODES.numberNode(-2147483649L), INT),
                new Change("/assignments/0/periods/0/displayOrder", NODES.numberNode(99999999999L), INT),
                new Change("/hierarchies/0/version", NODES.numberNode(new BigInteger("9223372036854775808")), LONG),
                new Change("/people/0/version", NODES.numberNode(new BigInteger("-9223372036854775809")), LONG));

        List<String> failures = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Change change : changes) {
            failures.add(failure(api.post("/restore?replace=true",
                    JSON.writeValueAsString(with(before, change.pointer(), change.value())))));
            expected.add("400 invalid.out_of_range " + path(change.pointer()) + change.limits());
        }
        failures.add(failure(api.post("/restore?replace=true", JSON.writeValueAsString(with(before,
                "/refItems/0/displayOrder", NODES.numberNode(new BigInteger(DIGITS)))))));
        expected.add("400 invalid.too_long refItems[0].displayOrder 1000");
        failures.add(failure(api.post("/restore?replace=true", JSON.writeValueAsString(with(before,
                "/employments/0/terms/0/workPercent", NODES.numberNode(new BigDecimal("1e999999999")))))));
        expected.add("400 restore.bad_value workPercent");
        failures.add(failure(api.post("/restore?replace=true", JSON.writeValueAsString(with(before,
                "/assignments/0/periods/0/allocationPercent", NODES.numberNode(new BigDecimal("1e-999999999")))))));
        expected.add("400 restore.bad_value allocationPercent");

        assertEquals(expected, failures);
        assertEquals(before, RealmExportIT.export(api));

        ObjectNode limits = with(with(before, "/refItems/0/displayOrder", NODES.numberNode(Integer.MAX_VALUE)),
                "/orgUnits/0/versions/0/displayOrder", NODES.numberNode(Integer.MIN_VALUE));
        assertEquals(200, api.post("/restore?replace=true", JSON.writeValueAsString(limits)).statusCode());
        assertEquals(limits, RealmExportIT.export(api));
        assertEquals(200, api.post("/restore?replace=true", JSON.writeValueAsString(before)).statusCode());
        assertEquals(before, RealmExportIT.export(api));
    }

    @Test
    void aReorganizationStepFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("range-reorganization");
        Api api = realm.api;
        ok(api.post("/imports", SETUP));
        String hospital = lookup(api, "ORG_UNIT", "H");
        String ward = lookup(api, "ORG_UNIT", "W");
        ObjectNode before = RealmExportIT.export(api);
        String reorganization = "{\"effectiveDate\": \"2027-04-01\", \"steps\": [{\"op\": \"create\", \"ref\": \"a\", "
                + NAMES + ", \"parentId\": \"" + hospital + "\"%s}, {\"op\": \"change\", \"id\": \"" + ward + "\", "
                + "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"乙\"}}%s}]}";

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(
                ", \"displayOrder\": 2147483648", ""))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(
                "", ", \"version\": 9223372036854775808"))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(
                "", ", \"version\": -9223372036854775809"))));
        failures.add(failure(api.postWithVersion("/reorganizations", reorganization.formatted(
                ", \"displayOrder\": " + DIGITS, ""))));

        assertEquals(List.of("400 invalid.out_of_range steps[0].displayOrder" + INT,
                "400 invalid.out_of_range steps[1].version" + LONG,
                "400 invalid.out_of_range steps[1].version" + LONG,
                "400 invalid.too_long steps[0].displayOrder 1000"), failures);
        assertEquals(before, RealmExportIT.export(api));

        assertEquals("409", String.valueOf(api.postWithVersion("/reorganizations", reorganization.formatted(
                "", ", \"version\": 9223372036854775807")).statusCode()));
        assertEquals(200, api.postWithVersion("/reorganizations", reorganization.formatted(
                ", \"displayOrder\": 2147483647", "")).statusCode());
    }

    @Test
    void aKeycloakEndpointAnswersAsBefore() throws Exception {
        TestRealm realm = TestRealm.create("range-keycloak");
        String token = KEYCLOAK.getKeycloakAdminClient().tokenManager().getAccessTokenString();
        Integer lifespan = KEYCLOAK.getKeycloakAdminClient().realm(realm.name).toRepresentation()
                .getAccessTokenLifespan();

        HttpResponse<String> updated = HTTP.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK.getAuthServerUrl() + "/admin/realms/" + realm.name))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"accessTokenLifespan\": 2147483648}",
                        StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals("400 {\"error\":\"HTTP 400 Bad Request\",\"error_description\":\"Cannot parse the JSON\"}",
                updated.statusCode() + " " + updated.body());
        assertEquals(lifespan,
                KEYCLOAK.getKeycloakAdminClient().realm(realm.name).toRepresentation().getAccessTokenLifespan());
    }
}
