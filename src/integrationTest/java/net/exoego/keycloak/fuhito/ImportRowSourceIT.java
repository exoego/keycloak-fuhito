package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static net.exoego.keycloak.fuhito.ImportIT.ok;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code source} of an import row is the system of the external IDs that the row gives. A row that names a
 * stored thing with no external ID can give only the source that the thing keeps. A row that leaves out
 * {@code source} takes the one of the document.
 */
class ImportRowSourceIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"名\"}}";

    // Every list has one stored thing, which the system "hr" made.
    private static final String STORED = """
            {"effectiveDate": "2026-04-01", "source": "hr",
             "refKinds": [{"externalId": "K", "code": "area", %1$s}],
             "attributeDefinitions": [{"externalId": "D", "target": "PERSON", "name": "beds", "type": "NUMBER"}],
             "refItems": [{"externalId": "I", "kind": "area", "code": "tokyo", %1$s},
               {"externalId": "approver", "kind": "UNIT_ROLE", "code": "approver", %1$s},
               {"externalId": "license", "kind": "QUALIFICATION", "code": "license", %1$s}],
             "hierarchies": [{"externalId": "H", "code": "project", "name": "P"}],
             "changeOrders": [{"externalId": "O", "title": "異動"}],
             "orgUnits": [{"externalId": "U1", "validFrom": "2020-04-01", %1$s},
               {"externalId": "U2", "parentExternalId": "U1", "validFrom": "2020-04-01", %1$s}],
             "orgUnitParents": [{"hierarchy": "project", "orgUnitExternalId": "U2", "parentExternalId": "U1"}],
             "people": [{"externalId": "P1", "username": "nurse"}, {"externalId": "P2", "username": "boss"}],
             "qualifications": [{"externalId": "Q", "personExternalId": "P1", "kind": "license"}],
             "employments": [{"externalId": "E1", "personExternalId": "P1", "validFrom": "2020-04-01"},
               {"externalId": "E2", "personExternalId": "P2", "validFrom": "2020-04-01"}],
             "unitRoles": [{"externalId": "R", "employmentExternalId": "E2", "orgUnitExternalId": "U1",
               "role": "approver"}],
             "assignments": [{"externalId": "A1", "employmentExternalId": "E1", "orgUnitExternalId": "U2",
               "validFrom": "2020-04-01"}],
             "reportingLines": [{"externalId": "L", "assignmentExternalId": "A1", "managerPersonExternalId": "P2"}]}
            """.formatted(NAMES);

    /**
     * The stored thing of a list, by the keys that name it with no external ID.
     *
     * @param list the list of a JSON document
     * @param file the list of a CSV file
     * @param id the ID of the thing
     * @param keys the keys that name the thing
     * @param externalId the external ID of the thing in "hr", or null for a parent
     * @param reference a key and the external ID in "hr" of a thing that the row names, or null
     * @param keepsSource true when the thing keeps "hr" as its source
     */
    private record Thing(String list, String file, String id, Map<String, String> keys, String externalId,
            Map.Entry<String, String> reference, boolean keepsSource) {

        Map<String, String> with(String source, String... more) {
            Map<String, String> row = new LinkedHashMap<>(keys);
            row.put("source", source);
            for (int i = 0; i < more.length; i += 2) {
                row.put(more[i], more[i + 1]);
            }
            return row;
        }
    }

    private static List<Thing> stored(TestRealm realm) throws Exception {
        Api api = realm.api;
        realm.user("nurse");
        realm.user("boss");
        ok(api.post("/imports", STORED));
        String unit = idOf(api, "ORG_UNIT", "U2");
        List<Thing> things = new ArrayList<>();
        for (List<String> each : List.of(
                List.of("refKinds", "ref-kinds", "REF_KIND", "K"),
                List.of("attributeDefinitions", "attribute-definitions", "ATTRIBUTE_DEFINITION", "D"),
                List.of("refItems", "ref-items", "REF_ITEM", "I"),
                List.of("hierarchies", "hierarchies", "HIERARCHY", "H"),
                List.of("changeOrders", "change-orders", "CHANGE_ORDER", "O"),
                List.of("unitRoles", "unit-roles", "UNIT_ROLE", "R", "employmentExternalId", "E2"),
                List.of("reportingLines", "reporting-lines", "REPORTING_LINE", "L", "assignmentExternalId", "A1"),
                List.of("orgUnits", "org-units", "ORG_UNIT", "U2", "parentExternalId", "U1"),
                List.of("people", "people", "PERSON", "P1"),
                List.of("employments", "employments", "EMPLOYMENT", "E1", "personExternalId", "P1"),
                List.of("assignments", "assignments", "ASSIGNMENT", "A1", "employmentExternalId", "E1"),
                List.of("qualifications", "qualifications", "QUALIFICATION", "Q", "personExternalId", "P1"))) {
            String id = idOf(api, each.get(2), each.get(3));
            things.add(new Thing(each.get(0), each.get(1), id, Map.of("id", id), each.get(3),
                    each.size() > 4 ? Map.entry(each.get(4), each.get(5)) : null,
                    List.of("ORG_UNIT", "PERSON", "EMPLOYMENT", "ASSIGNMENT", "QUALIFICATION").contains(each.get(2))));
        }
        Map<String, String> parent = new LinkedHashMap<>();
        parent.put("hierarchy", "project");
        parent.put("orgUnitId", unit);
        things.add(new Thing("orgUnitParents", "org-unit-parents", unit, parent, null,
                Map.entry("parentExternalId", "U1"), false));
        return things;
    }

    private static String idOf(Api api, String resourceType, String externalId) throws Exception {
        return ok(api.get("/external-ids/lookup?resourceType=" + resourceType + "&source=hr&externalId="
                + externalId)).get("resourceId").asText();
    }

    // A row that gives no source takes "payroll", the source of the document.
    private static HttpResponse<String> json(Api api, Thing thing, Map<String, String> row) throws Exception {
        String values = row.entrySet().stream()
                .map(key -> "\"" + key.getKey() + "\": "
                        + (key.getValue() == null ? "null" : "\"" + key.getValue() + "\""))
                .collect(Collectors.joining(", "));
        return api.post("/imports", "{\"effectiveDate\": \"2026-04-01\", \"source\": \"payroll\", \""
                + thing.list() + "\": [{" + values + "}]}");
    }

    private static HttpResponse<String> csv(Api api, Thing thing, Map<String, String> row) throws Exception {
        String file = String.join(",", row.keySet()) + "\r\n" + row.values().stream()
                .map(value -> value == null ? "#CLEAR" : value)
                .collect(Collectors.joining(",")) + "\r\n";
        return api.postBytes("/imports/csv/" + thing.file() + "?effectiveDate=2026-04-01&source=payroll",
                "text/csv; charset=UTF-8", file.getBytes(StandardCharsets.UTF_8));
    }

    // The status, and the code, the field, and the ID of the error of the first bad row.
    private static String outcome(HttpResponse<String> response) throws Exception {
        JsonNode error = Api.json(response).path("errors").path(0);
        return response.statusCode() + " " + error.path("error").asText() + " "
                + error.path("params").path("field").asText() + " " + error.path("params").path("id").asText();
    }

    private static String counts(HttpResponse<String> response, Thing thing) throws Exception {
        if (response.statusCode() != 200) {
            return response.statusCode() + " " + response.body();
        }
        return Api.json(response).get("counts").get(thing.list()).toString();
    }

    @Test
    void aJsonRowUsesItsSourceOrTakesTheOneOfTheDocument() throws Exception {
        rowsUseTheirSource("row-source-json", ImportRowSourceIT::json);
    }

    @Test
    void aCsvRowUsesItsSourceOrTakesTheOneOfTheFile() throws Exception {
        rowsUseTheirSource("row-source-csv", ImportRowSourceIT::csv);
    }

    // Null takes the source of the document, as a row that leaves it out does.
    private static List<Map<String, String>> rowsThatUseTheirSource(Thing thing) {
        List<Map<String, String>> rows = new ArrayList<>(List.of(thing.keys(), thing.with(null)));
        if (thing.externalId() != null) {
            rows.add(thing.with("hr", "externalId", thing.externalId()));
        }
        if (thing.reference() != null) {
            rows.add(thing.with("hr", thing.reference().getKey(), thing.reference().getValue()));
        }
        if (thing.keepsSource()) {
            rows.add(thing.with("hr"));
        }
        return rows;
    }

    private interface Load {
        HttpResponse<String> apply(Api api, Thing thing, Map<String, String> row) throws Exception;
    }

    private static void rowsUseTheirSource(String prefix, Load load) throws Exception {
        TestRealm realm = TestRealm.withEmployer(prefix);
        Api api = realm.api;
        List<Thing> things = stored(realm);
        JsonNode before = RealmExportIT.export(api);

        // A thing that keeps no source takes none. Another one keeps "hr".
        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (Thing thing : things) {
            expected.add(thing.list() + " 400 import.value_fixed source " + thing.id());
            got.add(thing.list() + " " + outcome(load.apply(api, thing,
                    thing.with(thing.keepsSource() ? "payroll" : "hr"))));
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));

        expected.clear();
        got.clear();
        for (Thing thing : things) {
            for (Map<String, String> row : rowsThatUseTheirSource(thing)) {
                expected.add(thing.list() + " " + row + " {\"created\":0,\"changed\":0,\"unchanged\":1}");
                got.add(thing.list() + " " + row + " " + counts(load.apply(api, thing, row), thing));
            }
        }
        assertEquals(expected, got);
        assertEquals(before, RealmExportIT.export(api));
    }
}
