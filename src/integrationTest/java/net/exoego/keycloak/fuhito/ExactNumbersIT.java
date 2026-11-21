package net.exoego.keycloak.fuhito;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A number that a field does not keep as sent fails with an error that names the field, and nothing is written. A
 * number of a free attribute must be one that a double gives back as the same number. A number that a field keeps is
 * stored as sent.
 */
class ExactNumbersIT {

    // Reads and writes the numbers of a body as they are written.
    private static final ObjectMapper EXACT = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    // A double gives back another number for each of these.
    private static final List<String> CHANGED = List.of("1e400", "-1e400", "1e-400", "50.000000000000001",
            "9007199254740993", "12345678901234567890");

    // A double gives back each of these, from the largest and the smallest one.
    private static final List<String> KEPT = List.of("1.7976931348623157e308", "-1.7976931348623157e308",
            "4.9e-324", "9007199254740992", "-9007199254740992", "0.1", "123456789012345.6",
            "100000000000000000000");

    // Jackson cannot read this number as a decimal.
    private static final String UNREADABLE = "1e9999999999";

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"甲\"}}";

    private static final String SETUP = """
            {"effectiveDate": "2026-04-01",
             "orgUnits": [
               {"externalId": "H", "primaryLanguage": "ja", "names": {"ja": {"name": "病院"}}, "validFrom": "2020-04-01"},
               {"externalId": "W", "primaryLanguage": "ja", "names": {"ja": {"name": "病棟"}},
                "parentExternalId": "H", "validFrom": "2020-04-01"}],
             "people": [{"externalId": "P1", "primaryLanguage": "ja", "names": {"ja": {"name": "山田"}}}],
             "employments": [{"externalId": "E1", "personExternalId": "P1", "validFrom": "2020-04-01",
                              "workPercent": 80}],
             "assignments": [
               {"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "W", "validFrom": "2020-04-01",
                "allocationPercent": 50}]}
            """;

    // The status, the error, and the field of a response. A row of an import has its list and its place.
    private static String failure(HttpResponse<String> response) throws Exception {
        if (response.statusCode() < 300) {
            return String.valueOf(response.statusCode());
        }
        JsonNode body = Api.json(response);
        JsonNode row = body.path("errors").path(0);
        if (!row.isMissingNode()) {
            return response.statusCode() + " " + row.get("list").asText() + "[" + row.get("row").asInt() + "] "
                    + row.get("error").asText() + " " + named(row.path("params"));
        }
        if (!body.path("params").isObject()) {
            return response.statusCode() + " " + response.body();
        }
        return response.statusCode() + " " + body.get("error").asText() + " " + named(body.get("params"));
    }

    private static String named(JsonNode params) {
        String field = params.has("field") ? params.get("field").asText() : params.path("name").asText();
        return params.has("rule") ? field + " " + params.get("rule").asText() : field;
    }

    // A number in a form that does not depend on how it was written, or the value when it is no number.
    private static String number(JsonNode value) {
        return value.isNumber() ? value.decimalValue().stripTrailingZeros().toString() : value.toString();
    }

    private static List<String> numbers(List<String> written) {
        return written.stream().map(n -> new BigDecimal(n).stripTrailingZeros().toString()).toList();
    }

    private static JsonNode read(Api api, String path) throws Exception {
        HttpResponse<String> response = api.get(path);
        assertEquals(200, response.statusCode(), response.body());
        return EXACT.readTree(response.body());
    }

    private static String lookup(Api api, String resourceType, String externalId) throws Exception {
        return Api.json(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=import&externalId="
                + externalId)).get("resourceId").asText();
    }

    private static TestRealm realm(String prefix) throws Exception {
        TestRealm realm = TestRealm.withEmployer(prefix);
        Api api = realm.api;
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"}").statusCode());
        assertEquals(201, api.post("/attribute-definitions", "{\"target\": \"ORG_UNIT\", \"name\": \"sizes\", "
                + "\"type\": \"NUMBER\", \"multiple\": true}").statusCode());
        ok(api.post("/imports", SETUP));
        return realm;
    }

    @Test
    void aRestWriteStoresANumberAsSentOrFailsAndWritesNothing() throws Exception {
        Api api = realm("exact-rest").api;
        String hospital = lookup(api, "ORG_UNIT", "H");
        String person = lookup(api, "PERSON", "P1");
        ObjectNode before = RealmExportIT.export(api);
        String unit = "{" + NAMES + ", \"parentId\": \"" + hospital + "\", \"validFrom\": \"2026-05-01\", "
                + "\"attributes\": %s}";

        List<String> failures = new ArrayList<>();
        for (String number : CHANGED) {
            failures.add(failure(api.post("/org-units", unit.formatted("{\"beds\": " + number + "}"))));
        }
        failures.add(failure(api.post("/org-units", unit.formatted("{\"sizes\": [1, 1e400]}"))));
        failures.add(failure(api.post("/org-units", unit.formatted("{\"beds\": " + UNREADABLE + "}"))));
        failures.add(failure(api.post("/employments", "{\"personId\": \"" + person + "\", \"validFrom\": "
                + "\"2026-05-01\", \"workPercent\": " + UNREADABLE + "}")));

        List<String> expected = new ArrayList<>(CHANGED.stream().map(n -> "400 attribute.bad_value beds").toList());
        expected.addAll(List.of("400 attribute.bad_value sizes", "400 invalid.number attributes.beds",
                "400 invalid.number workPercent"));
        assertEquals(expected, failures);
        assertEquals(before, RealmExportIT.export(api));

        List<String> stored = new ArrayList<>();
        for (String number : KEPT) {
            HttpResponse<String> created = api.post("/org-units", unit.formatted("{\"beds\": " + number + "}"));
            assertEquals(201, created.statusCode(), created.body());
            stored.add(number(read(api, "/org-units/" + Api.json(created).get("id").asText() + "?asOf=2026-05-01")
                    .at("/attributes/beds")));
        }
        assertEquals(numbers(KEPT), stored);
        HttpResponse<String> list = api.post("/org-units", unit.formatted("{\"sizes\": [" + String.join(", ", KEPT)
                + "]}"));
        assertEquals(201, list.statusCode(), list.body());
        List<String> elements = new ArrayList<>();
        read(api, "/org-units/" + Api.json(list).get("id").asText() + "?asOf=2026-05-01").at("/attributes/sizes")
                .forEach(element -> elements.add(number(element)));
        assertEquals(numbers(KEPT), elements);
    }

    private static String rows(String list, String rows) {
        return "{\"effectiveDate\": \"2026-05-01\", \"" + list + "\": [" + rows + "]}";
    }

    @Test
    void aRowOfAnImportReadsANumberAsSentAndWritesNothingWhenItFails() throws Exception {
        Api api = realm("exact-import").api;
        ObjectNode before = RealmExportIT.export(api);
        String terms = "{\"externalId\": \"E1\", \"workPercent\": %s}";
        String allocation = "{\"externalId\": \"A1\", \"allocationPercent\": %s}";
        String beds = "{\"externalId\": \"W\", \"attributes\": {\"beds\": %s}}";

        List<String> failures = new ArrayList<>();
        failures.add(failure(api.post("/imports", rows("employments", terms.formatted("50.000000000000001")))));
        failures.add(failure(api.post("/imports", "{\"dryRun\": true, " + rows("employments",
                terms.formatted("50.000000000000001")).substring(1))));
        failures.add(failure(api.post("/imports", rows("employments", terms.formatted("1e400")))));
        failures.add(failure(api.post("/imports", rows("assignments", allocation.formatted("50.000000000000001")))));
        failures.add(failure(api.post("/imports", rows("assignments", allocation.formatted("1e-400")))));
        for (String number : CHANGED) {
            failures.add(failure(api.post("/imports", rows("orgUnits", beds.formatted(number)))));
        }
        failures.add(failure(api.post("/imports", rows("orgUnits", beds.formatted(UNREADABLE)))));
        failures.add(failure(api.post("/imports", rows("employments", terms.formatted(UNREADABLE)))));

        List<String> expected = new ArrayList<>(List.of("400 employments[0] invalid.percent workPercent",
                "400 employments[0] invalid.percent workPercent", "400 employments[0] invalid.percent workPercent",
                "400 assignments[0] invalid.percent allocationPercent",
                "400 assignments[0] invalid.percent allocationPercent"));
        CHANGED.forEach(n -> expected.add("400 orgUnits[0] attribute.bad_value beds"));
        expected.addAll(List.of("400 invalid.number orgUnits[0].attributes.beds",
                "400 invalid.number employments[0].workPercent"));
        assertEquals(expected, failures);
        assertEquals(before, RealmExportIT.export(api));

        String unit = "/org-units/" + lookup(api, "ORG_UNIT", "W") + "?asOf=2026-05-01";
        List<String> stored = new ArrayList<>();
        for (String number : KEPT) {
            ok(api.post("/imports", rows("orgUnits", beds.formatted(number))));
            stored.add(number(read(api, unit).at("/attributes/beds")));
        }
        assertEquals(numbers(KEPT), stored);
        ok(api.post("/imports", rows("employments", terms.formatted("0.01"))));
        ok(api.post("/imports", rows("assignments", allocation.formatted("1E+2"))));
        assertEquals(List.of("0.01", "1E+2"), List.of(
                number(from(read(api, "/employments/" + lookup(api, "EMPLOYMENT", "E1")).get("terms"))
                        .get("workPercent")),
                number(from(read(api, "/assignments/" + lookup(api, "ASSIGNMENT", "A1")).get("periods"))
                        .get("allocationPercent"))));
    }

    // The period of a read that starts on the date of the rows.
    private static JsonNode from(JsonNode periods) {
        for (JsonNode period : periods) {
            if (period.get("validFrom").asText().equals("2026-05-01")) {
                return period;
            }
        }
        throw new AssertionError("No period starts on 2026-05-01 in " + periods);
    }

    private static HttpResponse<String> csv(Api api, String cell) throws Exception {
        return api.postBytes("/imports/csv/org-units?effectiveDate=2026-05-01", "text/csv; charset=UTF-8",
                ("外部ID,attributes.beds\r\nW," + cell + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCsvCellOfANumberAttributeStoresItsNumberAsWrittenOrFailsAndWritesNothing() throws Exception {
        Api api = realm("exact-csv").api;
        ObjectNode before = RealmExportIT.export(api);

        List<String> failures = new ArrayList<>();
        for (String number : Stream.concat(CHANGED.stream(), Stream.of("1e999999999", UNREADABLE)).toList()) {
            failures.add(failure(csv(api, number)));
        }

        assertEquals(Stream.concat(CHANGED.stream(), Stream.of("1e999999999", UNREADABLE))
                .map(n -> "400 orgUnits[0] csv.bad_number attributes.beds").toList(), failures);
        assertEquals(before, RealmExportIT.export(api));

        String unit = "/org-units/" + lookup(api, "ORG_UNIT", "W") + "?asOf=2026-05-01";
        List<String> stored = new ArrayList<>();
        for (String number : KEPT) {
            HttpResponse<String> loaded = csv(api, number);
            assertEquals(200, loaded.statusCode(), loaded.body());
            stored.add(number(read(api, unit).at("/attributes/beds")));
        }
        assertEquals(numbers(KEPT), stored);
    }

    // The numbers are written into the text of the document, so that no mapper reads them first.
    private static String withAttributes(String attributes) throws Exception {
        ObjectNode document = RestoreKeysIT.document();
        ((ObjectNode) document.at("/people/0")).put("attributes", "ATTRIBUTES");
        return EXACT.writeValueAsString(document).replace("\"ATTRIBUTES\"", attributes);
    }

    // An export gives only numbers that a write stored, and a write stores only the numbers that a double keeps.
    @Test
    void aRestoreTakesTheNumbersOfAttributesThatAWriteStores() throws Exception {
        TestRealm target = RestoreKeysIT.target("exact-restore");
        try {
            Api api = target.api;
            List<String> failures = new ArrayList<>();
            for (String number : CHANGED) {
                failures.add(failure(api.post("/restore?replace=true", withAttributes("{\"beds\": " + number + "}"))));
            }
            failures.add(failure(api.post("/restore?replace=true", withAttributes("{\"sizes\": [1, 1e400]}"))));
            failures.add(failure(api.post("/restore?replace=true", withAttributes("{\"beds\": " + UNREADABLE + "}"))));

            List<String> expected = new ArrayList<>(CHANGED.stream()
                    .map(n -> "400 restore.bad_value attributes.beds attribute.bad_value").toList());
            expected.addAll(List.of("400 restore.bad_value attributes.sizes attribute.bad_value",
                    "400 invalid.number people[0].attributes.beds"));
            assertEquals(expected, failures);
            assertEquals("0", KeycloakEnvironment.sql("select count(*) from fuhito_person where realm_id = '"
                    + target.id() + "'"));

            HttpResponse<String> restored = api.post("/restore", withAttributes("{\"sizes\": ["
                    + String.join(", ", KEPT) + "]}"));
            assertEquals(200, restored.statusCode(), restored.body());
            List<String> elements = new ArrayList<>();
            read(api, "/people/p-1").at("/attributes/sizes").forEach(element -> elements.add(number(element)));
            assertEquals(numbers(KEPT), elements);
        } finally {
            target.remove();
        }
    }
}
