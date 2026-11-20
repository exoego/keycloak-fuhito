package net.exoego.keycloak.fuhito;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write of a stored resource that gives another value of a field that never changes fails, and writes nothing.
 * The stored value, as a read gives it, still works, and so does a write that leaves the field out.
 */
class FixedValuesIT {

    private static final String NAMES = "\"primaryLanguage\": \"ja\", \"names\": {\"ja\": {\"name\": \"東京\"}}";

    private static String created(HttpResponse<String> response) throws Exception {
        assertEquals(201, response.statusCode(), response.body());
        return Api.json(response).get("id").asText();
    }

    private static JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return Api.json(response);
    }

    private static ObjectNode read(Api api, String path) throws Exception {
        return ExternalIdsIT.read(api, path).deepCopy();
    }

    // The status, the code, and the values of the error, so that one assertion shows every case.
    private static String outcome(HttpResponse<String> response, String... params) throws Exception {
        JsonNode body = Api.json(response);
        StringBuilder outcome = new StringBuilder().append(response.statusCode()).append(' ')
                .append(body.path("error").asText());
        for (String param : params) {
            outcome.append(' ').append(body.path("params").path(param).asText());
        }
        return outcome.toString();
    }

    // Puts the read of each path with the values of its case, and expects that each write fails and names the first
    // field of the case.
    private static void eachFails(Api api, List<List<String>> cases) throws Exception {
        List<String> expected = new ArrayList<>();
        List<String> got = new ArrayList<>();
        for (List<String> fieldCase : cases) {
            String path = fieldCase.get(0);
            String field = fieldCase.get(1);
            ObjectNode body = read(api, path).put(field, fieldCase.get(2));
            for (int i = 3; i < fieldCase.size(); i += 2) {
                body.put(fieldCase.get(i), fieldCase.get(i + 1));
            }
            String name = String.join(" ", fieldCase);
            expected.add(name + " 400 invalid.value_fixed " + field);
            got.add(name + " " + outcome(api.put(path, body.toString()), "field"));
        }
        assertEquals(expected, got);
    }

    // A write of the read works, and so does one that leaves out the fields that never change.
    private static void readAsBodyWorks(Api api, String path, String... fields) throws Exception {
        ok(api.put(path, read(api, path).toString()));
        ok(api.put(path, read(api, path).without(List.of(fields)).toString()));
    }

    @Test
    void aPutWithAnotherCodeOrPersonFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.withEmployer("fixed-values");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        for (String kind : List.of("site", "area")) {
            created(api.post("/ref-kinds", "{\"code\": \"" + kind + "\", " + NAMES + "}"));
        }
        String item = created(api.post("/ref-items", "{\"kind\": \"site\", \"code\": \"tokyo\", " + NAMES + "}"));
        String definition = created(api.post("/attribute-definitions", "{\"target\": \"PERSON\", \"name\": \"beds\", "
                + "\"type\": \"NUMBER\"}"));
        String hierarchy = created(api.post("/hierarchies", "{\"code\": \"project\", \"name\": \"P\"}"));
        String nurse = realm.user("nurse");
        String person = created(api.post("/people", "{\"userId\": \"" + nurse + "\"}"));
        String doctor = realm.user("doctor");
        String other = created(api.post("/people", "{\"userId\": \"" + doctor + "\"}"));
        String plain = created(api.post("/people", "{" + NAMES + "}"));
        // The user has no person, so a write that used the user would create one.
        String nobody = realm.user("nobody");
        String qualification = created(api.post("/qualifications", "{\"personId\": \"" + person + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"}"));
        String ofPlain = created(api.post("/qualifications", "{\"personId\": \"" + plain + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"}"));
        String ofQualification = "/qualifications/" + qualification;
        ObjectNode before = RealmExportIT.export(api);

        eachFails(api, List.of(
                List.of("/ref-kinds/site", "code", "area"),
                List.of("/hierarchies/" + hierarchy, "code", "default"),
                List.of("/ref-items/" + item, "kind", "area"),
                List.of("/ref-items/" + item, "code", "osaka"),
                List.of("/attribute-definitions/" + definition, "target", "ORG_UNIT"),
                List.of("/attribute-definitions/" + definition, "name", "rooms"),
                List.of(ofQualification, "personId", other),
                List.of(ofQualification, "personId", other, "userId", doctor),
                List.of(ofQualification, "userId", doctor),
                List.of(ofQualification, "userId", nobody),
                List.of("/qualifications/" + ofPlain, "userId", doctor)));
        String body = read(api, "/hierarchies/" + hierarchy).put("code", "region").toString();
        assertEquals("code never changes. Leave it out, or give the stored value.",
                Api.json(api.put("/hierarchies/" + hierarchy, body)).get("message").asText());
        assertEquals("code は変えられません。指定しないか、保存されている値を指定してください。",
                Api.json(api.withAcceptLanguage("ja").put("/hierarchies/" + hierarchy, body)).get("message")
                        .asText());
        assertEquals(before, RealmExportIT.export(api));

        readAsBodyWorks(api, "/ref-kinds/site", "code");
        readAsBodyWorks(api, "/hierarchies/" + hierarchy, "code");
        readAsBodyWorks(api, "/ref-items/" + item, "kind", "code");
        readAsBodyWorks(api, "/attribute-definitions/" + definition, "target", "name");
        readAsBodyWorks(api, ofQualification, "personId", "userId");
        // Each way to name the stored person works alone.
        ok(api.put(ofQualification, read(api, ofQualification).without("userId").toString()));
        ok(api.put(ofQualification, read(api, ofQualification).without("personId").toString()));
        JsonNode kept = ok(api.get(ofQualification));
        assertEquals(List.of(person, nurse), List.of(kept.get("personId").asText(), kept.get("userId").asText()));
    }

    @Test
    void aPutWithAnotherSourceAndNoExternalIdFailsAndWritesNothing() throws Exception {
        TestRealm realm = TestRealm.create("fixed-source");
        Api api = realm.api;
        realm.refItem("QUALIFICATION", "license", "免許");
        String fromHr = ", \"source\": \"hr\", \"externalId\": \"%s\"";
        String unit = created(api.post("/org-units", "{" + NAMES + ", \"validFrom\": \"2020-04-01\""
                + fromHr.formatted("U1") + "}"));
        String person = created(api.post("/people", "{" + NAMES + fromHr.formatted("P1") + "}"));
        String qualification = created(api.post("/qualifications", "{\"personId\": \"" + person + "\", "
                + "\"kind\": \"license\", \"validFrom\": \"2020-04-01\"" + fromHr.formatted("Q1") + "}"));
        // A resource that no system made has no source, so any source of a write is another one.
        String plain = created(api.post("/people", "{" + NAMES + "}"));
        List<String> paths = List.of("/org-units/" + unit, "/people/" + person, "/qualifications/" + qualification,
                "/people/" + plain);
        ObjectNode before = RealmExportIT.export(api);

        List<List<String>> cases = new ArrayList<>();
        for (String path : paths) {
            cases.add(List.of(path, "source", "payroll"));
        }
        eachFails(api, cases);
        assertEquals(before, RealmExportIT.export(api));

        for (String path : paths) {
            readAsBodyWorks(api, path, "source");
            // With an ID, the source names the system of that ID, and the resource keeps its own.
            JsonNode written = ok(api.put(path, read(api, path).put("source", "payroll")
                    .put("externalId", "X-" + path.substring(path.lastIndexOf('/') + 1)).toString()));
            assertEquals(path.equals("/people/" + plain) ? null : "hr", written.path("source").textValue(), path);
            assertTrue(Api.externalIds(written).stream().anyMatch(id -> id.startsWith("payroll=X-")), path);
        }
    }
}
